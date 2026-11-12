param(
    [string]$GatewayBaseFile,
    [string]$GatewayProdFile
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

function Get-GatewayRouteRecords {
    param([Parameter(Mandatory = $true)][string]$Content)

    $routePattern = '(?ms)^[ \t]*-[ \t]*id:[ \t]*(?<id>\S+)[ \t]*\r?\n(?<body>.*?)(?=^[ \t]*-[ \t]*id:[ \t]*|\z)'
    $records = @()
    foreach ($match in [regex]::Matches($Content, $routePattern)) {
        $body = $match.Groups["body"].Value
        $uriMatches = [regex]::Matches($body, '(?m)^[ \t]+uri:[ \t]*(?<uri>\S+)[ \t]*$')
        $pathMatches = [regex]::Matches($body, '(?m)^[ \t]+-[ \t]*Path=(?<path>\S+)[ \t]*$')
        $records += [pscustomobject]@{
            Id = $match.Groups["id"].Value
            Uri = if ($uriMatches.Count -eq 1) { $uriMatches[0].Groups["uri"].Value } else { $null }
            Path = if ($pathMatches.Count -eq 1) { $pathMatches[0].Groups["path"].Value } else { $null }
        }
    }
    return $records
}

function Assert-GatewayRoute {
    param(
        [Parameter(Mandatory = $true)][string]$Content,
        [Parameter(Mandatory = $true)][string]$Profile,
        [Parameter(Mandatory = $true)][string]$RouteId,
        [Parameter(Mandatory = $true)][string]$ExpectedPath,
        [Parameter(Mandatory = $true)][string]$ExpectedUri
    )

    $routes = @(Get-GatewayRouteRecords -Content $Content)
    $matches = @($routes | Where-Object { $_.Id -eq $RouteId })
    if ($matches.Count -ne 1) {
        Write-Host "FAIL java-gateway $Profile route '$RouteId' must exist exactly once (found $($matches.Count))"
        exit 1
    }

    $route = $matches[0]
    if ($route.Path -ne $ExpectedPath) {
        Write-Host "FAIL java-gateway $Profile route '$RouteId' has unexpected Path '$($route.Path)'"
        exit 1
    }
    if ($route.Uri -ne $ExpectedUri) {
        Write-Host "FAIL java-gateway $Profile route '$RouteId' has unexpected URI '$($route.Uri)'"
        exit 1
    }

    return $route
}

$servicesWithInternalToken = @(
    "java-user",
    "java-ticket",
    "java-order",
    "java-payment",
    "java-notification"
)

foreach ($svc in $servicesWithInternalToken) {
    $prodFile = Join-Path -Path $repoRoot -ChildPath "java/$svc/src/main/resources/application-prod-split.yml"
    if (-not (Test-Path -LiteralPath $prodFile)) {
        Write-Host "FAIL missing prod-split profile: $prodFile"
        exit 1
    }

    $content = Get-Content -Raw -LiteralPath $prodFile
    if ($content -notmatch "(?ms)^internal:\s*\r?\n\s+api:\s*\r?\n\s+token:\s*\$\{INTERNAL_API_TOKEN\}\s*$") {
        Write-Host "FAIL $svc prod-split: internal.api.token must require INTERNAL_API_TOKEN without fallback"
        exit 1
    }

    Write-Host "PASS $svc prod-split internal.api.token requires INTERNAL_API_TOKEN"
}

$servicesWithDatasource = @(
    "java-user",
    "java-ticket",
    "java-order",
    "java-payment",
    "java-notification"
)

foreach ($svc in $servicesWithDatasource) {
    $prodFile = Join-Path -Path $repoRoot -ChildPath "java/$svc/src/main/resources/application-prod-split.yml"
    if (-not (Test-Path -LiteralPath $prodFile)) {
        Write-Host "FAIL missing prod-split profile: $prodFile"
        exit 1
    }

    $content = Get-Content -Raw -LiteralPath $prodFile
    if ($content -notmatch "(?m)^\s+password:\s*\$\{SPRING_DATASOURCE_PASSWORD\}\s*$") {
        Write-Host "FAIL $svc prod-split: datasource password must require SPRING_DATASOURCE_PASSWORD without fallback"
        exit 1
    }

    Write-Host "PASS $svc prod-split datasource password requires SPRING_DATASOURCE_PASSWORD"
}

$servicesWithRabbitMq = @(
    "java-user",
    "java-ticket",
    "java-order",
    "java-payment",
    "java-notification"
)

$requiredRabbitMqEnv = @{
    "host" = "RABBITMQ_HOST"
    "port" = "RABBITMQ_PORT"
    "username" = "RABBITMQ_USER"
    "password" = "RABBITMQ_PASSWORD"
}

foreach ($svc in $servicesWithRabbitMq) {
    $prodFile = Join-Path -Path $repoRoot -ChildPath "java/$svc/src/main/resources/application-prod-split.yml"
    if (-not (Test-Path -LiteralPath $prodFile)) {
        Write-Host "FAIL missing prod-split profile: $prodFile"
        exit 1
    }

    $content = Get-Content -Raw -LiteralPath $prodFile
    foreach ($entry in $requiredRabbitMqEnv.GetEnumerator()) {
        $propertyName = [regex]::Escape($entry.Key)
        $envName = [regex]::Escape($entry.Value)
        if ($content -notmatch "(?m)^\s+${propertyName}:\s*\$\{${envName}\}\s*$") {
            Write-Host "FAIL $svc prod-split: spring.rabbitmq.$($entry.Key) must require $($entry.Value) without fallback"
            exit 1
        }
    }

    Write-Host "PASS $svc prod-split RabbitMQ config requires explicit environment"
}

$servicesWithNacos = @(
    "java-user",
    "java-ticket",
    "java-order",
    "java-payment",
    "java-notification"
)

foreach ($svc in $servicesWithNacos) {
    $prodFile = Join-Path -Path $repoRoot -ChildPath "java/$svc/src/main/resources/application-prod-split.yml"
    if (-not (Test-Path -LiteralPath $prodFile)) {
        Write-Host "FAIL missing prod-split profile: $prodFile"
        exit 1
    }

    $content = Get-Content -Raw -LiteralPath $prodFile
    if ($content -match '\$\{NACOS_HOST:' -or $content -match '\$\{NACOS_PORT:') {
        Write-Host "FAIL $svc prod-split: Nacos server-addr must not use localhost fallback"
        exit 1
    }

    if ($content -notmatch "(?m)^\s+cloud:\s*$" -or $content -notmatch "(?m)^\s+nacos:\s*$") {
        Write-Host "FAIL $svc prod-split: spring.cloud.nacos discovery/config must be declared without fallback"
        exit 1
    }

    $nacosServerAddrMatches = [regex]::Matches($content, '(?m)^\s+server-addr:\s*\$\{NACOS_HOST\}:\$\{NACOS_PORT\}\s*$')
    if ($nacosServerAddrMatches.Count -lt 2) {
        Write-Host "FAIL $svc prod-split: spring.cloud.nacos discovery/config must require NACOS_HOST and NACOS_PORT without fallback"
        exit 1
    }

    Write-Host "PASS $svc prod-split Nacos config requires explicit environment"
}

$servicesWithSeata = @(
    "java-ticket",
    "java-order",
    "java-payment"
)

foreach ($svc in $servicesWithSeata) {
    $prodFile = Join-Path -Path $repoRoot -ChildPath "java/$svc/src/main/resources/application-prod-split.yml"
    if (-not (Test-Path -LiteralPath $prodFile)) {
        Write-Host "FAIL missing prod-split profile: $prodFile"
        exit 1
    }

    $content = Get-Content -Raw -LiteralPath $prodFile
    if ($content -notmatch "(?ms)^seata:\s*\r?\n\s+enabled:\s*\$\{SEATA_ENABLED\}\s*$") {
        Write-Host "FAIL $svc prod-split: seata.enabled must require SEATA_ENABLED without fallback"
        exit 1
    }

    $nacosServerAddrMatches = [regex]::Matches($content, '(?m)^\s+server-addr:\s*\$\{NACOS_HOST\}:\$\{NACOS_PORT\}\s*$')
    if ($nacosServerAddrMatches.Count -lt 4) {
        Write-Host "FAIL $svc prod-split: Seata registry/config must require NACOS_HOST and NACOS_PORT without fallback"
        exit 1
    }

Write-Host "PASS $svc prod-split Seata config requires explicit environment"
}

$ticketProdFile = Join-Path -Path $repoRoot -ChildPath "java/java-ticket/src/main/resources/application-prod-split.yml"
$ticketProd = Get-Content -Raw -LiteralPath $ticketProdFile
if ($ticketProd -match '\$\{OMNI_SEARCH_PROVIDER:' -or $ticketProd -match '\$\{OMNI_SEARCH_REQUIRE_ES:') {
    Write-Host "FAIL java-ticket prod-split: search provider and require-es must be fixed, not defaulted from env fallback"
    exit 1
}
if ($ticketProd -notmatch "(?m)^\s+provider:\s*elasticsearch\s*$" -or $ticketProd -notmatch "(?m)^\s+require-elasticsearch:\s*true\s*$") {
    Write-Host "FAIL java-ticket prod-split: search must be fixed to Elasticsearch and require ES"
    exit 1
}
if ($ticketProd -notmatch "(?m)^\s+uris:\s*\$\{ELASTICSEARCH_URIS:\$\{SPRING_ELASTICSEARCH_URIS\}\}\s*$") {
    Write-Host "FAIL java-ticket prod-split: spring.elasticsearch.uris must come from ELASTICSEARCH_URIS or SPRING_ELASTICSEARCH_URIS without local fallback"
    exit 1
}
Write-Host "PASS java-ticket prod-split search is fixed to required Elasticsearch"

$gatewayProdFile = if ($GatewayProdFile) {
    (Resolve-Path -LiteralPath $GatewayProdFile).Path
} else {
    Join-Path -Path $repoRoot -ChildPath "java/java-gateway/src/main/resources/application-prod-split.yml"
}
if (-not (Test-Path -LiteralPath $gatewayProdFile)) {
    Write-Host "FAIL missing Gateway prod-split profile: $gatewayProdFile"
    exit 1
}

$gatewayProd = Get-Content -Raw -LiteralPath $gatewayProdFile
if ($gatewayProd -match '\$\{NACOS_HOST:' -or $gatewayProd -match '\$\{NACOS_PORT:') {
    Write-Host "FAIL java-gateway prod-split: Nacos server-addr must not use localhost fallback"
    exit 1
}

if ($gatewayProd -notmatch "(?m)^\s+cloud:\s*$" -or $gatewayProd -notmatch "(?m)^\s+nacos:\s*$") {
    Write-Host "FAIL java-gateway prod-split: spring.cloud.nacos discovery/config must be declared without fallback"
    exit 1
}

$gatewayNacosServerAddrMatches = [regex]::Matches($gatewayProd, '(?m)^\s+server-addr:\s*\$\{NACOS_HOST\}:\$\{NACOS_PORT\}\s*$')
if ($gatewayNacosServerAddrMatches.Count -lt 2) {
    Write-Host "FAIL java-gateway prod-split: spring.cloud.nacos discovery/config must require NACOS_HOST and NACOS_PORT without fallback"
    exit 1
}

Write-Host "PASS java-gateway prod-split Nacos config requires explicit environment"

$gatewayBaseFile = if ($GatewayBaseFile) {
    (Resolve-Path -LiteralPath $GatewayBaseFile).Path
} else {
    Join-Path -Path $repoRoot -ChildPath "java/java-gateway/src/main/resources/application.yml"
}
if (-not (Test-Path -LiteralPath $gatewayBaseFile)) {
    Write-Host "FAIL missing Gateway base profile: $gatewayBaseFile"
    exit 1
}

$gatewayBase = Get-Content -Raw -LiteralPath $gatewayBaseFile
$baseWaitlistRoute = Assert-GatewayRoute `
    -Content $gatewayBase `
    -Profile "base" `
    -RouteId "waitlist-service" `
    -ExpectedPath "/api/waitlist/**" `
    -ExpectedUri '${GATEWAY_WAITLIST_SERVICE_URI:http://localhost:3001}'
$baseGrabRoute = Assert-GatewayRoute `
    -Content $gatewayBase `
    -Profile "base" `
    -RouteId "grab-service" `
    -ExpectedPath "/api/grab/**" `
    -ExpectedUri '${GATEWAY_GRAB_SERVICE_URI:http://localhost:3001}'
Write-Host "PASS java-gateway base route IDs, paths and URIs are valid"

$gatewayLegacyProdRoutesFile = Join-Path -Path $repoRoot -ChildPath "java/java-gateway/src/main/resources/application-prod-split.properties"
if (Test-Path -LiteralPath $gatewayLegacyProdRoutesFile) {
    Write-Host "FAIL java-gateway prod-split must not use sparse application-prod-split.properties route overrides"
    exit 1
}

$prodWaitlistRoute = Assert-GatewayRoute `
    -Content $gatewayProd `
    -Profile "prod-split" `
    -RouteId "waitlist-service" `
    -ExpectedPath "/api/waitlist/**" `
    -ExpectedUri '${GATEWAY_WAITLIST_SERVICE_URI}'
$prodGrabRoute = Assert-GatewayRoute `
    -Content $gatewayProd `
    -Profile "prod-split" `
    -RouteId "grab-service" `
    -ExpectedPath "/api/grab/**" `
    -ExpectedUri '${GATEWAY_GRAB_SERVICE_URI}'
Write-Host "PASS java-gateway prod-split route IDs, paths and URIs are valid"

if ($gatewayProd -notmatch '(?ms)^\s+- id:\s*waitlist-service\s*\r?\n\s+uri:\s*\$\{GATEWAY_WAITLIST_SERVICE_URI\}\s*$') {
    Write-Host "FAIL java-gateway prod-split: waitlist route must require GATEWAY_WAITLIST_SERVICE_URI without fallback"
    exit 1
}

if ($gatewayProd -notmatch '(?ms)^\s+- id:\s*grab-service\s*\r?\n\s+uri:\s*\$\{GATEWAY_GRAB_SERVICE_URI\}\s*$') {
    Write-Host "FAIL java-gateway prod-split: grab route must require GATEWAY_GRAB_SERVICE_URI without fallback"
    exit 1
}

if ($gatewayProd -match 'localhost:3001|127\.0\.0\.1:3001|\$\{GATEWAY_(?:WAITLIST|GRAB)_SERVICE_URI:') {
    Write-Host "FAIL java-gateway prod-split route overrides must not contain local fallback URIs"
    exit 1
}

Write-Host "PASS java-gateway prod-split complete route list requires explicit grab/waitlist environment"

$javaUserProdFile = Join-Path -Path $repoRoot -ChildPath "java/java-user/src/main/resources/application-prod-split.yml"
if (-not (Test-Path -LiteralPath $javaUserProdFile)) {
    Write-Host "FAIL missing java-user prod-split profile: $javaUserProdFile"
    exit 1
}

$javaUserProd = Get-Content -Raw -LiteralPath $javaUserProdFile
if ($javaUserProd -notmatch "(?ms)^omni:\s*\r?\n\s+grab-service:\s*\r?\n\s+url:\s*\$\{GRAB_SERVICE_URL\}\s*$") {
    Write-Host "FAIL java-user prod-split: omni.grab-service.url must require GRAB_SERVICE_URL without fallback"
    exit 1
}

$javaUserClientRoot = Join-Path -Path $repoRoot -ChildPath "java/java-user/src/main/java/com/omni/user/client"
$javaUserClientFiles = Get-ChildItem -LiteralPath $javaUserClientRoot -Recurse -Filter "*.java"
foreach ($sourceFile in $javaUserClientFiles) {
    $source = Get-Content -Raw -LiteralPath $sourceFile.FullName
    if ($source -match 'omni\.grab-service\.url:http://localhost:3001') {
        $relativePath = $sourceFile.FullName.Substring($repoRoot.Length + 1)
        Write-Host "FAIL java-user Feign clients must not contain grab-service localhost fallback in annotation: $relativePath"
        exit 1
    }
}

Write-Host "PASS java-user prod-split grab-service URL requires explicit environment"

$javaMainRootsWithJwt = @(
    "java/java-order/src/main/java",
    "java/java-notification/src/main/java"
)

foreach ($mainRoot in $javaMainRootsWithJwt) {
    $absoluteRoot = Join-Path -Path $repoRoot -ChildPath $mainRoot
    if (-not (Test-Path -LiteralPath $absoluteRoot)) {
        Write-Host "FAIL missing Java source root: $absoluteRoot"
        exit 1
    }

    $javaFiles = Get-ChildItem -LiteralPath $absoluteRoot -Recurse -Filter "*.java"
    foreach ($javaFile in $javaFiles) {
        $source = Get-Content -Raw -LiteralPath $javaFile.FullName
        if ($source -match "omni-jwt-secretomni-jwt-secretomni-jwt-secret") {
            $relativePath = $javaFile.FullName.Substring($repoRoot.Length + 1)
            Write-Host "FAIL production Java source must not contain hardcoded default JWT fallback: $relativePath"
            exit 1
        }
    }
}

Write-Host "PASS production Java source contains no hardcoded default JWT fallback"

$servicesWithJwt = @(
    "java-order",
    "java-notification"
)

foreach ($svc in $servicesWithJwt) {
    $prodFile = Join-Path -Path $repoRoot -ChildPath "java/$svc/src/main/resources/application-prod-split.yml"
    if (-not (Test-Path -LiteralPath $prodFile)) {
        Write-Host "FAIL missing prod-split profile: $prodFile"
        exit 1
    }

    $content = Get-Content -Raw -LiteralPath $prodFile
    if ($content -notmatch "(?ms)^jwt:\s*\r?\n\s+secret:\s*\$\{JWT_SECRET\}\s*$") {
        Write-Host "FAIL $svc prod-split: jwt.secret must require JWT_SECRET without fallback"
        exit 1
    }

    Write-Host "PASS $svc prod-split jwt.secret requires JWT_SECRET"
}

$notificationProdFile = Join-Path -Path $repoRoot -ChildPath "java/java-notification/src/main/resources/application-prod-split.yml"
$notificationProd = Get-Content -Raw -LiteralPath $notificationProdFile
if ($notificationProd -notmatch "(?ms)^omni:\s*\r?\n\s+notification:\s*\r?\n\s+direct-channel:\s*\r?\n\s+enabled:\s*false\s*$") {
    Write-Host "FAIL java-notification prod-split: omni.notification.direct-channel.enabled must be false"
    exit 1
}

$notificationControllerFile = Join-Path -Path $repoRoot -ChildPath "java/java-notification/src/main/java/com/omni/notification/controller/NotificationController.java"
if (-not (Test-Path -LiteralPath $notificationControllerFile)) {
    Write-Host "FAIL missing notification controller: $notificationControllerFile"
    exit 1
}

$notificationController = Get-Content -Raw -LiteralPath $notificationControllerFile
if (-not $notificationController.Contains('omni.notification.direct-channel.enabled:false') -or -not $notificationController.Contains('directChannelEnabled')) {
    Write-Host "FAIL java-notification direct SMS/email endpoints must be gated by omni.notification.direct-channel.enabled"
    exit 1
}

Write-Host "PASS java-notification direct SMS/email endpoints are disabled by default"

$paymentProdFile = Join-Path -Path $repoRoot -ChildPath "java/java-payment/src/main/resources/application-prod-split.yml"
$paymentProd = Get-Content -Raw -LiteralPath $paymentProdFile
$requiredAlipayEnv = @{
    "gateway-url" = "ALIPAY_GATEWAY_URL"
    "app-id" = "ALIPAY_APP_ID"
    "merchant-private-key" = "ALIPAY_MERCHANT_PRIVATE_KEY"
    "alipay-public-key" = "ALIPAY_PUBLIC_KEY"
    "return-url" = "ALIPAY_RETURN_URL"
    "notify-url" = "ALIPAY_NOTIFY_URL"
}

foreach ($entry in $requiredAlipayEnv.GetEnumerator()) {
    $propertyName = [regex]::Escape($entry.Key)
    $envName = [regex]::Escape($entry.Value)
    if ($paymentProd -notmatch "(?m)^\s+${propertyName}:\s*\$\{${envName}\}\s*$") {
        Write-Host "FAIL java-payment prod-split: alipay.$($entry.Key) must require $($entry.Value) without fallback"
        exit 1
    }
}

if ($paymentProd -notmatch "(?m)^\s+mock-qr-fallback-enabled:\s*false\s*$") {
    Write-Host "FAIL java-payment prod-split: alipay.mock-qr-fallback-enabled must be false"
    exit 1
}

if ($paymentProd -notmatch "(?m)^\s+mock-qr-auto-confirm-enabled:\s*false\s*$") {
    Write-Host "FAIL java-payment prod-split: alipay.mock-qr-auto-confirm-enabled must be false"
    exit 1
}

Write-Host "PASS java-payment prod-split Alipay config requires explicit environment"

$paymentBaseFile = Join-Path -Path $repoRoot -ChildPath "java/java-payment/src/main/resources/application.yml"
if (-not (Test-Path -LiteralPath $paymentBaseFile)) {
    Write-Host "FAIL missing java-payment base profile: $paymentBaseFile"
    exit 1
}

$paymentBase = Get-Content -Raw -LiteralPath $paymentBaseFile
if ($paymentBase -match 'openapi-sandbox\.dl\.alipaydev\.com' `
    -or $paymentBase -match '\$\{ALIPAY_(?:APP_ID|MERCHANT_PRIVATE_KEY|PUBLIC_KEY):[^}]+\}' `
    -or $paymentBase -match '\$\{ALIPAY_RETURN_URL:http://localhost') {
    Write-Host "FAIL java-payment base profile: Alipay config must not contain hardcoded sandbox credentials or localhost return-url fallback"
    exit 1
}

Write-Host "PASS java-payment base Alipay config contains no hardcoded sandbox credentials"

if ($paymentProd -notmatch "(?ms)^omni:\s*\r?\n\s+payment:\s*\r?\n\s+mock:\s*\r?\n\s+enabled:\s*false\s*$") {
    Write-Host "FAIL java-payment prod-split: omni.payment.mock.enabled must be false"
    exit 1
}

$paymentControllerFile = Join-Path -Path $repoRoot -ChildPath "java/java-payment/src/main/java/com/omni/payment/controller/PaymentController.java"
if (-not (Test-Path -LiteralPath $paymentControllerFile)) {
    Write-Host "FAIL missing payment controller: $paymentControllerFile"
    exit 1
}

$paymentController = Get-Content -Raw -LiteralPath $paymentControllerFile
if (-not $paymentController.Contains('omni.payment.mock.enabled:false') -or -not $paymentController.Contains('mockPaymentEnabled')) {
    Write-Host "FAIL java-payment mock pay endpoint must be gated by omni.payment.mock.enabled"
    exit 1
}

Write-Host "PASS java-payment mock pay endpoint is disabled by default"

$localComposeFile = Join-Path -Path $repoRoot -ChildPath "docker-compose.yml"
if (-not (Test-Path -LiteralPath $localComposeFile)) {
    Write-Host "FAIL missing local docker compose: $localComposeFile"
    exit 1
}

$localCompose = Get-Content -Raw -LiteralPath $localComposeFile
if ($localCompose -notmatch "(?m)^x-omni-compose-scope:\s*local-development-only\s*$") {
    Write-Host "FAIL docker-compose.yml must declare x-omni-compose-scope: local-development-only"
    exit 1
}
Write-Host "PASS docker-compose.yml declares local-development-only scope"

if ($localCompose -notmatch "(?m)^\s+ORDER_SERVICE_URL:\s*http://(host\.docker\.internal|java-order):8083\s*$") {
    Write-Host "FAIL docker-compose.yml grab-service ORDER_SERVICE_URL must point directly to local java-order :8083, not Gateway :8088"
    exit 1
}

if ($localCompose -notmatch "(?m)^\s+TICKET_SERVICE_URL:\s*http://(host\.docker\.internal|java-ticket):8082\s*$") {
    Write-Host "FAIL docker-compose.yml grab-service TICKET_SERVICE_URL must point directly to local java-ticket :8082, not Gateway :8088"
    exit 1
}

Write-Host "PASS docker-compose.yml grab-service order/ticket internal URLs bypass local Gateway"

if ($localCompose -notmatch "(?m)^\s+elasticsearch:\s*$" -or $localCompose -notmatch "container_name:\s*omni-elasticsearch") {
    Write-Host "FAIL docker-compose.yml must define local Elasticsearch service"
    exit 1
}

if ($localCompose -notmatch "_cluster/health\?wait_for_status=yellow" -or $localCompose -notmatch "omni-elasticsearch-data:/usr/share/elasticsearch/data") {
    Write-Host "FAIL docker-compose.yml Elasticsearch must have healthcheck and persistent data volume"
    exit 1
}

Write-Host "PASS docker-compose.yml defines Elasticsearch healthcheck and data volume"

$startInfraFile = Join-Path -Path $repoRoot -ChildPath "scripts/start-infra.ps1"
if (-not (Test-Path -LiteralPath $startInfraFile)) {
    Write-Host "FAIL missing infra startup script: $startInfraFile"
    exit 1
}

$startInfra = Get-Content -Raw -LiteralPath $startInfraFile
if ($startInfra -notmatch '"rabbitmq"' -or $startInfra -notmatch 'Wait-Port -Name "RabbitMQ"' -or $startInfra -notmatch 'Wait-ElasticsearchHealthy') {
    Write-Host "FAIL scripts/start-infra.ps1 must start/wait RabbitMQ and require Elasticsearch health"
    exit 1
}

Write-Host "PASS scripts/start-infra.ps1 starts RabbitMQ and requires Elasticsearch health"

$startProjectFile = Join-Path -Path $repoRoot -ChildPath "start-project.ps1"
if (-not (Test-Path -LiteralPath $startProjectFile)) {
    Write-Host "FAIL missing startup script: $startProjectFile"
    exit 1
}

$startProject = Get-Content -Raw -LiteralPath $startProjectFile
if ($startProject -notmatch "\`$env:ORDER_SERVICE_URL='http://localhost:8083'") {
    Write-Host "FAIL start-project.ps1 grab-service ORDER_SERVICE_URL must point directly to local java-order :8083, not Gateway :8088"
    exit 1
}

if ($startProject -notmatch "\`$env:TICKET_SERVICE_URL='http://localhost:8082'") {
    Write-Host "FAIL start-project.ps1 grab-service TICKET_SERVICE_URL must point directly to local java-ticket :8082, not Gateway :8088"
    exit 1
}

Write-Host "PASS start-project.ps1 grab-service order/ticket internal URLs bypass local Gateway"

if ($startProject -match 'OMNI_SEARCH_PROVIDER\s*=\s*"db"' -or $startProject -match 'OMNI_SEARCH_REQUIRE_ES\s*=\s*"false"') {
    Write-Host "FAIL start-project.ps1 must not configure DB search fallback"
    exit 1
}

if ($startProject -notmatch 'OMNI_SEARCH_PROVIDER\s*=\s*"elasticsearch"' -or $startProject -notmatch 'OMNI_SEARCH_REQUIRE_ES\s*=\s*"true"' -or $startProject -notmatch 'Wait-ElasticsearchHealthy') {
    Write-Host "FAIL start-project.ps1 must fix search to Elasticsearch and wait for ES health"
    exit 1
}

Write-Host "PASS start-project.ps1 requires Elasticsearch search before Java startup"

$productionComposeFile = Join-Path -Path $repoRoot -ChildPath "docker-compose.production.example.yml"
if (-not (Test-Path -LiteralPath $productionComposeFile)) {
    Write-Host "FAIL missing production compose example: $productionComposeFile"
    exit 1
}

$productionCompose = Get-Content -Raw -LiteralPath $productionComposeFile
$requiredComposeSecrets = @(
    "JWT_SECRET",
    "INTERNAL_API_TOKEN",
    "GRAB_DB_PASSWORD",
    "RABBITMQ_PASSWORD",
    "ELASTICSEARCH_PASSWORD"
)

foreach ($secretName in $requiredComposeSecrets) {
    $requiredMarker = [char]36 + [char]123 + $secretName + ':?'
    if ($productionCompose -notmatch [regex]::Escape($requiredMarker)) {
        Write-Host "FAIL production compose must require $secretName without fallback"
        exit 1
    }
}

if ($productionCompose -match '\$\{(?:JWT_SECRET|INTERNAL_API_TOKEN|GRAB_DB_PASSWORD|RABBITMQ_PASSWORD|ELASTICSEARCH_PASSWORD):-') {
    Write-Host "FAIL production compose must not use fallback defaults for sensitive secrets"
    exit 1
}

if ($productionCompose -match 'omni-local-internal-token|omni-local-jwt-secret|123456') {
    Write-Host "FAIL production compose must not contain local demo secret values"
    exit 1
}

Write-Host "PASS production compose sensitive values require explicit environment"

if ($productionCompose -notmatch "(?m)^\s+elasticsearch:\s*$" -or $productionCompose -notmatch "container_name:\s*omni-elasticsearch") {
    Write-Host "FAIL production compose must define Elasticsearch service"
    exit 1
}

foreach ($envName in @("ELASTICSEARCH_IMAGE_TAG", "ELASTICSEARCH_SECURITY_ENABLED", "ELASTICSEARCH_PASSWORD", "ELASTICSEARCH_JAVA_OPTS")) {
    $requiredMarker = [char]36 + [char]123 + $envName + ':?'
    if ($productionCompose -notmatch [regex]::Escape($requiredMarker)) {
        Write-Host "FAIL production compose Elasticsearch must require $envName without fallback"
        exit 1
    }
}

if ($productionCompose -notmatch "_cluster/health\?wait_for_status=yellow" -or $productionCompose -notmatch "omni-elasticsearch-data:/usr/share/elasticsearch/data") {
    Write-Host "FAIL production compose Elasticsearch must have healthcheck and persistent data volume"
    exit 1
}

Write-Host "PASS production compose defines required Elasticsearch healthcheck and data volume"

$grabServiceSourceRoot = Join-Path -Path $repoRoot -ChildPath "nestjs/grab-service/src"
if (-not (Test-Path -LiteralPath $grabServiceSourceRoot)) {
    Write-Host "FAIL missing grab-service source root: $grabServiceSourceRoot"
    exit 1
}

$forbiddenGrabServiceRuntimeFallbacks = @(
    "GRAB_SERVICE_HOST || '127.0.0.1'",
    "ORDER_SERVICE_URL || 'http://localhost:8088'",
    "TICKET_SERVICE_URL || process.env.ORDER_SERVICE_URL || 'http://localhost:8088'",
    "NOTIFICATION_SERVICE_URL || process.env.ORDER_SERVICE_URL || 'http://localhost:8088'",
    "NOTIFICATION_SERVICE_URL || process.env.API_GATEWAY_URL || 'http://localhost:8088'",
    "GRAB_DB_HOST || 'localhost'",
    "GRAB_DB_PORT || 5432",
    "GRAB_DB_NAME || 'omni_grab'",
    "GRAB_DB_USER || 'postgres'",
    "GRAB_DB_PASSWORD || '123456'",
    "REDIS_HOST || 'localhost'",
    "REDIS_PORT || '6379'",
    "RABBITMQ_HOST || 'localhost'",
    "RABBITMQ_PORT || 5672",
    "RABBITMQ_USER || 'admin'",
    "RABBITMQ_PASSWORD || '123456'"
)

$grabServiceSourceFiles = Get-ChildItem -LiteralPath $grabServiceSourceRoot -Recurse -Filter "*.ts" |
    Where-Object { $_.FullName -notmatch '\.spec\.ts$' }
foreach ($sourceFile in $grabServiceSourceFiles) {
    $source = Get-Content -Raw -LiteralPath $sourceFile.FullName
    foreach ($fallback in $forbiddenGrabServiceRuntimeFallbacks) {
        if ($source.Contains($fallback)) {
            $relativePath = $sourceFile.FullName.Substring($repoRoot.Length + 1)
            Write-Host "FAIL grab-service runtime source must not contain local fallback '$fallback': $relativePath"
            exit 1
        }
    }
}

Write-Host "PASS grab-service runtime source contains no local service or infrastructure fallbacks"

if ($productionCompose -notmatch "(?m)^\s+GRAB_SERVICE_HOST:\s*0\.0\.0\.0\s*$") {
    Write-Host "FAIL production compose grab-service must listen on 0.0.0.0 inside container"
    exit 1
}

$requiredGrabServiceComposeEnv = @(
    "ORDER_SERVICE_URL",
    "TICKET_SERVICE_URL",
    "NOTIFICATION_SERVICE_URL",
    "REDIS_HOST",
    "REDIS_PORT",
    "RABBITMQ_HOST",
    "RABBITMQ_PORT"
)

foreach ($envName in $requiredGrabServiceComposeEnv) {
    $requiredMarker = [char]36 + [char]123 + $envName + ':?'
    if ($productionCompose -notmatch [regex]::Escape($requiredMarker)) {
        Write-Host "FAIL production compose grab-service must require $envName without fallback"
        exit 1
    }
}

Write-Host "PASS production compose grab-service service URLs and infrastructure addresses require explicit environment"

$frontendServerProxyFile = Join-Path -Path $repoRoot -ChildPath "frontend/src/lib/server-proxy.ts"
if (-not (Test-Path -LiteralPath $frontendServerProxyFile)) {
    Write-Host "FAIL missing frontend server proxy: $frontendServerProxyFile"
    exit 1
}

$frontendServerProxy = Get-Content -Raw -LiteralPath $frontendServerProxyFile
if ($frontendServerProxy -match 'DEFAULT_PROXY_TARGET|http://localhost:8088|127\.0\.0\.1:8088') {
    Write-Host "FAIL frontend server proxy must not contain local API_PROXY_TARGET fallback"
    exit 1
}

Write-Host "PASS frontend server proxy requires explicit API_PROXY_TARGET"

$frontendHomePageFile = Join-Path -Path $repoRoot -ChildPath "frontend/src/app/page.tsx"
if (-not (Test-Path -LiteralPath $frontendHomePageFile)) {
    Write-Host "FAIL missing frontend homepage: $frontendHomePageFile"
    exit 1
}

$frontendHomePage = Get-Content -Raw -LiteralPath $frontendHomePageFile
if ($frontendHomePage -match '@/lib/mock-data|mockCategories|mockSections|降级到 mock 数据') {
    Write-Host "FAIL frontend homepage must not fall back to mock categories or sections"
    exit 1
}

$frontendFooterFile = Join-Path -Path $repoRoot -ChildPath "frontend/src/components/Footer.tsx"
if (-not (Test-Path -LiteralPath $frontendFooterFile)) {
    Write-Host "FAIL missing frontend footer: $frontendFooterFile"
    exit 1
}

$frontendFooter = Get-Content -Raw -LiteralPath $frontendFooterFile
if ($frontendFooter -match '@/lib/mock-data') {
    Write-Host "FAIL frontend footer must not import mock-data into the homepage component tree"
    exit 1
}

Write-Host "PASS frontend homepage does not use mock categories or sections fallback"

$productionEnvDocFile = Join-Path -Path $repoRoot -ChildPath "docs/production-readiness/production-env-vars.md"
if (-not (Test-Path -LiteralPath $productionEnvDocFile)) {
    Write-Host "FAIL missing production environment variable checklist: $productionEnvDocFile"
    exit 1
}

$productionEnvDoc = Get-Content -Raw -LiteralPath $productionEnvDocFile
$requiredDocumentedEnvVars = @(
    "SPRING_PROFILES_ACTIVE",
    "SPRING_DATASOURCE_URL",
    "SPRING_DATASOURCE_USERNAME",
    "SPRING_DATASOURCE_PASSWORD",
    "INTERNAL_API_TOKEN",
    "JWT_SECRET",
    "GRAB_SERVICE_URL",
    "ALIPAY_GATEWAY_URL",
    "ALIPAY_APP_ID",
    "ALIPAY_MERCHANT_PRIVATE_KEY",
    "ALIPAY_PUBLIC_KEY",
    "ALIPAY_RETURN_URL",
    "ALIPAY_NOTIFY_URL",
    "SEATA_ENABLED",
    "NACOS_HOST",
    "NACOS_PORT",
    "GATEWAY_GRAB_SERVICE_URI",
    "GATEWAY_WAITLIST_SERVICE_URI",
    "ELASTICSEARCH_URIS",
    "SPRING_ELASTICSEARCH_URIS",
    "ELASTICSEARCH_USERNAME",
    "ELASTICSEARCH_PASSWORD",
    "ELASTICSEARCH_IMAGE_TAG",
    "ELASTICSEARCH_SECURITY_ENABLED",
    "ELASTICSEARCH_JAVA_OPTS",
    "OMNI_SEARCH_PROVIDER",
    "OMNI_SEARCH_REQUIRE_ES",
    "RABBITMQ_HOST",
    "RABBITMQ_PORT",
    "RABBITMQ_USER",
    "RABBITMQ_PASSWORD",
    "GRAB_DB_PASSWORD",
    "GRAB_SERVICE_HOST",
    "ORDER_SERVICE_URL",
    "TICKET_SERVICE_URL",
    "NOTIFICATION_SERVICE_URL",
    "REDIS_HOST",
    "REDIS_PORT",
    "API_PROXY_TARGET",
    "NEXT_PUBLIC_API_URL",
    "NEXT_PUBLIC_SENTRY_ENABLED",
    "NEXT_PUBLIC_POSTHOG_ENABLED"
)

foreach ($envName in $requiredDocumentedEnvVars) {
    $docMarker = [char]96 + $envName + [char]96
    if (-not $productionEnvDoc.Contains($docMarker)) {
        Write-Host "FAIL production environment variable checklist must document $envName"
        exit 1
    }
}

Write-Host "PASS production environment variable checklist documents required runtime keys"

Write-Host "PASS production runtime default guard"

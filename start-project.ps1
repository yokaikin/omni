# Omni Ticket Platform - Startup Script

param(
    [switch]$SkipJava,
    [switch]$SkipFrontend,
    [switch]$SkipInstall,
    [switch]$UseSharedDatabase,
    [switch]$UseDockerInfra,
    [switch]$UseDockerStack
)

$ErrorActionPreference = "Continue"
$projectRoot = $PSScriptRoot
if (-not $projectRoot) { $projectRoot = Get-Location }

if ($UseDockerStack) {
    try {
        & (Join-Path $projectRoot "scripts\start-docker-stack.ps1")
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        exit 0
    } catch {
        Write-Error $_
        exit 1
    }
}

$runtimeTemp = Join-Path $projectRoot "runtime\tmp"
New-Item -ItemType Directory -Force -Path $runtimeTemp | Out-Null
$env:TEMP = $runtimeTemp
$env:TMP = $runtimeTemp

function Find-NacosHome {
    param([string[]]$SearchRoots = @("C:\", "D:\"))

    # 1. 优先读取环境变量 NACOS_HOME
    $envHome = [Environment]::GetEnvironmentVariable("NACOS_HOME", "User")
    if (-not $envHome) { $envHome = [Environment]::GetEnvironmentVariable("NACOS_HOME", "Machine") }
    if ($envHome -and (Test-Path "$envHome\bin\startup.cmd")) {
        Write-Host "[Nacos] Found via NACOS_HOME: $envHome" -ForegroundColor Green
        return $envHome
    }

    # 2. 检查常见安装路径
    $commonPaths = @(
        "C:\nacos",
        "D:\nacos",
        "D:\Development-Environment\nacos",
        "C:\Program Files\nacos",
        "$env:USERPROFILE\nacos",
        "$env:USERPROFILE\Downloads\nacos"
    )
    foreach ($p in $commonPaths) {
        if (Test-Path "$p\bin\startup.cmd") {
            Write-Host "[Nacos] Found at: $p" -ForegroundColor Green
            return $p
        }
    }

    # 3. 自动扫描磁盘根目录及一级子目录（深度可控）
    foreach ($root in $SearchRoots) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem -Path $root -Directory -Depth 0 -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq "nacos" } |
            Select-Object -First 1
        if ($found) {
            $p = $found.FullName
            if (Test-Path "$p\bin\startup.cmd") {
                Write-Host "[Nacos] Found at: $p" -ForegroundColor Green
                return $p
            }
        }
    }

    # 4. 深层扫描（最多两级子目录，覆盖常见嵌套场景）
    foreach ($root in $SearchRoots) {
        if (-not (Test-Path $root)) { continue }
        $found = Get-ChildItem -Path $root -Directory -Depth 2 -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq "nacos" } |
            Select-Object -First 1
        if ($found) {
            $p = $found.FullName
            if (Test-Path "$p\bin\startup.cmd") {
                Write-Host "[Nacos] Found at: $p" -ForegroundColor Green
                return $p
            }
        }
    }

    return $null
}

$nacosHome = Find-NacosHome

function Write-Step {
    param([string]$Message)
    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host " $Message" -ForegroundColor Cyan
    Write-Host "========================================`n" -ForegroundColor Cyan
}

function Quote-PowerShellString {
    param([string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Start-Service-InBackground {
    param([string]$Name, [string]$Command, [string]$WorkDir)
    $proc = Start-Process powershell -ArgumentList "-NoExit", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", $Command -WorkingDirectory $WorkDir -PassThru
    Write-Host "[$Name] Started (PID: $($proc.Id))" -ForegroundColor Green
    return $proc
}

function Get-PrimaryElasticsearchUri {
    $uri = $env:ELASTICSEARCH_URIS
    if ([string]::IsNullOrWhiteSpace($uri)) {
        $uri = $env:SPRING_ELASTICSEARCH_URIS
    }
    if ([string]::IsNullOrWhiteSpace($uri)) {
        return "http://localhost:9200"
    }
    return ($uri -split ",")[0].Trim().TrimEnd("/")
}

function Get-ElasticsearchHeaders {
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($env:ELASTICSEARCH_USERNAME)) {
        $pair = "$($env:ELASTICSEARCH_USERNAME):$($env:ELASTICSEARCH_PASSWORD)"
        $bytes = [System.Text.Encoding]::ASCII.GetBytes($pair)
        $headers["Authorization"] = "Basic " + [Convert]::ToBase64String($bytes)
    }
    return $headers
}

function Wait-ElasticsearchHealthy {
    param([int]$TimeoutSeconds = 120)
    $uri = Get-PrimaryElasticsearchUri
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Uri "$uri/_cluster/health?wait_for_status=yellow&timeout=1s" -Headers (Get-ElasticsearchHeaders) -TimeoutSec 3
            if ($health.status -in @("green", "yellow")) {
                Write-Host "[Elasticsearch] $uri 已就绪，集群状态 $($health.status)" -ForegroundColor Green
                return
            }
        } catch {
            Start-Sleep -Seconds 2
            continue
        }
        Start-Sleep -Seconds 2
    }
    throw "等待 Elasticsearch 超时：$uri 未达到 green/yellow 健康状态"
}

function Import-DotEnv {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) {
            return
        }
        if ($line -notmatch "^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$") {
            return
        }

        $name = $Matches[1]
        $value = $Matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, "Process"))) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

Write-Host "`n  Omni Ticket Platform - Startup`n" -ForegroundColor Magenta
Import-DotEnv -Path (Join-Path $projectRoot ".env.local")
Import-DotEnv -Path (Join-Path $projectRoot ".env")

$javaUnixDomainTempPath = Join-Path $projectRoot "runtime\java-tmp"
try {
    if (-not (Test-Path -LiteralPath $javaUnixDomainTempPath)) {
        New-Item -ItemType Directory -Path $javaUnixDomainTempPath -Force | Out-Null
    }
    [Environment]::SetEnvironmentVariable("TEMP", $javaUnixDomainTempPath, "Process")
    [Environment]::SetEnvironmentVariable("TMP", $javaUnixDomainTempPath, "Process")
    $env:TEMP = $javaUnixDomainTempPath
    $env:TMP = $javaUnixDomainTempPath
    Write-Host "[本地临时目录] $javaUnixDomainTempPath" -ForegroundColor Green
} catch {
    Write-Host "[本地临时目录] 配置失败，Java selector 可能无法启动: $($_.Exception.Message)" -ForegroundColor Yellow
}

# 1. Check Environment
Write-Step "Checking Environment..."

try {
    $javaVersion = java -version 2>&1 | Select-Object -First 1
    Write-Host "[Java] $javaVersion" -ForegroundColor Green
} catch {
    Write-Host "[Java] Not Found" -ForegroundColor Red
}

try {
    $mvnVersion = mvn -version 2>&1 | Select-Object -First 1
    Write-Host "[Maven] OK" -ForegroundColor Green
} catch {
    Write-Host "[Maven] Not Found" -ForegroundColor Red
}

try {
    $nodeVersion = node -v
    Write-Host "[Node.js] v$nodeVersion" -ForegroundColor Green
} catch {
    Write-Host "[Node.js] Not Found" -ForegroundColor Red
}

$npmCmd = "npm"
try {
    $pnpmVersion = pnpm -v
    $npmCmd = "pnpm"
    Write-Host "[pnpm] v$pnpmVersion" -ForegroundColor Green
} catch {
    Write-Host "[npm] using npm instead of pnpm" -ForegroundColor Yellow
}

$defaultJwtSecret = "omni-local-jwt-secret-must-be-at-least-32-bytes"
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    $env:JWT_SECRET = $defaultJwtSecret
}
if ([string]::IsNullOrWhiteSpace($env:INTERNAL_API_TOKEN)) {
    $env:INTERNAL_API_TOKEN = "omni-local-internal-token"
}
if ([string]::IsNullOrWhiteSpace($env:OMNI_ID_NO_KEY)) {
    $env:OMNI_ID_NO_KEY = "omni-local-dev-id-no-key-change-me"
}
$localRuntimeDefaults = @{
    RABBITMQ_HOST = "localhost"
    RABBITMQ_PORT = "5672"
    RABBITMQ_USER = "admin"
    RABBITMQ_PASSWORD = "123456"
    GRAB_SERVICE_URL = "http://localhost:3001"
    SEATA_ENABLED = "true"
    OMNI_SEARCH_PROVIDER = "elasticsearch"
    OMNI_SEARCH_REQUIRE_ES = "true"
    ELASTICSEARCH_URIS = "http://localhost:9200"
    OMNI_SUPPORT_AI_CONTEXT_WINDOW = "2048"
    ALIPAY_GATEWAY_URL = "http://localhost:8084/local-alipay-disabled"
    ALIPAY_APP_ID = "omni-local-placeholder"
    ALIPAY_MERCHANT_PRIVATE_KEY = "omni-local-placeholder"
    ALIPAY_PUBLIC_KEY = "omni-local-placeholder"
    ALIPAY_RETURN_URL = "http://localhost:3000/payment/result"
    ALIPAY_NOTIFY_URL = "http://localhost:8088/api/payment/alipay/notify"
    API_PROXY_TARGET = "http://localhost:8088"
}
foreach ($entry in $localRuntimeDefaults.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($entry.Key, "Process"))) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
}
if ([string]::IsNullOrWhiteSpace($env:SPRING_ELASTICSEARCH_URIS)) {
    $env:SPRING_ELASTICSEARCH_URIS = $env:ELASTICSEARCH_URIS
}
if ([string]::IsNullOrWhiteSpace($env:ELASTICSEARCH_URIS)) {
    $env:ELASTICSEARCH_URIS = $env:SPRING_ELASTICSEARCH_URIS
}

if ($UseDockerInfra) {
    Write-Step "启动 Docker 中间件..."
    & (Join-Path $projectRoot "scripts\start-infra.ps1")
}

# 2. Check PostgreSQL
if ($UseDockerInfra) {
    Write-Step "使用本机 PostgreSQL..."
    Write-Host "[PostgreSQL] Docker 中间件模式下仍使用本机数据库" -ForegroundColor Green
} else {
    Write-Step "Starting PostgreSQL..."
    $pgSvc = Get-Service -Name "postgresql-x64-17" -ErrorAction SilentlyContinue
    if ($pgSvc -and $pgSvc.Status -eq "Running") {
        Write-Host "[PostgreSQL] Running" -ForegroundColor Green
    } else {
        Start-Service -Name "postgresql-x64-17" -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 2
        Write-Host "[PostgreSQL] Started" -ForegroundColor Green
    }
}

# 3. Start Nacos
$nacosPort = 8848
if ($UseDockerInfra) {
    Write-Step "Using Docker Nacos..."
    Write-Host "[Nacos] Docker infrastructure selected" -ForegroundColor Green
} else {
    Write-Step "Starting Nacos..."
    try {
        $nacosCheck = Invoke-WebRequest -Uri "http://localhost:$nacosPort/nacos" -TimeoutSec 2 -UseBasicParsing -ErrorAction SilentlyContinue
        Write-Host "[Nacos] Already Running" -ForegroundColor Green
    } catch {
        Write-Host "[Nacos] Starting..." -ForegroundColor Yellow
        if (-not $nacosHome) {
            Write-Host "[Nacos] NOT FOUND! Please install Nacos to C:\nacos or D:\nacos, or start it manually." -ForegroundColor Red
        } else {
            Start-Process cmd -ArgumentList "/c", "$nacosHome\bin\startup.cmd -m standalone" -WorkingDirectory "$nacosHome\bin" -PassThru | Out-Null
            Start-Sleep -Seconds 15
            Write-Host "[Nacos] Started" -ForegroundColor Green
        }
    }
}

# 4. Check Elasticsearch
if (-not $SkipJava) {
    Write-Step "Checking Elasticsearch..."
    Wait-ElasticsearchHealthy -TimeoutSeconds 120
}

# 4. Install Java Dependencies
if (-not $SkipInstall -and -not $SkipJava) {
    Write-Step "Installing Java Dependencies..."

    $javaParentPom = Join-Path $projectRoot "java\pom.xml"
    Write-Host "正在安装 java-common 和 java-ai-core..." -ForegroundColor Cyan
    & mvn -f $javaParentPom -pl "java-common,java-ai-core" -am install "-DskipTests"
    if ($LASTEXITCODE -ne 0) {
        throw "Java 共享模块安装失败，请检查 Maven 输出后重试。"
    }
    Write-Host "Java 共享模块安装完成。" -ForegroundColor Green
}

# 5. Install Frontend Dependencies
if (-not $SkipInstall -and -not $SkipFrontend) {
    Write-Step "Installing Frontend Dependencies..."

    $frontendPath = Join-Path $projectRoot "frontend"
    if (-not (Test-Path (Join-Path $frontendPath "node_modules"))) {
        Write-Host "Running $($npmCmd) install..." -ForegroundColor Cyan
        Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd $frontendPath; $npmCmd install" -WorkingDirectory $frontendPath -PassThru | Out-Null
        Start-Sleep -Seconds 10
        Write-Host "[Info] Frontend dependencies installing in background..." -ForegroundColor Yellow
    } else {
        Write-Host "[Frontend] node_modules already exists" -ForegroundColor Green
    }

    $grabPath = Join-Path $projectRoot "nestjs\grab-service"
    if (-not (Test-Path (Join-Path $grabPath "node_modules"))) {
        Write-Host "Running npm install for grab-service..." -ForegroundColor Cyan
        Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd $grabPath; npm install" -WorkingDirectory $grabPath -PassThru | Out-Null
        Start-Sleep -Seconds 10
        Write-Host "[Info] grab-service dependencies installing in background..." -ForegroundColor Yellow
    } else {
        Write-Host "[grab-service] node_modules already exists" -ForegroundColor Green
    }
}

# 6. Start Java Services
if (-not $SkipJava) {
    Write-Step "Starting Java Services..."

    if ($UseSharedDatabase) {
        Write-Host "[Warning] Starting business services with the legacy shared omni_ticket database." -ForegroundColor Yellow
    } else {
        Write-Host "[Info] Starting business services with prod-split local databases." -ForegroundColor Cyan
    }

    $javaServices = @(
        @{Name="java-gateway"; Port=8088; Path="java\java-gateway"; Database=$null},
        @{Name="java-user"; Port=8081; Path="java\java-user"; Database="omni_user"},
        @{Name="java-ticket"; Port=8082; Path="java\java-ticket"; Database="omni_ticket_split"},
        @{Name="java-order"; Port=8083; Path="java\java-order"; Database="omni_order"},
        @{Name="java-payment"; Port=8084; Path="java\java-payment"; Database="omni_payment"},
        @{Name="java-notification"; Port=8085; Path="java\java-notification"; Database="omni_notification"}
    )

    $uploadRoot = Join-Path $projectRoot "runtime\uploads"
    $internalApiToken = $env:INTERNAL_API_TOKEN
    $jwtSecret = $env:JWT_SECRET

    if (-not $UseSharedDatabase) {
        Write-Step "发布 Seata 配置..."
        & (Join-Path $projectRoot "scripts\update-seata-nacos-config.ps1") -NacosAddr "127.0.0.1:$nacosPort"
    }

    foreach ($svc in $javaServices) {
        $fullPath = Join-Path $projectRoot $svc.Path
        Write-Host "Starting $($svc.Name) on port $($svc.Port)..." -ForegroundColor Cyan
        $smsMockArg = if ($svc.Name -eq "java-user") { " --omni.sms.mock.enabled=true" } else { "" }
        $grabServiceUrlArg = if ($svc.Name -eq "java-user") { " --omni.grab-service.url=http://localhost:3001" } else { "" }
        $searchArg = if ($svc.Name -eq "java-ticket") { " --spring.elasticsearch.uris=$env:SPRING_ELASTICSEARCH_URIS --omni.search.provider=elasticsearch --omni.search.require-elasticsearch=true" } else { "" }
        if ($svc.Name -eq "java-ticket" -and -not [string]::IsNullOrWhiteSpace($env:ELASTICSEARCH_USERNAME)) {
            $searchArg = "$searchArg --spring.elasticsearch.username=$env:ELASTICSEARCH_USERNAME --spring.elasticsearch.password=$env:ELASTICSEARCH_PASSWORD"
        }
        $servicePathArg = Quote-PowerShellString $fullPath
        $sharedDbUrlArg = if ($UseSharedDatabase) { " --spring.datasource.url=jdbc:postgresql://localhost:5432/omni_ticket" } else { "" }
        $defaultRunArguments = "--spring.cloud.nacos.discovery.ip=127.0.0.1 --omni.upload.root=$uploadRoot$sharedDbUrlArg$smsMockArg$searchArg"
        $command = "Set-Location -LiteralPath $servicePathArg; mvn spring-boot:run $(Quote-PowerShellString "-Dspring-boot.run.arguments=$defaultRunArguments")"
        if (-not $UseSharedDatabase -and $svc.Database) {
            $seataArg = if ($svc.Name -in @("java-ticket", "java-order", "java-payment")) { " --seata.enabled=true" } else { "" }
            $prodSplitRunArguments = "--spring.datasource.url=jdbc:postgresql://localhost:5432/$($svc.Database) --spring.datasource.username=postgres --spring.datasource.password=123456 --internal.api.token=$internalApiToken --jwt.secret=$jwtSecret --NACOS_HOST=localhost --NACOS_PORT=$nacosPort --spring.cloud.nacos.discovery.ip=127.0.0.1 --omni.upload.root=$uploadRoot$smsMockArg$grabServiceUrlArg$searchArg$seataArg"
            $command = "Set-Location -LiteralPath $servicePathArg; mvn spring-boot:run $(Quote-PowerShellString "-Dspring-boot.run.profiles=prod-split") $(Quote-PowerShellString "-Dspring-boot.run.arguments=$prodSplitRunArguments")"
        }
        Start-Service-InBackground -Name $svc.Name -Command $command -WorkDir $fullPath
        Start-Sleep -Seconds 5
    }
    Write-Host "`n[Info] Java services need a few minutes to start" -ForegroundColor Yellow
}

# 7. Start Grab Service
if (-not $SkipFrontend) {
    Write-Step "Starting Grab Service..."

    $grabPath = Join-Path $projectRoot "nestjs\grab-service"
    $grabPathArg = Quote-PowerShellString $grabPath
    $grabCommand = "Set-Location -LiteralPath $grabPathArg; `$env:GRAB_SERVICE_PORT='3001'; `$env:GRAB_SERVICE_HOST='127.0.0.1'; `$env:GRAB_DB_HOST='localhost'; `$env:GRAB_DB_PORT='5432'; `$env:GRAB_DB_NAME='omni_grab'; `$env:GRAB_DB_USER='postgres'; `$env:GRAB_DB_PASSWORD='123456'; `$env:REDIS_HOST='localhost'; `$env:REDIS_PORT='6379'; `$env:ORDER_SERVICE_URL='http://localhost:8083'; `$env:TICKET_SERVICE_URL='http://localhost:8082'; `$env:NOTIFICATION_SERVICE_URL='http://localhost:8088'; `$env:INTERNAL_API_TOKEN='$env:INTERNAL_API_TOKEN'; `$env:JWT_SECRET='$env:JWT_SECRET'; `$env:RABBITMQ_HOST='localhost'; `$env:RABBITMQ_PORT='5672'; `$env:RABBITMQ_USER='admin'; `$env:RABBITMQ_PASSWORD='123456'; npm run start:dev"
    Start-Service-InBackground -Name "grab-service" -Command $grabCommand -WorkDir $grabPath
    Write-Host "`n[Info] Grab service access through gateway /api/grab" -ForegroundColor Yellow
}

# 8. Start Frontend
if (-not $SkipFrontend) {
    Write-Step "Starting Frontend..."

    $frontendPath = Join-Path $projectRoot "frontend"
    $devCmd = if ($npmCmd -eq "pnpm") { "pnpm dev" } else { "npm run dev" }

    $frontendPathArg = Quote-PowerShellString $frontendPath
    Start-Service-InBackground -Name "Next.js Frontend" -Command "Set-Location -LiteralPath $frontendPathArg; $devCmd" -WorkDir $frontendPath
    Write-Host "`n[Info] Access http://localhost:3000 after startup" -ForegroundColor Yellow
}

Write-Step "Startup Complete!"

Write-Host @"

  Ports:
  - Nacos:      http://localhost:8848
  - Gateway:    http://localhost:8088
  - Grab:       http://localhost:3001
  - Frontend:   http://localhost:3000

  Test Accounts:
  - admin:      13800000001 / 123456
  - organizer: 13800000002 / 123456
  - user:       13900000001 / 123456

"@ -ForegroundColor Green

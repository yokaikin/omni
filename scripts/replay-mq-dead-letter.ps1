# MQ 死信查询/重放运维脚本
# 用法示例：
#   powershell -ExecutionPolicy Bypass -File scripts/replay-mq-dead-letter.ps1 -Service notification -Action list
#   powershell -ExecutionPolicy Bypass -File scripts/replay-mq-dead-letter.ps1 -Service notification -Action replay -Id 3
#   powershell -ExecutionPolicy Bypass -File scripts/replay-mq-dead-letter.ps1 -Service ticket -Action list -Status PENDING
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("notification", "ticket")]
    [string]$Service,

    [ValidateSet("list", "replay")]
    [string]$Action = "list",

    [long]$Id = 0,

    [string]$Status = "",

    [ValidateRange(1, 200)]
    [int]$Limit = 50,

    [string]$BaseUrl = "",

    [string]$Token = $env:INTERNAL_API_TOKEN
)

$ErrorActionPreference = "Stop"

if (-not $Token) {
    Write-Host "FAIL 缺少内部令牌：请设置 -Token 或环境变量 INTERNAL_API_TOKEN"
    exit 1
}

if (-not $BaseUrl) {
    $port = @{ notification = "8085"; ticket = "8082" }[$Service]
    $BaseUrl = "http://localhost:$port"
}

if ($Service -eq "notification") {
    $listUrl = "$BaseUrl/api/notification/internal/mq-dead-letters"
    $replayUrlTemplate = "$BaseUrl/api/notification/internal/mq-dead-letters/{0}/replay"
} else {
    $listUrl = "$BaseUrl/api/ticket/internal/search/dead-letters"
    $replayUrlTemplate = "$BaseUrl/api/ticket/internal/search/dead-letters/{0}/replay"
}

$headers = @{ "X-Internal-Token" = $Token }

if ($Action -eq "list") {
    $query = "?limit=$Limit"
    if ($Status) {
        $query += "&status=$Status"
    }
    $result = Invoke-RestMethod -Method Get -Uri ($listUrl + $query) -Headers $headers
    if ($result.code -ne 200) {
        Write-Host "FAIL 查询死信失败: code=$($result.code) message=$($result.message)"
        exit 1
    }
    $items = @($result.data)
    Write-Host "共 $($items.Count) 条死信记录:"
    foreach ($item in $items) {
        Write-Host ("  id={0} status={1} sourceQueue={2} replayCount={3} createdTime={4} payload={5}" -f `
            $item.id, $item.status, $item.sourceQueue, $item.replayCount, $item.createdTime, `
            ($item.payload.Length -gt 120 ? $item.payload.Substring(0, 120) + "..." : $item.payload))
    }
    exit 0
}

if ($Id -le 0) {
    Write-Host "FAIL 重放需要 -Id（死信记录 ID），可先用 -Action list 查询"
    exit 1
}

$replayUrl = $replayUrlTemplate -f $Id
$replayResult = Invoke-RestMethod -Method Post -Uri $replayUrl -Headers $headers
if ($replayResult.code -ne 200) {
    Write-Host "FAIL 重放死信失败: code=$($replayResult.code) message=$($replayResult.message)"
    exit 1
}
Write-Host "死信 $Id 已重放: status=$($replayResult.data.status) replayCount=$($replayResult.data.replayCount)"

param(
    [string]$NacosAddr = $(if ($env:SEATA_NACOS_ADDR) { $env:SEATA_NACOS_ADDR } else { "127.0.0.1:8848" }),
    [string]$ConfigFile = "",
    [string]$Group = $(if ($env:SEATA_CONFIG_GROUP) { $env:SEATA_CONFIG_GROUP } else { "SEATA_GROUP" }),
    [string]$DataId = $(if ($env:SEATA_CONFIG_DATA_ID) { $env:SEATA_CONFIG_DATA_ID } else { "seataServer.properties" }),
    [string]$AdvertiseHost = $env:SEATA_ADVERTISE_HOST,
    [int]$AdvertisePort = $(if ($env:SEATA_ADVERTISE_PORT) { [int]$env:SEATA_ADVERTISE_PORT } else { 8091 }),
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ConfigFile)) {
    $ConfigFile = Join-Path $projectRoot "docker\seata\seataServer.properties"
} elseif (-not [System.IO.Path]::IsPathRooted($ConfigFile)) {
    $ConfigFile = Join-Path $projectRoot $ConfigFile
}

function Test-AdvertiseHost {
    param([string]$HostValue)
    if ([string]::IsNullOrWhiteSpace($HostValue)) { return $false }
    if ($HostValue -in @("localhost", "0.0.0.0", "::1")) { return $false }
    if ($HostValue -like "127.*") { return $false }
    if ($HostValue -like "169.254.*") { return $false }
    return $HostValue -match "^\d{1,3}(\.\d{1,3}){3}$"
}

function Test-TcpPort {
    param([string]$HostValue, [int]$PortValue)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostValue, $PortValue, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne(1200, $false)) {
            return $false
        }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Add-CandidateHost {
    param(
        [System.Collections.Generic.List[string]]$Candidates,
        [string]$HostValue
    )
    if ((Test-AdvertiseHost $HostValue) -and -not $Candidates.Contains($HostValue)) {
        $Candidates.Add($HostValue)
    }
}

function Add-NetworkCandidateHosts {
    param([System.Collections.Generic.List[string]]$Candidates)

    $routes = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
        Where-Object { $_.NextHop -and $_.NextHop -ne "0.0.0.0" } |
        Sort-Object RouteMetric, InterfaceMetric

    foreach ($route in $routes) {
        Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $route.InterfaceIndex -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress } |
            ForEach-Object { Add-CandidateHost -Candidates $Candidates -HostValue $_.IPAddress }
    }

    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress } |
        ForEach-Object { Add-CandidateHost -Candidates $Candidates -HostValue $_.IPAddress }
}

function Get-SeataAdvertiseHost {
    param([string]$PreferredHost, [int]$PortValue)

    if (Test-AdvertiseHost $PreferredHost) {
        if (Test-TcpPort -HostValue $PreferredHost -PortValue $PortValue) {
            return $PreferredHost
        }
        Write-Host "[Seata] 当前 SEATA_ADVERTISE_HOST=$PreferredHost 不可达，自动重新探测。" -ForegroundColor Yellow
    } elseif (-not [string]::IsNullOrWhiteSpace($PreferredHost)) {
        Write-Host "[Seata] 当前 SEATA_ADVERTISE_HOST=$PreferredHost 不是可用的非回环 IPv4，自动重新探测。" -ForegroundColor Yellow
    }

    $candidates = New-Object System.Collections.Generic.List[string]
    Add-NetworkCandidateHosts -Candidates $candidates

    foreach ($candidate in $candidates) {
        if (Test-TcpPort -HostValue $candidate -PortValue $PortValue) {
            return $candidate
        }
    }

    if ($candidates.Count -gt 0) {
        throw "未找到可连接 Seata 端口 $PortValue 的本机地址。候选地址：$($candidates -join ', ')。请先启动 Seata Server。"
    }
    throw "未找到可用于 Seata 注册的本机地址。请检查当前网络连接。"
}

function Wait-Nacos {
    param([string]$Address, [int]$Timeout)
    $deadline = (Get-Date).AddSeconds($Timeout)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -Uri "http://$Address/nacos/" -TimeoutSec 3 -UseBasicParsing | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "Nacos 在 $Address 未就绪，无法发布 Seata 配置。"
}

function Publish-NacosConfig {
    param([string]$Address, [string]$ConfigGroup, [string]$ConfigDataId, [string]$Content)
    Invoke-WebRequest -Method Post -Uri "http://$Address/nacos/v1/cs/configs" -UseBasicParsing -Body @{
        dataId = $ConfigDataId
        group = $ConfigGroup
        type = "properties"
        content = $Content
    } | Out-Null
}

if (-not (Test-Path -LiteralPath $ConfigFile)) {
    throw "Seata 配置模板不存在：$ConfigFile"
}

$resolvedHost = Get-SeataAdvertiseHost -PreferredHost $AdvertiseHost -PortValue $AdvertisePort
$template = Get-Content -LiteralPath $ConfigFile -Raw
$content = $template.Replace('${SEATA_ADVERTISE_HOST}', $resolvedHost).Replace('${SEATA_ADVERTISE_PORT}', [string]$AdvertisePort)

Wait-Nacos -Address $NacosAddr -Timeout $TimeoutSeconds
Publish-NacosConfig -Address $NacosAddr -ConfigGroup $Group -ConfigDataId $DataId -Content $content

$count = 0
foreach ($line in ($content -split "`r?`n")) {
    $trimmed = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#") -or $trimmed.StartsWith("!") -or -not $trimmed.Contains("=")) {
        continue
    }
    $idx = $trimmed.IndexOf("=")
    $key = $trimmed.Substring(0, $idx).Trim()
    $value = $trimmed.Substring($idx + 1)
    if ([string]::IsNullOrWhiteSpace($key)) { continue }
    Publish-NacosConfig -Address $NacosAddr -ConfigGroup $Group -ConfigDataId $key -Content $value
    $count++
}

Write-Host "[Seata] Nacos 配置已更新：${resolvedHost}:$AdvertisePort，发布配置项 $count 个。" -ForegroundColor Green

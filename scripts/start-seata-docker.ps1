param(
    [int]$AdvertisePort = $(if ($env:SEATA_ADVERTISE_PORT) { [int]$env:SEATA_ADVERTISE_PORT } else { 8091 }),
    [string]$NacosAddr = $(if ($env:SEATA_NACOS_ADDR) { $env:SEATA_NACOS_ADDR } else { "127.0.0.1:8848" })
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

function Test-CommandExists {
    param([string]$Name)
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Test-AdvertiseHost {
    param([string]$HostValue)
    if ([string]::IsNullOrWhiteSpace($HostValue)) { return $false }
    if ($HostValue -in @("localhost", "0.0.0.0", "::1")) { return $false }
    if ($HostValue -like "127.*") { return $false }
    if ($HostValue -like "169.254.*") { return $false }
    return $HostValue -match "^\d{1,3}(\.\d{1,3}){3}$"
}

function Get-PrimaryIpv4 {
    $routes = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
        Where-Object { $_.NextHop -and $_.NextHop -ne "0.0.0.0" } |
        Sort-Object RouteMetric, InterfaceMetric

    foreach ($route in $routes) {
        $ip = Get-NetIPAddress -AddressFamily IPv4 -InterfaceIndex $route.InterfaceIndex -ErrorAction SilentlyContinue |
            Where-Object { $_.IPAddress -and $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
            Select-Object -First 1 -ExpandProperty IPAddress
        if ($ip) { return $ip }
    }

    return $null
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

function Wait-TcpPort {
    param([string]$HostValue, [int]$PortValue, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort -HostValue $HostValue -PortValue $PortValue) {
            return
        }
        Start-Sleep -Seconds 2
    }
    if ($HostValue -notlike "127.*" -and (Test-TcpPort -HostValue "127.0.0.1" -PortValue $PortValue)) {
        throw "Docker Desktop 当前只接受 127.0.0.1:$PortValue。请以管理员身份执行：netsh interface portproxy add v4tov4 listenaddress=$HostValue listenport=$PortValue connectaddress=127.0.0.1 connectport=$PortValue；然后重试本脚本。"
    }
    throw "Seata Server 在 ${HostValue}:$PortValue 未就绪。"
}

function Wait-Nacos {
    param([string]$Address, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -Uri "http://$Address/nacos/" -TimeoutSec 3 -UseBasicParsing | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "Nacos 在 $Address 未就绪。"
}

function Get-SeataRegistryInstances {
    param([string]$Address)
    $response = Invoke-RestMethod -Uri "http://$Address/nacos/v1/ns/instance/list?serviceName=seata-server&groupName=SEATA_GROUP&namespaceId=" -TimeoutSec 5
    return @($response.hosts)
}

function Wait-SeataRegistry {
    param(
        [string]$Address,
        [string]$ExpectedHost,
        [int]$ExpectedPort,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $instances = @(Get-SeataRegistryInstances -Address $Address)
            $expected = @($instances | Where-Object {
                $_.ip -eq $ExpectedHost -and
                [int]$_.port -eq $ExpectedPort -and
                $_.clusterName -eq "default" -and
                $_.healthy -eq $true -and
                $_.enabled -eq $true
            })
            $stale = @($instances | Where-Object {
                $_.ip -ne $ExpectedHost -or [int]$_.port -ne $ExpectedPort
            })

            if ($expected.Count -gt 0 -and $stale.Count -eq 0) {
                return $instances
            }
        } catch {
            # Nacos 注册可能在容器重建后短暂延迟。
        }
        Start-Sleep -Seconds 2
    }

    $instances = @(Get-SeataRegistryInstances -Address $Address)
    $summary = ($instances | ForEach-Object { "$($_.ip):$($_.port) healthy=$($_.healthy) enabled=$($_.enabled)" }) -join ", "
    throw "Nacos Seata 注册未收敛到 ${ExpectedHost}:$ExpectedPort。当前实例：$summary"
}

if (-not (Test-CommandExists "docker")) {
    throw "未找到 docker 命令，请先启动 Docker Desktop。"
}

if (Test-AdvertiseHost $env:SEATA_ADVERTISE_HOST) {
    $advertiseHost = $env:SEATA_ADVERTISE_HOST
} else {
    $advertiseHost = Get-PrimaryIpv4
    if (-not $advertiseHost) {
        throw "未找到可用于 Seata 注册的非回环 IPv4，请检查当前网络连接。"
    }
}

$oldHost = $env:SEATA_ADVERTISE_HOST
$oldPort = $env:SEATA_ADVERTISE_PORT
$env:SEATA_ADVERTISE_HOST = $advertiseHost
$env:SEATA_ADVERTISE_PORT = [string]$AdvertisePort

Push-Location $projectRoot
try {
    Write-Host "[Seata] 自动探测注册地址 ${advertiseHost}:$AdvertisePort。" -ForegroundColor Cyan
    docker compose up -d nacos
    if ($LASTEXITCODE -ne 0) { throw "Docker 启动 Nacos 失败。" }

    Wait-Nacos -Address $NacosAddr -TimeoutSeconds 60
    docker compose run --rm --no-deps seata-config-init
    if ($LASTEXITCODE -ne 0) { throw "Docker 发布 Seata 配置失败。" }

    docker compose up -d --force-recreate seata-server
    if ($LASTEXITCODE -ne 0) { throw "Docker 启动 Seata Server 失败。" }

    Wait-TcpPort -HostValue $advertiseHost -PortValue $AdvertisePort -TimeoutSeconds 60
    & (Join-Path $PSScriptRoot "update-seata-nacos-config.ps1") `
        -NacosAddr $NacosAddr `
        -AdvertiseHost $advertiseHost `
        -AdvertisePort $AdvertisePort
    if ($LASTEXITCODE -ne 0) { throw "发布 Seata Nacos 配置失败。" }

    $instances = Wait-SeataRegistry `
        -Address $NacosAddr `
        -ExpectedHost $advertiseHost `
        -ExpectedPort $AdvertisePort `
        -TimeoutSeconds 60

    Write-Host "[Seata] Docker: UP" -ForegroundColor Green
    Write-Host "[Seata] Nacos: UP ($NacosAddr)" -ForegroundColor Green
    Write-Host "[Seata] Registered IP: $advertiseHost" -ForegroundColor Green
    Write-Host "[Seata] Port: $AdvertisePort" -ForegroundColor Green
    Write-Host "[Seata] Health: healthy" -ForegroundColor Green
    Write-Host "[Seata] Registry: $(@($instances).Count) 个有效实例" -ForegroundColor Green
    Write-Host "[Seata] Connectivity: PASS ($advertiseHost`:$AdvertisePort)" -ForegroundColor Green
} finally {
    Pop-Location
    $env:SEATA_ADVERTISE_HOST = $oldHost
    $env:SEATA_ADVERTISE_PORT = $oldPort
}

param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "未找到 docker 命令，请先启动 Docker Desktop。"
}

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        $javaServices = @(
            "java-gateway",
            "java-user",
            "java-ticket",
            "java-order",
            "java-payment",
            "java-notification"
        )
        foreach ($service in $javaServices) {
            & docker compose build $service
            if ($LASTEXITCODE -ne 0) {
                throw "Java 服务镜像构建失败：$service"
            }
        }
    }
    & docker compose up -d
    if ($LASTEXITCODE -ne 0) {
        throw "完整 Docker 编排启动失败。"
    }
    & docker compose ps
} finally {
    Pop-Location
}

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
    $composeArgs = @("compose", "--parallel", "1", "up", "-d")
    if (-not $SkipBuild) {
        $composeArgs += "--build"
    }
    & docker @composeArgs
    if ($LASTEXITCODE -ne 0) {
        throw "完整 Docker 编排启动失败。"
    }
    & docker compose ps
} finally {
    Pop-Location
}

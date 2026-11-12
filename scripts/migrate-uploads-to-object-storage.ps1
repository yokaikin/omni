# 将本地磁盘上传文件迁移到 S3 兼容对象存储（s3proxy/MinIO 文件布局）。
# 布局约定：{object-storage-data}/{bucket}/{objectKey}
#   - 公有：runtime/uploads/{user,ticket}/... -> omni-assets/{user,ticket}/...
#   - 私有：runtime/private-uploads/ticket/... -> omni-assets/private/ticket/...
# 幂等：目标已存在的文件跳过；源目录保留不删。
param(
    [string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent),
    [string]$Bucket = "${env:OMNI_OBJECT_STORAGE_BUCKET}?omni-assets"
)
$ErrorActionPreference = 'Stop'
if ($Bucket -match '\?') { $Bucket = $Bucket.Split('?')[1] }

$storageData = Join-Path $ProjectRoot 'runtime\object-storage'
$bucketRoot = Join-Path $storageData $Bucket
$uploadsRoot = Join-Path $ProjectRoot 'runtime\uploads'
$privateRoot = Join-Path $ProjectRoot 'runtime\private-uploads'

if (-not (Test-Path $storageData)) {
    Write-Error "对象存储数据目录不存在: $storageData（请先启动 object-storage 容器）"
}

$migrations = @(
    @{ Source = Join-Path $uploadsRoot 'user';   Target = Join-Path $bucketRoot 'user' },
    @{ Source = Join-Path $uploadsRoot 'ticket'; Target = Join-Path $bucketRoot 'ticket' },
    @{ Source = $privateRoot;                    Target = Join-Path $bucketRoot 'private\ticket' }
)

$copied = 0
$skipped = 0
$missing = 0

foreach ($migration in $migrations) {
    if (-not (Test-Path $migration.Source)) {
        Write-Host "跳过（源不存在）: $($migration.Source)"
        continue
    }
    $files = Get-ChildItem -Recurse -File $migration.Source
    foreach ($file in $files) {
        $relative = $file.FullName.Substring($migration.Source.Length).TrimStart('\', '/')
        $targetPath = Join-Path $migration.Target ($relative -replace '/', '\')
        if (Test-Path $targetPath) {
            $skipped++
            continue
        }
        $targetDir = Split-Path $targetPath -Parent
        if (-not (Test-Path $targetDir)) {
            New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        }
        Copy-Item -LiteralPath $file.FullName -Destination $targetPath
        $copied++
    }
}

Write-Host ""
Write-Host "迁移完成: 新复制 $copied 个文件，跳过已存在 $skipped 个，源目录未删除。"
if ($copied -eq 0 -and $skipped -eq 0) {
    Write-Host "没有可迁移的文件。"
}

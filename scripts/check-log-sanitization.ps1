#Requires -Version 5.1
<#
.SYNOPSIS
  日志脱敏静态守卫：扫描源码中裸露的敏感字段日志、手机号字面量与 SDK 参数级日志配置。
.DESCRIPTION
  检查项：
    1. Java main 源码 log 语句中裸调 getPhone()/getIdNo()/getPassword()/getRealName()/getRecipientEmail()
       且未经过 LogMaskUtil 包装；
    2. Java main / 前端 / NestJS 源码（不含测试）中的 11 位手机号字面量；
    3. 前端 / NestJS 源码中的 console.* 敏感字段输出（token/password/idNo）；
    4. java-payment 是否压制 Alipay SDK 参数级日志（logging.level.sdk.biz.info <= warn）。
  任一检查失败则退出码 1。
#>
param(
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$failures = @()

function Add-Failure {
    param([string]$Category, [string]$Detail)
    $script:failures += [pscustomobject]@{ Category = $Category; Detail = $Detail }
}

function Test-File {
    param([string]$Label, [scriptblock]$Body)
    $before = $failures.Count
    & $Body
    if ($failures.Count -eq $before) {
        Write-Host "[PASS] $Label" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] $Label" -ForegroundColor Red
    }
}

$javaMainFiles = @()
if (Test-Path (Join-Path $ProjectRoot 'java')) {
    $javaMainFiles = Get-ChildItem -Path (Join-Path $ProjectRoot 'java') -Recurse -Filter '*.java' |
        Where-Object { $_.FullName -match '\\src\\main\\java\\' }
}

# ---------- 检查 1：Java log 语句裸敏感 getter ----------
Test-File 'Java 日志语句不裸打敏感字段' {
    $pattern = 'log\.(info|warn|error|debug|trace)\(.*get(Phone|IdNo|Password|RealName|RecipientEmail)\(\)'
    foreach ($file in $javaMainFiles) {
        $matchesFound = Select-String -LiteralPath $file.FullName -Pattern $pattern
        foreach ($m in $matchesFound) {
            if ($m.Line -notmatch 'LogMaskUtil\.') {
                Add-Failure 'java-log-sensitive-getter' ("{0}:{1}: {2}" -f $file.FullName.Substring($ProjectRoot.Length + 1), $m.LineNumber, $m.Line.Trim())
            }
        }
    }
}

# ---------- 检查 2：源码中的 11 位手机号字面量 ----------
Test-File '源码不含 11 位手机号字面量' {
    $phonePattern = '(?<![\d])1[3-9]\d{9}(?![\d])'
    $scanTargets = @()
    $scanTargets += $javaMainFiles
    foreach ($dir in @('frontend\src', 'nestjs\grab-service\src')) {
        $base = Join-Path $ProjectRoot $dir
        if (Test-Path $base) {
            $scanTargets += Get-ChildItem -Path $base -Recurse -Include '*.ts', '*.tsx' -File |
                Where-Object { $_.Name -notmatch '\.(test|spec)\.tsx?$' }
        }
    }
    foreach ($file in $scanTargets) {
        $matchesFound = Select-String -LiteralPath $file.FullName -Pattern $phonePattern
        foreach ($m in $matchesFound) {
            Add-Failure 'phone-literal' ("{0}:{1}: {2}" -f $file.FullName.Substring($ProjectRoot.Length + 1), $m.LineNumber, $m.Line.Trim())
        }
    }
}

# ---------- 检查 3：前端 / NestJS console 敏感字段 ----------
Test-File '前端与 NestJS 无 console 敏感字段输出' {
    $consolePattern = 'console\.(log|info|warn|error|debug)\('
    $sensitivePattern = '(?i)(token|password|idno|id_no|secret)'
    foreach ($dir in @('frontend\src', 'nestjs\grab-service\src')) {
        $base = Join-Path $ProjectRoot $dir
        if (-not (Test-Path $base)) { continue }
        $files = Get-ChildItem -Path $base -Recurse -Include '*.ts', '*.tsx' -File |
            Where-Object { $_.Name -notmatch '\.(test|spec)\.tsx?$' }
        foreach ($file in $files) {
            $matchesFound = Select-String -LiteralPath $file.FullName -Pattern $consolePattern
            foreach ($m in $matchesFound) {
                if ($m.Line -match $sensitivePattern) {
                    Add-Failure 'console-sensitive' ("{0}:{1}: {2}" -f $file.FullName.Substring($ProjectRoot.Length + 1), $m.LineNumber, $m.Line.Trim())
                }
            }
        }
    }
}

# ---------- 检查 4：Alipay SDK 参数级日志压制 ----------
Test-File 'java-payment 压制支付宝 SDK 参数级日志' {
    $yml = Join-Path $ProjectRoot 'java\java-payment\src\main\resources\application.yml'
    if (-not (Test-Path $yml)) {
        Add-Failure 'sdk-log-config' "缺少 $yml"
        return
    }
    $content = Get-Content -LiteralPath $yml -Raw -Encoding UTF8
    if ($content -notmatch '(?m)^\s*sdk\.biz\.info:\s*(warn|error|off)\s*$') {
        Add-Failure 'sdk-log-config' "application.yml 缺少 logging.level.sdk.biz.info: warn（或更低）配置"
    }
}

# ---------- 检查 5：AlipayApiException catch 块内裸异常落日志 ----------
Test-File 'java-payment Alipay 异常消息经脱敏后落日志' {
    $paymentMain = Get-ChildItem -Path (Join-Path $ProjectRoot 'java\java-payment\src\main\java') -Recurse -Filter '*.java' -File
    foreach ($file in $paymentMain) {
        $lines = Get-Content -LiteralPath $file.FullName -Encoding UTF8
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -notmatch 'catch\s*\(.*AlipayApiException') { continue }
            # catch 块向后看 12 行，遇到下一个 catch 块即止（后续 catch 是独立分支）
            $end = [Math]::Min($i + 12, $lines.Count - 1)
            for ($j = $i; $j -le $end; $j++) {
                $line = $lines[$j]
                if ($j -gt $i -and $line -match 'catch\s*\(') { break }
                if ($line -match 'AlipayLogSanitizer') { continue }
                if ($line -match '(e\.getMessage\(\))|(log\.(warn|error|info)\([^)]*,\s*e\s*\))') {
                    Add-Failure 'alipay-exception-raw' ("{0}:{1}: {2}" -f $file.FullName.Substring($ProjectRoot.Length + 1), ($j + 1), $line.Trim())
                }
            }
        }
    }
}

# ---------- 汇总 ----------
if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "发现 $($failures.Count) 处日志脱敏问题：" -ForegroundColor Red
    $failures | ForEach-Object {
        Write-Host ("  [{0}] {1}" -f $_.Category, $_.Detail) -ForegroundColor Yellow
    }
    exit 1
}

Write-Host ""
Write-Host "日志脱敏检查全部通过。" -ForegroundColor Green
exit 0

param(
    [string]$Apk = 'app/build/outputs/apk/debug/app-debug.apk',
    [string]$OutputDirectory = '../dist',
    [string]$Repository = 'OOIUa/hongguo-android'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Push-Location $project
try {
    $apkPath = (Resolve-Path -LiteralPath $Apk).Path
    $sdk = $env:ANDROID_HOME
    if (-not $sdk) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
    $buildTools = Join-Path $sdk 'build-tools/36.0.0'
    # Build Tools ship aapt.exe / apksigner.bat on Windows and aapt / apksigner on macOS and Linux.
    $onWindows = $PSVersionTable.PSEdition -eq 'Desktop' -or $IsWindows
    $aapt = Join-Path $buildTools ($(if ($onWindows) { 'aapt.exe' } else { 'aapt' }))
    $apksigner = Join-Path $buildTools ($(if ($onWindows) { 'apksigner.bat' } else { 'apksigner' }))
    $badging = (& $aapt dump badging $apkPath) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK.' }
    if ($badging -notmatch "package: name='com.hongguo.android' versionCode='(\d+)' versionName='([^']+)'") {
        throw 'Unexpected application ID or APK metadata.'
    }
    $code = [long]$Matches[1]
    $version = $Matches[2]
    if ($version -notmatch '^\d+\.\d+\.\d+(\.\d+)?$') { throw 'Only a regular release version can be published.' }
    if ($badging -notmatch "sdkVersion:'(\d+)'") { throw 'Missing minSdk.' }
    $minimum = [int]$Matches[1]
    $signature = (& $apksigner verify --print-certs $apkPath) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $expected = (Get-Content release-signing.sha256 -Raw).Trim().ToLowerInvariant()
    if ($signature -notmatch 'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})' -or $Matches[1].ToLowerInvariant() -ne $expected) {
        throw 'Signing certificate differs from the existing installation.'
    }
    & git diff --exit-code HEAD --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Commit source changes before preparing the matching source archive.' }
    $untracked = & git ls-files --others --exclude-standard
    if ($untracked) { throw 'Untracked source files must be reviewed and committed first.' }
    $null = New-Item -ItemType Directory -Force -Path $OutputDirectory
    $output = (Resolve-Path -LiteralPath $OutputDirectory).Path
    $apkName = "hongguo-android-$version.apk"
    $sourceName = "hongguo-android-$version-source.zip"
    Copy-Item -LiteralPath $apkPath -Destination (Join-Path $output $apkName)
    & git archive --format=zip "--prefix=hongguo-android-$version/" "--output=$(Join-Path $output $sourceName)" HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Source archive failed.' }
    $manifest = [ordered]@{
        schemaVersion = 1
        applicationId = 'com.hongguo.android'
        versionCode = $code
        versionName = $version
        apkUrl = "https://github.com/$Repository/releases/download/v$version/$apkName"
        sha256 = (Get-FileHash -LiteralPath (Join-Path $output $apkName) -Algorithm SHA256).Hash.ToLowerInvariant()
        sizeBytes = (Get-Item -LiteralPath (Join-Path $output $apkName)).Length
        minSdk = $minimum
        notes = "### 新增`n- 新增官方弹幕高赞角标（20 赞起显示）、弹幕密度档位设置及 145 个常用表情映射。`n- 新增完播续看状态记录，支持在追剧与观看历史中区分「已看完」与「接着看下一集」。`n`n### 优化`n- 优化「我的账号」页面为影院氛围通栏样式，支持通顶透明状态栏、吸顶导航栏过渡、影院观看历史横卡与带计数的滑动下划线片单栏。`n- 优化账号登录流程，精简为「手机验证码」与「扫码登录」两种方式。`n- 优化追剧与观看历史体验，合并本机与账号观看记录及在追收藏，支持按账号隔离历史并从本设备单独移除。`n- 优化全站默认配置，默认主题样式调整为「沉浸暗场」，默认外观模式调整为「跟随系统」。`n- 优化全站海报为统一 7:10 完整比例，精简首页频道、详情页与「我的」页信息排布。`n`n### 修复`n- 修复竖屏播放时弹幕底部区域可能遮挡剧集标题的问题。"
        publishedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    }
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $output 'update.json'), ($manifest | ConvertTo-Json -Depth 4) + "`n", $utf8)
    $sums = foreach ($name in @($apkName, $sourceName, 'update.json')) {
        $hash = (Get-FileHash -LiteralPath (Join-Path $output $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $name"
    }
    [IO.File]::WriteAllText((Join-Path $output "SHA256SUMS-$version.txt"), ($sums -join "`n") + "`n", $utf8)
    [pscustomobject]$manifest | Select-Object versionCode, versionName, sizeBytes, sha256
    Write-Output "Release assets prepared in $output"
} finally { Pop-Location }

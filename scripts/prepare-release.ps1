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
    $badging = (& (Join-Path $buildTools 'aapt.exe') dump badging $apkPath) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect APK.' }
    if ($badging -notmatch "package: name='com.hongguo.android' versionCode='(\d+)' versionName='([^']+)'") {
        throw 'Unexpected application ID or APK metadata.'
    }
    $code = [long]$Matches[1]
    $version = $Matches[2]
    if ($version -notmatch '^\d+\.\d+\.\d+(\.\d+)?$') { throw 'Only a regular release version can be published.' }
    if ($badging -notmatch "sdkVersion:'(\d+)'") { throw 'Missing minSdk.' }
    $minimum = [int]$Matches[1]
    $signature = (& (Join-Path $buildTools 'apksigner.bat') verify --print-certs $apkPath) -join "`n"
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
        notes = '换用简约版红果图标（自适应启动图标，Android 13 起支持主题图标），首页标题旁同步使用新 logo。补齐过渡动画：播放页上下滑动切集时当前画面随手指滑走、下一集带集数滑入，自动连播和选集跳转也以滑动切换，画面首帧淡入；进入详情、搜索和账号页从右侧滑入、返回时滑出，底栏切换淡入淡出，播放器打开时上滑出现、关闭时淡出。播放、主题、底栏样式、记录、续播、账号片单和更新功能保持不变。'
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

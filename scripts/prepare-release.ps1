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
        notes = '手机验证码改走官方 App 通道；平台要求验证时会弹窗引导到官方登录页手动完成。猜你喜欢修复登录后错误码 110，失败时换域名重试，翻页不再大量重复。每次取播放信息注册新的匿名设备；只提供 ByteVC2 的集会明确提示；缺加密信息的片源可使用校验通过的备用片源。登录页按手机验证码、官网、扫码分开，列表提前加载下一页。手机号登录如遇问题可改用官网或扫码登录。'
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

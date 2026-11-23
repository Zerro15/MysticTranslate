$ErrorActionPreference = "Stop"
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA "Android/Sdk" }
$tools = Join-Path $sdk "build-tools/36.0.0"
$platform = Join-Path $sdk "platforms/android-36/android.jar"
$folder = Join-Path ([IO.Path]::GetTempPath()) ("mystic-fixture-" + [guid]::NewGuid().ToString())
New-Item -ItemType Directory $folder | Out-Null

function Invoke-Tool {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool завершился с кодом $LASTEXITCODE" }
}

Push-Location $folder
try {
    Invoke-Tool "$tools/aapt.exe" @("package", "-f", "-M", "$PSScriptRoot/AndroidManifest.xml", "-I", $platform, "-F", "unsigned.apk")
    Invoke-Tool "javac" @("--release", "8", "-encoding", "UTF-8", "-d", ".", "-classpath", $platform, "$PSScriptRoot/Fixture.java")
    Invoke-Tool "$tools/d8.bat" @("--min-api", "26", "--lib", $platform, "--output", ".", "local/mystictest/Fixture.class")
    Invoke-Tool "jar" @("uf", "unsigned.apk", "classes.dex")
    Invoke-Tool "$tools/zipalign.exe" @("-f", "4", "unsigned.apk", "aligned.apk")
    $key = Join-Path $env:USERPROFILE ".android/debug.keystore"
    Invoke-Tool "$tools/apksigner.bat" @("sign", "--ks", $key, "--ks-pass", "pass:android", "--out", "fixture.apk", "aligned.apk")
    (Resolve-Path -LiteralPath "fixture.apk").Path
} finally {
    Pop-Location
}

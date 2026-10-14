$ErrorActionPreference = 'Stop'
$huatiRoot = $PSScriptRoot
$huatiJava = Join-Path $huatiRoot '.toolchain\jdk-ranged\jdk-17.0.16+8'
if (-not (Test-Path -LiteralPath (Join-Path $huatiJava 'bin\java.exe'))) {
    $huatiJava = Join-Path $huatiRoot '.toolchain\jdk\jdk-17.0.16+8'
}
if (-not (Test-Path -LiteralPath (Join-Path $huatiJava 'bin\java.exe'))) { throw 'Prepare the local Android build tools first.' }
$env:JAVA_HOME = $huatiJava
$huatiSdk = Join-Path $huatiRoot '.toolchain\sdk'
$env:ANDROID_HOME = $huatiSdk
$huatiGradle = Join-Path $huatiRoot '.toolchain\gradle\gradle-8.9\bin\gradle.bat'
$huatiAndroid = Join-Path $huatiRoot 'android'
$huatiProperties = 'sdk.dir=' + $huatiSdk.Replace('\', '/')
[IO.File]::WriteAllText((Join-Path $huatiAndroid 'local.properties'), $huatiProperties, [Text.Encoding]::ASCII)
& $huatiGradle -p $huatiAndroid --console=plain testDebugUnitTest lintRelease assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' }

$huatiPrivate = Join-Path $huatiRoot 'private'
$huatiPassword = Join-Path $huatiPrivate 'android-signing-password.txt'
$huatiKey = Join-Path $huatiPrivate 'android-signing.p12'
if (-not (Test-Path -LiteralPath $huatiPassword)) {
    New-Item -ItemType Directory -Force -Path $huatiPrivate | Out-Null
    $huatiRandom = New-Object byte[] 32
    $huatiRng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $huatiRng.GetBytes($huatiRandom) } finally { $huatiRng.Dispose() }
    [IO.File]::WriteAllText($huatiPassword, [Convert]::ToBase64String($huatiRandom), [Text.Encoding]::ASCII)
}
if (-not (Test-Path -LiteralPath $huatiKey)) {
    & (Join-Path $huatiJava 'bin\keytool.exe') -genkeypair -keystore $huatiKey -storetype PKCS12 -storepass:file $huatiPassword -alias huati -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Huati Assistant, OU=Personal, O=Local, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed.' }
}
$huatiDist = Join-Path $huatiRoot 'dist'
New-Item -ItemType Directory -Force -Path $huatiDist | Out-Null
$huatiVersionMatch = [regex]::Match([IO.File]::ReadAllText((Join-Path $huatiAndroid 'app\build.gradle')), "versionName\s+'([^']+)'")
if (-not $huatiVersionMatch.Success) { throw 'Could not determine app version.' }
$huatiApk = Join-Path $huatiDist ('huati-assistant-v' + $huatiVersionMatch.Groups[1].Value + '.apk')
$huatiSigner = Join-Path $huatiSdk 'build-tools\35.0.0\apksigner.bat'
& $huatiSigner sign --ks $huatiKey --ks-key-alias huati --ks-pass "file:$huatiPassword" --out $huatiApk (Join-Path $huatiAndroid 'app\build\outputs\apk\release\app-release-unsigned.apk')
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& $huatiSigner verify --verbose $huatiApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$huatiHash = (Get-FileHash -LiteralPath $huatiApk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($huatiApk + '.sha256'), ($huatiHash + '  ' + [IO.Path]::GetFileName($huatiApk)), [Text.Encoding]::ASCII)
Write-Host ('APK ready: ' + $huatiApk)

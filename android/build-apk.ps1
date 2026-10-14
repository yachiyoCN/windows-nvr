param(
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [string]$JdkRoot = $env:JAVA_HOME,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'build\manual'),
    [string]$Keystore = '',
    [string]$KeyAlias = '',
    [string]$StorePasswordEnv = '',
    [string]$KeyPasswordEnv = ''
)
$ErrorActionPreference = 'Stop'
$sdk = (Resolve-Path -LiteralPath $SdkRoot).Path
if ([string]::IsNullOrWhiteSpace($JdkRoot)) { throw '请用 -JdkRoot 指定 JDK 17 或更新版本。' }
$jdk = (Resolve-Path -LiteralPath $JdkRoot).Path
$platform = Join-Path $sdk 'platforms\android-35\android.jar'
if (-not (Test-Path -LiteralPath $platform)) { throw '需要 Android SDK Platform 35。' }
$tools = @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory | Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } | Sort-Object { [version]$_.Name } -Descending)[0].FullName
foreach ($file in @('aapt2.exe', 'd8.bat', 'zipalign.exe', 'apksigner.bat')) {
    if (-not (Test-Path -LiteralPath (Join-Path $tools $file))) { throw "缺少 Android 构建工具：$file" }
}
$build = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $build | Out-Null
$buildId = [guid]::NewGuid().ToString('N').Substring(0, 10)
$classes = Join-Path $build "classes-$buildId"
$generated = Join-Path $build "generated-$buildId"
$dex = Join-Path $build "dex-$buildId"
foreach ($folder in @($classes, $generated, $dex)) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
$resources = Join-Path $build 'resources.zip'
$manifest = Join-Path $build 'AndroidManifest.xml'
[xml]$manifestXml = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'app\src\main\AndroidManifest.xml') -Raw -Encoding UTF8
$manifestXml.manifest.SetAttribute('package', 'cn.homewatch.recorder')
$manifestXml.Save($manifest)
$unsigned = Join-Path $build 'recorder-unsigned.apk'
$aligned = Join-Path $build 'recorder-aligned.apk'
$apk = Join-Path $build '录像机.apk'
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    Push-Location -LiteralPath $PSScriptRoot
    try {
        & $Executable @Arguments
        if ($LASTEXITCODE -ne 0) { throw "构建步骤失败：$([System.IO.Path]::GetFileName($Executable))，退出码 $LASTEXITCODE" }
    } finally { Pop-Location }
}
function Native-Relative([string]$FullPath) {
    $base = [uri]($PSScriptRoot.TrimEnd('\') + '\')
    return [uri]::UnescapeDataString($base.MakeRelativeUri([uri]$FullPath).ToString()).Replace('/', '\')
}
# Windows aapt2 uses the active code page for paths; relative arguments keep Chinese project parent names working.
Invoke-Checked (Join-Path $tools 'aapt2.exe') @('compile', '--dir', 'app\src\main\res', '-o', (Native-Relative $resources))
Invoke-Checked (Join-Path $tools 'aapt2.exe') @('link', '-o', (Native-Relative $unsigned), '-I', (Native-Relative $platform), '--manifest', (Native-Relative $manifest), '--java', (Native-Relative $generated), '--min-sdk-version', '26', '--target-sdk-version', '35', '--version-code', '9', '--version-name', '1.5.1', '--auto-add-overlay', '-R', (Native-Relative $resources))
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'app\src\main\java') -Filter '*.java' -Recurse | ForEach-Object FullName)
$sources += @(Get-ChildItem -LiteralPath $generated -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-Checked (Join-Path $jdk 'bin\javac.exe') (@('-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '--release', '17', '-classpath', $platform, '-d', $classes) + $sources)
$classJar = Join-Path $build 'classes.jar'
Invoke-Checked (Join-Path $jdk 'bin\jar.exe') @('--create', '--file', $classJar, '-C', $classes, '.')
$oldJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $jdk
    Invoke-Checked (Join-Path $tools 'd8.bat') @('--lib', $platform, '--min-api', '26', '--output', $dex, $classJar)
    $dexFiles = @(Get-ChildItem -LiteralPath $dex -Filter '*.dex' | ForEach-Object Name)
    foreach ($dexFile in $dexFiles) { Invoke-Checked (Join-Path $jdk 'bin\jar.exe') @('--update', '--file', $unsigned, '-C', $dex, $dexFile) }
    Invoke-Checked (Join-Path $tools 'zipalign.exe') @('-f', '4', (Native-Relative $unsigned), (Native-Relative $aligned))
    if ([string]::IsNullOrWhiteSpace($Keystore)) {
        $Keystore = Join-Path $build 'debug.keystore'
        $KeyAlias = 'androiddebugkey'
        if (-not (Test-Path -LiteralPath $Keystore)) {
            Invoke-Checked (Join-Path $jdk 'bin\keytool.exe') @('-genkeypair', '-keystore', $Keystore, '-storepass', 'android', '-keypass', 'android', '-alias', $KeyAlias, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Recorder Debug,O=Home Recorder,C=CN')
        }
        $signing = @('--ks', $Keystore, '--ks-key-alias', $KeyAlias, '--ks-pass', 'pass:android', '--key-pass', 'pass:android')
    } else {
        if ([string]::IsNullOrWhiteSpace($StorePasswordEnv) -or [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($StorePasswordEnv))) { throw '签名需通过 -StorePasswordEnv 指定已有密码环境变量名。' }
        $signing = @('--ks', (Resolve-Path -LiteralPath $Keystore).Path, '--ks-pass', "env:$StorePasswordEnv")
        if (-not [string]::IsNullOrWhiteSpace($KeyAlias)) { $signing += @('--ks-key-alias', $KeyAlias) }
        if (-not [string]::IsNullOrWhiteSpace($KeyPasswordEnv)) { $signing += @('--key-pass', "env:$KeyPasswordEnv") }
    }
    Invoke-Checked (Join-Path $tools 'apksigner.bat') (@('sign') + $signing + @('--out', $apk, $aligned))
    Invoke-Checked (Join-Path $tools 'apksigner.bat') @('verify', '--verbose', $apk)
    Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Select-Object Path, Hash
} finally { $env:JAVA_HOME = $oldJavaHome }

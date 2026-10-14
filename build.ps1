param(
    [string]$JdkPath,
    [switch]$CompileOnly,
    [string]$DependencyDirectory,
    [string]$BuildDirectory,
    [string]$Destination,
    [string]$RuntimeImage,
    [switch]$LauncherOnly,
    [switch]$IncludePersonDetection
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$projectLegalFiles = @('LICENSE', 'NOTICE')
foreach ($name in $projectLegalFiles) {
    if (!(Test-Path -LiteralPath (Join-Path $projectRoot $name) -PathType Leaf)) {
        throw ('缺少项目许可文件：' + $name)
    }
}

function Resolve-ProjectPath {
    param([string]$Value, [string]$DefaultRelative)
    if ([string]::IsNullOrWhiteSpace($Value)) { $Value = $DefaultRelative }
    if (![System.IO.Path]::IsPathRooted($Value)) { $Value = Join-Path $projectRoot $Value }
    return [System.IO.Path]::GetFullPath($Value)
}

function Find-ProjectJdk {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($JdkPath) { $candidates.Add($JdkPath) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $compilerCommand = Get-Command 'javac.exe' -ErrorAction SilentlyContinue
    if ($compilerCommand) {
        $candidates.Add((Split-Path (Split-Path $compilerCommand.Source -Parent) -Parent))
    }
    foreach ($candidate in $candidates) {
        $jdk = [System.IO.Path]::GetFullPath($candidate)
        $complete = $true
        foreach ($tool in @('javac.exe', 'jar.exe', 'jlink.exe', 'jpackage.exe')) {
            if (!(Test-Path -LiteralPath (Join-Path $jdk ('bin/' + $tool)) -PathType Leaf)) {
                $complete = $false
            }
        }
        if (!$complete) { continue }
        $previousErrorAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $versionText = (& (Join-Path $jdk 'bin/javac.exe') '-version' 2>&1 | Out-String).Trim()
            $versionExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousErrorAction }
        if ($versionExit -eq 0 -and $versionText -match 'javac\s+(\d+)' -and [int]$Matches[1] -ge 21) {
            return $jdk
        }
    }
    throw '需要完整的 JDK 21 或更新版本，请设置 JAVA_HOME 或传入 -JdkPath。'
}

function Invoke-BuildTool {
    param([string]$Executable, [string[]]$ToolArguments)
    & $Executable @ToolArguments
    if ($LASTEXITCODE -ne 0) {
        throw ('构建工具失败：{0}，退出码 {1}' -f [System.IO.Path]::GetFileName($Executable), $LASTEXITCODE)
    }
}

$jdkRoot = Find-ProjectJdk
$dependencyRoot = Resolve-ProjectPath $DependencyDirectory 'tools'
$buildRoot = Resolve-ProjectPath $BuildDirectory 'build'
$destinationRoot = Resolve-ProjectPath $Destination 'dist'
$applicationName = '录像机'
$applicationRoot = Join-Path $destinationRoot $applicationName
$desktopRoot = Join-Path $projectRoot 'desktop'
$resourcesRoot = Join-Path $desktopRoot 'src/main/resources'
$applicationIcon = Join-Path $desktopRoot 'assets/recorder.ico'
$personDirectory = Join-Path $dependencyRoot 'person-detection'
$personLibrary = Join-Path $personDirectory 'onnxruntime-1.20.0.jar'
$personModel = Join-Path $personDirectory 'yolox_tiny.onnx'
$hasPersons = (Test-Path -LiteralPath $personLibrary -PathType Leaf) -and
    (Test-Path -LiteralPath $personModel -PathType Leaf)

if (!$CompileOnly) {
    if (Test-Path -LiteralPath $applicationRoot) {
        throw '目标程序目录已存在。请使用新的 -Destination，脚本不会覆盖现有程序或运行数据。'
    }
    if (!(Test-Path -LiteralPath $applicationIcon -PathType Leaf)) {
        throw '缺少 desktop/assets/recorder.ico，请先运行 scripts/generate-app-icon.ps1。'
    }
}
if (!$CompileOnly -and !$LauncherOnly) {
    foreach ($binary in @('ffmpeg.exe', 'ffprobe.exe', 'ffplay.exe')) {
        if (!(Test-Path -LiteralPath (Join-Path $dependencyRoot $binary) -PathType Leaf)) {
            throw ('缺少 {0}，请按 docs/开发说明.md 准备依赖或传入 -DependencyDirectory。' -f $binary)
        }
    }
    if ($IncludePersonDetection -and !$hasPersons) {
        throw '缺少指定版本的人形模型或 ONNX Runtime。请执行 scripts/download-person-dependencies.ps1。'
    }
}

$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $desktopRoot 'src/main/java') -Filter '*.java' -Recurse -File |
    Sort-Object FullName | ForEach-Object { $_.FullName })
if ($sourceFiles.Count -eq 0) { throw 'desktop/src/main/java 目录中没有 Java 源码。' }
$guardSource = Join-Path $desktopRoot 'native/StorageGuard.cs'
if (!(Test-Path -LiteralPath $guardSource -PathType Leaf)) { throw '缺少 desktop/native/StorageGuard.cs。' }
$guardCompiler = @(
    (Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'),
    (Join-Path $env:WINDIR 'Microsoft.NET/Framework/v4.0.30319/csc.exe')
) | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
if (!$guardCompiler) { throw '未找到 Windows .NET Framework C# 编译器，无法构建目录校验组件。' }

New-Item -ItemType Directory -Path $buildRoot -Force | Out-Null
$buildId = (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6)
$classesRoot = Join-Path $buildRoot ('classes-' + $buildId)
$inputRoot = Join-Path $buildRoot ('package-input-' + $buildId)
New-Item -ItemType Directory -Path $classesRoot, $inputRoot | Out-Null
$jarPath = Join-Path $inputRoot 'home-monitor.jar'
$guardPath = Join-Path $buildRoot 'StorageGuard.exe'

Write-Host '正在编译 Java 程序和录像目录校验组件…'
Invoke-BuildTool (Join-Path $jdkRoot 'bin/javac.exe') (
    @('--release', '21', '-encoding', 'UTF-8', '-d', $classesRoot) + $sourceFiles)
if (!(Test-Path -LiteralPath $resourcesRoot -PathType Container)) { throw '缺少桌面资源目录 desktop/src/main/resources。' }
$legalResources = Join-Path $classesRoot 'META-INF'
New-Item -ItemType Directory -Path $legalResources -Force | Out-Null
foreach ($name in $projectLegalFiles) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $name) -Destination (Join-Path $legalResources $name)
}
Invoke-BuildTool (Join-Path $jdkRoot 'bin/jar.exe') @(
    '--create', '--file', $jarPath, '--main-class', 'cn.homewatch.HomeMonitor', '-C', $classesRoot, '.', '-C', $resourcesRoot, '.')
Copy-Item -LiteralPath $jarPath -Destination (Join-Path $buildRoot 'home-monitor.jar') -Force
Invoke-BuildTool $guardCompiler @('/nologo', '/optimize+', '/target:exe', ('/out:' + $guardPath), $guardSource)

if ($CompileOnly) {
    Write-Host ('编译完成：{0}' -f (Join-Path $buildRoot 'home-monitor.jar'))
    return
}

$runtimeRoot = if ($RuntimeImage) { Resolve-ProjectPath $RuntimeImage 'runtime' } else { Join-Path $buildRoot 'runtime' }
if ($RuntimeImage -and !(Test-Path -LiteralPath (Join-Path $runtimeRoot 'bin/javaw.exe') -PathType Leaf)) {
    throw '指定的 -RuntimeImage 不是完整的 Windows Java 运行环境。'
}
if (!$RuntimeImage -and !(Test-Path -LiteralPath $runtimeRoot)) {
    Write-Host '正在生成程序专用 Java 运行环境…'
    Invoke-BuildTool (Join-Path $jdkRoot 'bin/jlink.exe') @(
        '--add-modules', 'java.base,java.desktop,java.logging,java.management,java.naming,java.security.jgss,jdk.crypto.ec,jdk.httpserver',
        '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=2', '--output', $runtimeRoot)
} elseif (!(Test-Path -LiteralPath (Join-Path $runtimeRoot 'bin/javaw.exe') -PathType Leaf)) {
    throw '构建运行环境目录已存在但不完整。请换用新的 -BuildDirectory。'
}
$runtimeModules = @(& (Join-Path $runtimeRoot 'bin/java.exe') '--list-modules')
if ($LASTEXITCODE -ne 0 -or !($runtimeModules | Where-Object { $_ -match '^jdk\.httpserver(?:@|$)' })) {
    throw '指定运行环境缺少 jdk.httpserver，无法使用网页/API。请使用新的 -BuildDirectory 自动生成包含此模块的运行环境。'
}

New-Item -ItemType Directory -Path $destinationRoot -Force | Out-Null
Write-Host '正在生成可整体移动的 Windows 程序…'
# Single quotes deliberately preserve jpackage's $APPDIR variable for the native launcher.
Invoke-BuildTool (Join-Path $jdkRoot 'bin/jpackage.exe') @(
    '--type', 'app-image', '--name', $applicationName, '--dest', $destinationRoot,
    '--input', $inputRoot, '--main-jar', 'home-monitor.jar', '--main-class', 'cn.homewatch.HomeMonitor',
    '--runtime-image', $runtimeRoot, '--app-version', '1.4.1', '--icon', $applicationIcon,
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Dhomewatch.root=$APPDIR/..',
    '--java-options', '-Djava.io.tmpdir=$APPDIR/../data/temp',
    '--java-options', '-Duser.home=$APPDIR/../data',
    '--java-options', '-Djava.util.prefs.userRoot=$APPDIR/../data/prefs',
    '--java-options', '-XX:ErrorFile=$APPDIR/../data/logs/jvm-error-%p.log')

foreach ($name in $projectLegalFiles) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $name) -Destination (Join-Path $applicationRoot $name)
}

if ($LauncherOnly) {
    Write-Host ('启动器已生成：{0}' -f (Join-Path $applicationRoot ($applicationName + '.exe')))
    Write-Host '仅供更新已有安装：使用 scripts/update-installed-launcher.ps1 替换启动器和 JAR。'
    return
}

$applicationTools = Join-Path $applicationRoot 'tools'
New-Item -ItemType Directory -Path $applicationTools -Force | Out-Null
foreach ($binary in @('ffmpeg.exe', 'ffprobe.exe', 'ffplay.exe')) {
    Copy-Item -LiteralPath (Join-Path $dependencyRoot $binary) -Destination (Join-Path $applicationTools $binary)
}
Copy-Item -LiteralPath $guardPath -Destination (Join-Path $applicationTools 'StorageGuard.exe')
$ffmpegLicense = Join-Path $dependencyRoot 'LICENSE'
$ffmpegReadme = Join-Path $dependencyRoot 'README.txt'
if (!(Test-Path -LiteralPath $ffmpegLicense -PathType Leaf)) {
    $ffmpegLicense = Join-Path $projectRoot 'third_party/FFmpeg/LICENSE.txt'
}
if (!(Test-Path -LiteralPath $ffmpegReadme -PathType Leaf)) {
    $ffmpegReadme = Join-Path $projectRoot 'third_party/FFmpeg/README.txt'
}
Copy-Item -LiteralPath $ffmpegLicense -Destination (Join-Path $applicationTools 'LICENSE')
Copy-Item -LiteralPath $ffmpegReadme -Destination (Join-Path $applicationTools 'README.txt')
$ffmpegChecksum = Join-Path $dependencyRoot '下载校验.sha256'
if (Test-Path -LiteralPath $ffmpegChecksum -PathType Leaf) {
    Copy-Item -LiteralPath $ffmpegChecksum -Destination (Join-Path $applicationTools '下载校验.sha256')
}

if ($hasPersons) {
    $targetPersons = Join-Path $applicationTools 'person-detection'
    New-Item -ItemType Directory -Path $targetPersons | Out-Null
    Copy-Item -LiteralPath $personLibrary -Destination (Join-Path $targetPersons 'onnxruntime-1.20.0.jar')
    Copy-Item -LiteralPath $personModel -Destination (Join-Path $targetPersons 'yolox_tiny.onnx')
    foreach ($text in @('LICENSE-YOLOX.txt', 'LICENSE-ONNXRuntime.txt', 'NOTICE.txt')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot ('third_party/person-detection/' + $text)) `
            -Destination (Join-Path $targetPersons $text)
    }
}

foreach ($relative in @('config', 'data/screenshots', 'data/recordings', 'data/logs', 'data/temp', 'data/prefs')) {
    New-Item -ItemType Directory -Path (Join-Path $applicationRoot $relative) -Force | Out-Null
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'docs/使用说明.md') -Destination (Join-Path $applicationRoot '使用说明.md')
Write-Host ('打包完成：{0}' -f (Join-Path $applicationRoot ($applicationName + '.exe')))
Write-Host '配置和录像目录已初始化，请启动程序完成设备设置。'


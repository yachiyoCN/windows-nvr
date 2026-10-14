param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(Mandatory = $true)][string]$SourceDirectory,
    [Parameter(Mandatory = $true)][string]$InstalledProgramDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$ApkPath,
    [switch]$NoZip
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'update-package-common.ps1')
$sourceRoot = Get-NvrAbsoluteDirectory $SourceDirectory
$installedRoot = Get-NvrAbsoluteDirectory $InstalledProgramDirectory
$outputRoot = Get-NvrAbsoluteDirectory $OutputDirectory
$jar = [IO.Path]::GetFullPath($JarPath)
foreach ($root in @($sourceRoot, $installedRoot, $outputRoot)) { Assert-NvrRealPath $root }
foreach ($inputRoot in @($sourceRoot, $installedRoot)) {
    if (!(Test-Path -LiteralPath $inputRoot -PathType Container)) { throw ('找不到输入目录：' + $inputRoot) }
    if ($outputRoot -eq $inputRoot -or $outputRoot.StartsWith($inputRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or $inputRoot.StartsWith($outputRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw '更新包输出必须与源码、现有程序目录互相独立。' }
}
if (!(Test-Path -LiteralPath $jar -PathType Leaf)) { throw '找不到最终审核的 JAR。' }
if ((Test-Path -LiteralPath $outputRoot) -and @(Get-ChildItem -LiteralPath $outputRoot -Force).Count -gt 0) { throw '输出目录非空。请使用新的空目录，避免覆盖其他文件。' }
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

function Copy-NvrPackageFile([string]$Source, [string]$Relative) {
    if (!(Test-NvrReleaseFile $Relative) -and !(Test-NvrPackageSupport $Relative)) { throw ('拒绝打包非发布文件：' + $Relative) }
    if (!(Test-Path -LiteralPath $Source -PathType Leaf)) { throw ('缺少打包文件：' + $Relative) }
    Assert-NvrRealPath $Source
    $target = Join-Path $outputRoot $Relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    [IO.File]::Copy($Source, $target, $false)
    (Get-Item -LiteralPath $target -Force).Attributes = [IO.FileAttributes]::Normal
}
function Write-NvrPackageText([string]$Relative, [string]$Text, [bool]$Bom = $true) {
    if (!(Test-NvrReleaseFile $Relative) -and !(Test-NvrPackageSupport $Relative)) { throw '拒绝打包非发布文本。' }
    $target = Join-Path $outputRoot $Relative
    New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
    [IO.File]::WriteAllText($target, $Text, (New-Object Text.UTF8Encoding($Bom)))
}

Copy-NvrPackageFile $jar 'app/home-monitor.jar'
foreach ($name in @('LICENSE', 'NOTICE')) { Copy-NvrPackageFile (Join-Path $sourceRoot $name) $name }
foreach ($relative in @('录像机.exe', 'app/录像机.cfg')) { Copy-NvrPackageFile (Join-Path $installedRoot $relative) $relative }
$launcherConfig = Get-Content -LiteralPath (Join-Path $outputRoot 'app/录像机.cfg') -Raw -Encoding UTF8
if ($launcherConfig -notmatch 'app.mainclass=cn.homewatch.HomeMonitor' -or $launcherConfig -match '(?i)[A-Z]:[\\/]|https?://|rtsps?://|password|token|credential') { throw '启动器参数含有本机地址、凭据或未知入口。' }
$runtimeRoot = Join-Path $installedRoot 'runtime'
foreach ($item in @(Get-ChildItem -LiteralPath $runtimeRoot -Force -Recurse)) {
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '公共运行环境不能含有符号链接或目录联接。' }
    if (!$item.PSIsContainer) {
        $relative = Get-NvrRelativePath $installedRoot $item.FullName
        if (!(Test-NvrReleaseFile $relative)) { throw ('运行环境中有非发布文件：' + $relative) }
        Copy-NvrPackageFile $item.FullName $relative
    }
}
foreach ($relative in @('tools/ffmpeg.exe', 'tools/ffplay.exe', 'tools/ffprobe.exe', 'tools/StorageGuard.exe', 'tools/LICENSE', 'tools/README.txt', 'tools/下载校验.sha256',
    'tools/person-detection/onnxruntime-1.20.0.jar', 'tools/person-detection/yolox_tiny.onnx', 'tools/person-detection/LICENSE-ONNXRuntime.txt',
    'tools/person-detection/LICENSE-YOLOX.txt', 'tools/person-detection/NOTICE.txt', 'tools/person-detection/native/onnxruntime.dll', 'tools/person-detection/native/onnxruntime4j_jni.dll')) {
    Copy-NvrPackageFile (Join-Path $installedRoot $relative) $relative
}
if (!$ApkPath) { $ApkPath = Join-Path $installedRoot '安卓APP/录像机.apk' }
Copy-NvrPackageFile ([IO.Path]::GetFullPath($ApkPath)) '安卓APP/录像机.apk'
$usageText = Get-Content -LiteralPath (Join-Path $sourceRoot 'docs/使用说明.md') -Raw -Encoding UTF8
Write-NvrPackageText '使用说明.md' $usageText
$updateText = @'
# 录像机更新

1. 将此更新包解压到新的空目录，保留全部文件；不要把 ZIP 留在解压目录。
2. 双击“更新.cmd”更新同级“程序”，或把已有程序目录拖到“更新.cmd”上。
3. 工具校验清单后请求正常退出、保存当前录像，备份原发布文件后更新并重新打开。

更新包不包含 config、data、摄像头配置、账号、登录凭据、私钥、录像或截图。请保留原程序目录及原录像位置，不要使用删除目标多余文件的镜像同步。

安卓安装包位于“安卓APP/录像机.apk”。直接覆盖更新旧 App，不要卸载或清除数据；原签名兼容时保留加密登录信息。

详细安装、手动覆盖、失败恢复和私人备份步骤见同目录 [使用说明](使用说明.md#更新与备份)。
'@
Write-NvrPackageText '更新说明.md' $updateText
foreach ($mapping in @(@('install-update.ps1', '更新工具/安装更新.ps1'), @('update-package-common.ps1', '更新工具/更新包校验.ps1'))) {
    Write-NvrPackageText $mapping[1] (Get-Content -LiteralPath (Join-Path $PSScriptRoot $mapping[0]) -Raw -Encoding UTF8)
}
$command = @'
@echo off
chcp 65001 >nul
setlocal
if "%~1"=="" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0更新工具\安装更新.ps1"
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0更新工具\安装更新.ps1" -TargetDirectory "%~1"
)
if errorlevel 1 (
    echo.
    echo 更新未完成，请查看上面的原因。摄像头设置和录像不会被此更新包替换。
) else (
    echo.
    echo 更新完成。
)
pause
'@
Write-NvrPackageText '更新.cmd' ($command.Replace("`n", "`r`n")) $false
$entries = @(Get-ChildItem -LiteralPath $outputRoot -File -Recurse -Force | Sort-Object FullName | ForEach-Object {
    @{ path = (Get-NvrRelativePath $outputRoot $_.FullName); length = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
})
$manifest = @{ format = 1; product = 'homewatch-nvr'; created = [DateTime]::UtcNow.ToString('o'); files = $entries }
[IO.File]::WriteAllText((Join-Path $outputRoot '更新清单.json'), ($manifest | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($true)))
[void](Read-NvrVerifiedManifest $outputRoot)
if (!$NoZip) {
    $zip = $outputRoot + '.zip'
    if (Test-Path -LiteralPath $zip) { throw 'ZIP 已存在，请使用新的输出目录。' }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($zip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in @(Get-ChildItem -LiteralPath $outputRoot -File -Recurse -Force | Sort-Object FullName)) {
            $relative = Get-NvrRelativePath $outputRoot $file.FullName
            [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $relative, [IO.Compression.CompressionLevel]::Optimal)
        }
    } finally { $archive.Dispose() }
    Write-Host ('更新 ZIP：' + $zip)
}
Write-Host ('无用户配置的更新包：' + $outputRoot)
Write-Host ('最终 JAR SHA-256：' + (Get-FileHash -LiteralPath (Join-Path $outputRoot 'app/home-monitor.jar') -Algorithm SHA256).Hash)
Write-Host ('发布及更新辅助文件：' + $entries.Count + '；不包含 config、data、录像或本机凭据。')

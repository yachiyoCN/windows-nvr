param(
    [Parameter(Mandatory = $true)][string]$ProgramDirectory,
    [Parameter(Mandatory = $true)][string]$LauncherDirectory,
    [string]$ShortcutPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$programRoot = [System.IO.Path]::GetFullPath($ProgramDirectory).TrimEnd('\', '/')
$launcherRoot = [System.IO.Path]::GetFullPath($LauncherDirectory).TrimEnd('\', '/')
if ($programRoot -eq $launcherRoot) { throw '新启动器必须位于单独的暂存目录。' }
foreach ($relative in @('runtime/bin/javaw.exe', 'tools/ffmpeg.exe', 'app/home-monitor.jar')) {
    if (!(Test-Path -LiteralPath (Join-Path $programRoot $relative) -PathType Leaf)) {
        throw ('现有程序目录不完整：缺少 ' + $relative)
    }
}
foreach ($relative in @('录像机.exe', 'app/录像机.cfg', 'app/home-monitor.jar')) {
    if (!(Test-Path -LiteralPath (Join-Path $launcherRoot $relative) -PathType Leaf)) {
        throw ('新启动器目录不完整：缺少 ' + $relative)
    }
}
$runtimeJava = Join-Path $programRoot 'runtime/bin/java.exe'
if (!(Test-Path -LiteralPath $runtimeJava -PathType Leaf)) { throw '现有运行环境不完整：缺少 java.exe。' }
$runtimeModules = & $runtimeJava '--list-modules'
if ($LASTEXITCODE -ne 0 -or !($runtimeModules -match '^jdk\.httpserver@')) {
    throw '现有运行环境缺少 Web/API 所需的 jdk.httpserver，请先更新为本版构建的完整 runtime 目录。'
}
foreach ($process in Get-Process) {
    try { $executablePath = $process.Path } catch { continue }
    if ($executablePath -and
        $executablePath.StartsWith($programRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw '录像机或媒体组件仍在运行。请先关闭程序，等待当前录像保存完成后更新。'
    }
}
if ($ShortcutPath -and [System.IO.Path]::GetExtension($ShortcutPath) -ne '.lnk') { throw '快捷方式路径必须以 .lnk 结尾。' }

$backupRoot = Join-Path $programRoot ('data/backups/launcher-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $backupRoot 'app') -Force | Out-Null
foreach ($relative in @('录像机.exe', '家庭监控.exe', 'app/录像机.cfg', 'app/家庭监控.cfg', 'app/home-monitor.jar', '使用说明.md')) {
    $existing = Join-Path $programRoot $relative
    if (Test-Path -LiteralPath $existing -PathType Leaf) {
        Copy-Item -LiteralPath $existing -Destination (Join-Path $backupRoot $relative) -Force
    }
}
try {
    foreach ($relative in @('录像机.exe', 'app/录像机.cfg', 'app/home-monitor.jar')) {
        Copy-Item -LiteralPath (Join-Path $launcherRoot $relative) -Destination (Join-Path $programRoot $relative) -Force
    }
    $usage = Join-Path $PSScriptRoot '../docs/使用说明.md'
    if (Test-Path -LiteralPath $usage -PathType Leaf) {
        Copy-Item -LiteralPath $usage -Destination (Join-Path $programRoot '使用说明.md') -Force
    }
    foreach ($relative in @('家庭监控.exe', 'app/家庭监控.cfg')) {
        $oldLauncher = Join-Path $programRoot $relative
        if (Test-Path -LiteralPath $oldLauncher -PathType Leaf) {
            Move-Item -LiteralPath $oldLauncher -Destination (Join-Path $backupRoot ('retired-' + [System.IO.Path]::GetFileName($relative))) -Force
        }
    }
    if ($ShortcutPath) {
        $shortcutAbsolute = [System.IO.Path]::GetFullPath($ShortcutPath)
        $shortcutFolder = Split-Path $shortcutAbsolute -Parent
        if (!(Test-Path -LiteralPath $shortcutFolder -PathType Container)) { throw '快捷方式所在目录不存在。' }
        $shell = New-Object -ComObject WScript.Shell
        $shortcut = $shell.CreateShortcut($shortcutAbsolute)
        $shortcut.TargetPath = Join-Path $programRoot '录像机.exe'
        $shortcut.WorkingDirectory = $programRoot
        $shortcut.IconLocation = (Join-Path $programRoot '录像机.exe') + ',0'
        $shortcut.Description = '录像机：实时预览、连续录像与回放'
        $shortcut.Save()
    }
} catch {
    throw ('启动器更新未完成。原文件备份在 ' + $backupRoot + '。原因：' + $_.Exception.Message)
}
Write-Host ('启动器更新完成：' + (Join-Path $programRoot '录像机.exe'))
Write-Host ('原启动器与 JAR 备份：' + $backupRoot)
Write-Host '运行环境、媒体组件、配置和录像数据保持原位置。'

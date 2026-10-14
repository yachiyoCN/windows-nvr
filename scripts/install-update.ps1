param(
    [string]$TargetDirectory,
    [switch]$NoStart,
    [switch]$ValidateOnly,
    [ValidateRange(5, 300)][int]$ShutdownTimeoutSeconds = 60
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot '更新包校验.ps1')
$packageRoot = Get-NvrAbsoluteDirectory (Join-Path $PSScriptRoot '..')
$manifest = Read-NvrVerifiedManifest $packageRoot
if (!$TargetDirectory) { $TargetDirectory = Join-Path $packageRoot '..\程序' }
$targetRoot = Get-NvrAbsoluteDirectory $TargetDirectory
Assert-NvrRealPath $targetRoot
if ($targetRoot -eq $packageRoot -or $targetRoot.StartsWith($packageRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    $packageRoot.StartsWith($targetRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw '更新包与程序目录必须互相独立。' }
if (!(Test-Path -LiteralPath $targetRoot -PathType Container)) { throw '找不到现有程序目录，请指定包含录像机.exe 的目录。' }
foreach ($required in @('录像机.exe', 'app/home-monitor.jar', 'app/录像机.cfg', 'runtime/bin/javaw.exe', 'tools/ffmpeg.exe')) {
    $destination = Join-Path $targetRoot $required
    Assert-NvrRealPath $destination
    if (!(Test-Path -LiteralPath $destination -PathType Leaf)) { throw ('目标不是完整录像机程序目录：缺少 ' + $required) }
}
$releaseFiles = @($manifest.files | Where-Object { Test-NvrReleaseFile $_.path })
foreach ($entry in $releaseFiles) {
    $destination = Join-Path $targetRoot $entry.path
    Assert-NvrRealPath $destination
    if (Test-Path -LiteralPath $destination -PathType Container) { throw ('目标文件路径被目录占用：' + $entry.path) }
}
$backupParent = Join-Path $targetRoot 'data/backups'
Assert-NvrRealPath $backupParent
if ($ValidateOnly) { Write-Host ('更新包校验通过，发布文件 ' + $releaseFiles.Count + ' 个。'); return }

function Get-NvrRunningProcesses {
    @(Get-Process | ForEach-Object {
        try {
            $executable = $_.Path
            if ($executable -and $executable.StartsWith($targetRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { $_ }
        } catch {}
    })
}
function Copy-NvrReleaseFile([string]$Source, [string]$Destination) {
    Assert-NvrRealPath $Destination
    New-Item -ItemType Directory -Path (Split-Path -Parent $Destination) -Force | Out-Null
    if (Test-Path -LiteralPath $Destination -PathType Leaf) {
        $item = Get-Item -LiteralPath $Destination -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReadOnly) { $item.Attributes = $item.Attributes -band (-bnot [IO.FileAttributes]::ReadOnly) }
    }
    [IO.File]::Copy($Source, $Destination, $true)
}

Write-Host '正在请求录像机正常退出并保存当前录像，请稍候……'
foreach ($process in @(Get-NvrRunningProcesses)) {
    if ($process.MainWindowHandle -ne 0) { [void]$process.CloseMainWindow() }
}
$deadline = [DateTime]::UtcNow.AddSeconds($ShutdownTimeoutSeconds)
while (@(Get-NvrRunningProcesses).Count -gt 0 -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 250 }
if (@(Get-NvrRunningProcesses).Count -gt 0) { throw '录像机尚未完全退出，本次没有替换文件。请正常关闭录像机，等录像保存后再次运行更新。' }
# Recheck the package and paths after waiting; no configuration or recording files are copied.
$manifest = Read-NvrVerifiedManifest $packageRoot
$releaseFiles = @($manifest.files | Where-Object { Test-NvrReleaseFile $_.path })
Assert-NvrRealPath $backupParent
$backupRoot = Join-Path $backupParent ('update-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6))
New-Item -ItemType Directory -Path $backupRoot | Out-Null
$oldFiles = @{}
foreach ($entry in $releaseFiles) {
    $destination = Join-Path $targetRoot $entry.path
    Assert-NvrRealPath $destination
    if (Test-Path -LiteralPath $destination -PathType Leaf) {
        $oldFile = Get-Item -LiteralPath $destination -Force
        $oldFiles[$entry.path] = @{ hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash; attributes = [int]$oldFile.Attributes }
        $backupFile = Join-Path $backupRoot ('files/' + $entry.path)
        New-Item -ItemType Directory -Path (Split-Path -Parent $backupFile) -Force | Out-Null
        [IO.File]::Copy($destination, $backupFile, $false)
        if ((Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash -ne $oldFiles[$entry.path].hash) { throw '原程序备份校验失败，本次没有替换文件。' }
    }
}
$receipt = @{ packageCreated = $manifest.created; jarSha256 = (@($manifest.files | Where-Object path -eq 'app/home-monitor.jar')[0]).sha256; target = $targetRoot; status = 'backed-up'; files = @($oldFiles.Keys) }
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backupRoot '更新记录.json') -Encoding UTF8
$changedFiles = New-Object 'System.Collections.Generic.List[string]'
try {
    foreach ($entry in $releaseFiles) {
        $destination = Join-Path $targetRoot $entry.path
        $changedFiles.Add($entry.path)
        Copy-NvrReleaseFile (Join-Path $packageRoot $entry.path) $destination
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $entry.sha256) { throw ('复制后校验失败：' + $entry.path) }
    }
    if (!$NoStart) {
        $started = Start-Process -FilePath (Join-Path $targetRoot '录像机.exe') -WorkingDirectory $targetRoot -PassThru
        # jpackage can hand the application to a child process and exit its launcher.
        # Confirm the actual recorder window rather than the short-lived wrapper.
        $startupDeadline = [DateTime]::UtcNow.AddSeconds(20)
        $recorderWindow = $false
        do {
            Start-Sleep -Milliseconds 250
            $recorderWindow = @((Get-NvrRunningProcesses) | Where-Object {
                $_.MainWindowHandle -ne 0 -and [IO.Path]::GetFileName($_.Path) -eq '录像机.exe'
            }).Count -gt 0
            if ($recorderWindow) { break }
        } while ([DateTime]::UtcNow -lt $startupDeadline)
        if (!$recorderWindow) { throw '新版本窗口未能正常打开。' }
    }
    $receipt.status = 'complete'
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backupRoot '更新记录.json') -Encoding UTF8
} catch {
    $originalError = $_.Exception.Message
    $rollbackErrors = New-Object 'System.Collections.Generic.List[string]'
    if (@(Get-NvrRunningProcesses).Count -gt 0) {
        $rollbackErrors.Add('程序仍在运行，无法安全自动回滚；请正常关闭后从备份恢复发布文件。')
    } else {
        foreach ($relative in $changedFiles) {
            try {
                $destination = Join-Path $targetRoot $relative
                Assert-NvrRealPath $destination
                if ($oldFiles.ContainsKey($relative)) {
                    if (!(Test-Path -LiteralPath $destination -PathType Leaf) -or (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $oldFiles[$relative].hash) {
                        Copy-NvrReleaseFile (Join-Path $backupRoot ('files/' + $relative)) $destination
                    }
                    (Get-Item -LiteralPath $destination -Force).Attributes = [IO.FileAttributes]$oldFiles[$relative].attributes
                    if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $oldFiles[$relative].hash) { throw ('回滚校验失败：' + $relative) }
                } elseif (Test-Path -LiteralPath $destination -PathType Leaf) { Remove-Item -LiteralPath $destination -Force }
            } catch { $rollbackErrors.Add($_.Exception.Message) }
        }
    }
    $receipt.status = if ($rollbackErrors.Count -eq 0) { 'rolled-back' } else { 'needs-recovery' }
    $receipt.error = $originalError
    $receipt.rollbackErrors = $rollbackErrors.ToArray()
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $backupRoot '更新记录.json') -Encoding UTF8
    if ($rollbackErrors.Count -gt 0) { throw ('更新失败，配置和录像未改动。备份：' + $backupRoot + '。原因：' + $originalError + '。恢复情况：' + ($rollbackErrors -join '；')) }
    throw ('更新失败，已恢复原发布文件，配置和录像未改动。备份：' + $backupRoot + '。原因：' + $originalError)
}
Write-Host ('更新完成：' + $targetRoot)
Write-Host '摄像头、录像目录、容量限制、用户、登录与证书配对均保留。'
Write-Host ('原发布文件备份：' + $backupRoot)

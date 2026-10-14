param([string]$JdkPath = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$JdkPath) {
    $compiler = Get-Command javac.exe -ErrorAction Stop
    $JdkPath = Split-Path (Split-Path $compiler.Source -Parent) -Parent
}
$iconClasses = Join-Path $projectRoot ('build/icon-generator-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $iconClasses -Force | Out-Null
& (Join-Path $JdkPath 'bin/javac.exe') --release 21 -encoding UTF-8 -d $iconClasses `
    (Join-Path $projectRoot 'desktop/src/main/java/cn/homewatch/AppIdentity.java') (Join-Path $PSScriptRoot 'GenerateAppIcon.java')
if ($LASTEXITCODE -ne 0) { throw '图标生成器编译失败。' }
& (Join-Path $JdkPath 'bin/java.exe') '-Djava.awt.headless=true' -cp $iconClasses GenerateAppIcon `
    (Join-Path $projectRoot 'desktop/assets') (Join-Path $projectRoot 'desktop/src/main/resources')
if ($LASTEXITCODE -ne 0) { throw '图标生成失败。' }
Write-Host '已生成 Windows ICO 和 7 种窗口图标 PNG。'

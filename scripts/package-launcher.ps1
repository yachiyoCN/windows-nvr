param(
    [string]$JdkPath,
    [Parameter(Mandatory = $true)][string]$RuntimeImage,
    [string]$BuildDirectory,
    [string]$Destination
)
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$buildArguments = @{
    RuntimeImage = $RuntimeImage
    LauncherOnly = $true
}
if ($JdkPath) { $buildArguments.JdkPath = $JdkPath }
if ($BuildDirectory) { $buildArguments.BuildDirectory = $BuildDirectory }
if ($Destination) { $buildArguments.Destination = $Destination }
& (Join-Path $projectRoot 'build.ps1') @buildArguments

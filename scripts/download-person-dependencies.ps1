param([string]$Destination)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($Destination)) {
    $Destination = Join-Path $projectRoot 'tools/person-detection'
} elseif (![System.IO.Path]::IsPathRooted($Destination)) {
    $Destination = Join-Path $projectRoot $Destination
}
$dependencyRoot = [System.IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path $dependencyRoot -Force | Out-Null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$downloads = @(
    @{
        Name = 'yolox_tiny.onnx'
        Url = 'https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_tiny.onnx'
        Sha256 = '427cc366d34e27ff7a03e2899b5e3671425c262ea2291f88bb942bc1cc70b0f7'
    },
    @{
        Name = 'onnxruntime-1.20.0.jar'
        Url = 'https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.20.0/onnxruntime-1.20.0.jar'
        Sha256 = 'c46608681692b3693914defb42fa3119ad7cb6146870581ece247e0ed5793fe8'
    }
)
foreach ($download in $downloads) {
    $target = Join-Path $dependencyRoot $download.Name
    if ((Test-Path -LiteralPath $target -PathType Leaf) -and
        (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $download.Sha256) {
        Write-Host ($download.Name + ' 已存在，校验通过。')
        continue
    }
    $temporary = $target + '.download-' + [Guid]::NewGuid().ToString('N')
    try {
        Write-Host ('正在下载 ' + $download.Name + '…')
        Invoke-WebRequest -Uri $download.Url -OutFile $temporary -UseBasicParsing
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne $download.Sha256) {
            throw ('校验失败：' + $download.Name)
        }
        Move-Item -LiteralPath $temporary -Destination $target -Force
    } finally {
        if (Test-Path -LiteralPath $temporary -PathType Leaf) { Remove-Item -LiteralPath $temporary }
    }
}
foreach ($notice in @('LICENSE-YOLOX.txt', 'LICENSE-ONNXRuntime.txt', 'NOTICE.txt')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('third_party/person-detection/' + $notice)) `
        -Destination (Join-Path $dependencyRoot $notice) -Force
}
Write-Host '人员识别依赖准备完成。'

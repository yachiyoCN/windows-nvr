$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-NvrAbsoluteDirectory([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw '目录不能为空。' }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    if ($full.Length -le 3 -or $full.StartsWith('\\') -or $full.IndexOf(':', 2) -ge 0) {
        throw '请使用本机非根目录，不能使用网络路径。'
    }
    return $full
}

function Assert-NvrRealPath([string]$Path) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($current) {
        if (Test-Path -LiteralPath $current) {
            if ((Get-Item -LiteralPath $current -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw ('更新路径不能含有符号链接或目录联接：' + $current)
            }
        }
        $parent = Split-Path -Parent $current
        if (!$parent -or $parent -eq $current) { break }
        $current = $parent
    }
}

function Get-NvrRelativePath([string]$Root, [string]$FullPath) {
    $prefix = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/') + '\'
    $full = [IO.Path]::GetFullPath($FullPath)
    if (!$full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw '文件超出了预定目录。' }
    return $full.Substring($prefix.Length).Replace('\', '/')
}

function Assert-NvrRelativePath([string]$Relative) {
    if (!$Relative -or $Relative -match '[\\:\x00-\x1f]' -or $Relative.StartsWith('/') -or
        $Relative -match '(^|/)(\.|\.\.|)(/|$)' -or $Relative -match '(^|/)[^/]*[ .](/|$)') {
        throw ('更新包包含不安全的路径：' + $Relative)
    }
    foreach ($part in $Relative.Split('/')) {
        if ($part -match '^(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$') { throw '更新路径不能含有 Windows 保留设备名。' }
    }
}

function Test-NvrReleaseFile([string]$Relative) {
    Assert-NvrRelativePath $Relative
    if ($Relative -in @('LICENSE', 'NOTICE', '录像机.exe', 'app/home-monitor.jar', 'app/录像机.cfg', '使用说明.md',
        '手机摄像头接入说明.md', '加密连接与登录说明.md', '更新说明.md', '网页使用说明.md')) { return $true }
    if ($Relative -match '^tools/(ffmpeg\.exe|ffplay\.exe|ffprobe\.exe|StorageGuard\.exe|LICENSE|README\.txt|下载校验\.sha256)$') { return $true }
    if ($Relative -match '^tools/person-detection/(onnxruntime-1\.20\.0\.jar|yolox_tiny\.onnx|LICENSE-ONNXRuntime\.txt|LICENSE-YOLOX\.txt|NOTICE\.txt|native/onnxruntime\.dll|native/onnxruntime4j_jni\.dll)$') { return $true }
    if ($Relative -match '^安卓APP/(录像机\.apk|安卓使用说明\.md|用户权限说明\.md|加密连接与登录说明\.md|开始使用\.md|API\.md|openapi\.yaml)$') { return $true }
    if ($Relative -eq 'runtime/release') { return $true }
    if ($Relative -match '^runtime/bin/(server/)?[A-Za-z0-9_-]+\.(dll|exe)$') { return $true }
    if ($Relative -match '^runtime/legal/[A-Za-z0-9.]+/(LICENSE|ADDITIONAL_LICENSE_INFO|ASSEMBLY_EXCEPTION|[A-Za-z0-9_.-]+\.(md|txt))$') { return $true }
    if ($Relative -match '^runtime/conf/(jaxp\.properties|logging\.properties|net\.properties|sound\.properties|security/java\.policy|security/java\.security|security/policy/README\.txt|security/policy/(limited|unlimited)/(default_local\.policy|default_US_export\.policy|exempt_local\.policy))$') { return $true }
    if ($Relative -match '^runtime/lib/(classlist|fontconfig\.bfc|fontconfig\.properties\.src|jawt\.lib|jrt-fs\.jar|jvm\.cfg|jvm\.lib|modules|psfont\.properties\.ja|psfontj2d\.properties|tzdb\.dat|tzmappings|security/blocked\.certs|security/cacerts|security/default\.policy|security/public_suffix_list\.dat)$') { return $true }
    return $false
}

function Test-NvrPackageSupport([string]$Relative) {
    return $Relative -in @('更新.cmd', '更新工具/安装更新.ps1', '更新工具/更新包校验.ps1')
}

function Read-NvrVerifiedManifest([string]$PackageRoot) {
    Assert-NvrRealPath $PackageRoot
    $manifestPath = Join-Path $PackageRoot '更新清单.json'
    if (!(Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw '缺少更新清单。' }
    $manifestFile = Get-Item -LiteralPath $manifestPath
    if ($manifestFile.Length -gt 2MB) { throw '更新清单大小异常。' }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.format -ne 1 -or $manifest.product -ne 'homewatch-nvr' -or !$manifest.files) { throw '更新包格式不受支持。' }
    $listed = @{}
    foreach ($entry in @($manifest.files)) {
        if ($entry.path -isnot [string]) { throw '更新清单路径类型错误。' }
        $relative = [string]$entry.path
        Assert-NvrRelativePath $relative
        if (!(Test-NvrReleaseFile $relative) -and !(Test-NvrPackageSupport $relative)) { throw ('更新清单含有非发布文件：' + $relative) }
        if ($listed.ContainsKey($relative)) { throw '更新清单存在重复路径。' }
        if ($entry.sha256 -notmatch '^[A-Fa-f0-9]{64}$' -or $entry.length -lt 0) { throw '更新清单校验值错误。' }
        $listed[$relative] = $entry
    }
    if ($listed.Count -gt 4096) { throw '更新文件数量异常。' }
    foreach ($required in @('录像机.exe', 'app/home-monitor.jar', 'app/录像机.cfg', 'runtime/bin/javaw.exe', 'runtime/bin/java.exe', 'runtime/lib/modules', 'tools/ffmpeg.exe', 'tools/ffprobe.exe', 'tools/StorageGuard.exe', '更新.cmd', '更新工具/安装更新.ps1', '更新工具/更新包校验.ps1', '更新说明.md')) {
        if (!$listed.ContainsKey($required)) { throw ('更新包缺少必要文件：' + $required) }
    }
    $allowedDirectories = @{}
    foreach ($relative in $listed.Keys) {
        $parts = $relative.Split('/')
        for ($i = 1; $i -lt $parts.Length; $i++) { $allowedDirectories[($parts[0..($i - 1)] -join '/')] = $true }
    }
    $foundFiles = 0
    foreach ($item in @(Get-ChildItem -LiteralPath $PackageRoot -Force -Recurse)) {
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '更新包不能含有符号链接或目录联接。' }
        $relative = Get-NvrRelativePath $PackageRoot $item.FullName
        Assert-NvrRelativePath $relative
        if ($item.PSIsContainer) {
            if (!$allowedDirectories.ContainsKey($relative)) { throw ('更新包含有非发布目录：' + $relative) }
            continue
        }
        if ($relative -eq '更新清单.json') { continue }
        if (!$listed.ContainsKey($relative)) { throw ('更新包含有未登记文件：' + $relative) }
        $expected = $listed[$relative]
        if ($item.Length -ne $expected.length -or (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash -ne $expected.sha256) {
            throw ('更新包完整性校验失败：' + $relative)
        }
        $foundFiles++
    }
    if ($foundFiles -ne $listed.Count) { throw '更新包有文件缺失。' }
    return $manifest
}

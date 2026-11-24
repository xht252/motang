# 校验 .so 的 16KB page size 对齐（LOAD 段 align >= 0x4000）。
#
# 用法：
#   powershell -File .kilo/scripts/check-16k-pagesize.ps1 -SoPath <path-to-libmotang_ffi.so>
#   powershell -File .kilo/scripts/check-16k-pagesize.ps1 -ApkPath <path-to.apk>
#
# 退出码：0 全部通过；1 存在未对齐或无法检查的 .so。

[CmdletBinding(DefaultParameterSetName = "So")]
param(
    [Parameter(ParameterSetName = "So", Mandatory = $true)]
    [string]$SoPath,

    [Parameter(ParameterSetName = "Apk", Mandatory = $true)]
    [string]$ApkPath,

    [string]$NdkHome = $(if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { "D:\SDK\ndk\28.2.13676358" }),

    # 只校验匹配的文件名（APK 模式下含第三方 native 库，如 JNA 的 libjnidispatch.so）。
    # 默认只校验本项目产物，避免第三方二进制阻断 M0。
    [string]$NameFilter = "libmotang_ffi.so",

    # 要求的最小对齐（16KB = 0x4000）。
    [int]$MinAlign = 0x4000,

    # 仅警告、不返回非零退出码（M6 起用于非阻断提示）。
    [switch]$WarnOnly
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

$readelf = Join-Path $NdkHome "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
if (-not (Test-Path $readelf)) {
    $msg = "找不到 llvm-readelf：$readelf。请设置 -NdkHome 或 ANDROID_NDK_HOME。"
    if ($WarnOnly) { Write-Warning $msg; exit 0 } else { Write-Error $msg; exit 1 }
}

function Get-SoFiles {
    param([string]$Path)

    if ($Path -like "*.apk") {
        if (-not (Test-Path $Path)) { throw "APK 不存在：$Path" }
        # APK 内 .so 是 stored 的（AGP useLegacyPackaging=false 时仍以 store 方式对齐），
        # 直接解压到临时目录再检查。
        $temp = Join-Path $env:TEMP ("motang-16k-" + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Path $temp -Force | Out-Null
        $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $Path))
        try {
            foreach ($entry in $zip.Entries) {
                if ($entry.FullName -like "lib/*/*.so") {
                    $dest = Join-Path $temp ($entry.FullName -replace '/', '\')
                    New-Item -ItemType Directory -Path (Split-Path $dest) -Force | Out-Null
                    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
                }
            }
        } finally {
            $zip.Dispose()
        }
        return @(Get-ChildItem -Path $temp -Recurse -Filter *.so)
    }

    if (-not (Test-Path $Path)) { throw "文件不存在：$Path" }
    if ((Get-Item $Path).PSIsContainer) {
        return @(Get-ChildItem -Path $Path -Recurse -Filter *.so)
    }
    return @(Get-Item $Path)
}

function Test-SoAlignment {
    param([System.IO.FileInfo]$So)

    $output = & $readelf -lW $So.FullName 2>&1
    if ($LASTEXITCODE -ne 0) {
        return [pscustomobject]@{
            Path    = $So.FullName
            Aligns  = @()
            Ok      = $false
            Reason  = "llvm-readelf 执行失败"
        }
    }

    # 形如：  LOAD  0x000000 0x00000000 0x00000000 0x001234 0x001234 R E 0x4000
    $aligns = @()
    foreach ($line in $output) {
        if ($line -match '^\s*LOAD\s+.*?\s(0x[0-9a-fA-F]+)\s*$') {
            $aligns += [Convert]::ToInt64($Matches[1], 16)
        }
    }

    if ($aligns.Count -eq 0) {
        return [pscustomobject]@{
            Path   = $So.FullName
            Aligns = @()
            Ok     = $false
            Reason = "未找到 LOAD 段"
        }
    }

    $bad = @($aligns | Where-Object { $_ -lt $MinAlign })
    return [pscustomobject]@{
        Path   = $So.FullName
        Aligns = $aligns
        Ok     = ($bad.Count -eq 0)
        Reason = if ($bad.Count -eq 0) { "" } else { "存在 align < 0x$($MinAlign.ToString('X')) 的 LOAD 段" }
    }
}

$target = if ($PSCmdlet.ParameterSetName -eq "Apk") { $ApkPath } else { $SoPath }
$all = Get-SoFiles -Path $target
$files = if ($NameFilter) { @($all | Where-Object { $_.Name -eq $NameFilter }) } else { @($all) }

if ($files.Count -eq 0) {
    $names = ($all | ForEach-Object { $_.Name }) -join ", "
    Write-Error "未找到匹配 '$NameFilter' 的 .so（实际：$names）"
    if (-not $WarnOnly) { exit 1 } else { exit 0 }
}

$results = $files | ForEach-Object { Test-SoAlignment -So $_ }

foreach ($r in $results) {
    $alignText = ($r.Aligns | ForEach-Object { "0x{0:X}" -f $_ }) -join ", "
    $status = if ($r.Ok) { "PASS" } else { "FAIL" }
    Write-Host ("[{0}] {1}" -f $status, $r.Path)
    Write-Host ("         LOAD align: {0}" -f $alignText)
    if (-not $r.Ok) { Write-Host ("         {0}" -f $r.Reason) -ForegroundColor Yellow }
}

$failed = @($results | Where-Object { -not $_.Ok })
if ($failed.Count -gt 0) {
    if ($WarnOnly) {
        Write-Warning "16KB 对齐未通过（WarnOnly，不阻断）：$($failed.Count) 个文件"
        exit 0
    }
    Write-Error "16KB 对齐未通过：$($failed.Count) 个文件"
    exit 1
}

Write-Host "全部 .so 满足 16KB 对齐。" -ForegroundColor Green
exit 0

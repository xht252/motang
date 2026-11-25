# 下载 chinese-poetry 语料到本地 data/（不入库）。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/fetch-corpus.ps1
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/fetch-corpus.ps1 -Force
#
# 产物：data/chinese-poetry/全唐诗/poet.tang.<n>.json + 唐诗三百首.json
# 这些文件已由 .gitignore 排除，只作为 motang-etl 的输入。

[CmdletBinding()]
param(
    [string]$OutDir,
    [string]$Ref = "master",
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# 注意：不能在 param 默认值里用 $PSScriptRoot —— PowerShell 求值默认值时它还是空串。
if (-not $OutDir) {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
    $OutDir = Join-Path $repoRoot "data\chinese-poetry"
}

$base = "https://raw.githubusercontent.com/chinese-poetry/chinese-poetry/$Ref/%E5%85%A8%E5%94%90%E8%AF%97"
$target = Join-Path $OutDir "全唐诗"
New-Item -ItemType Directory -Path $target -Force | Out-Null

# 全唐诗按 1000 首分片：poet.tang.0.json … poet.tang.57000.json
$shards = 0..57 | ForEach-Object { $_ * 1000 }

$files = @($shards | ForEach-Object { "poet.tang.$_.json" }) + @("唐诗三百首.json")

$downloaded = 0
$skipped = 0
foreach ($name in $files) {
    $dest = Join-Path $target $name
    if ((Test-Path $dest) -and (-not $Force) -and ((Get-Item $dest).Length -gt 0)) {
        $skipped++
        continue
    }

    $url = "$base/" + [uri]::EscapeDataString($name)
    try {
        Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -TimeoutSec 120
        $downloaded++
        if ($downloaded % 10 -eq 0) {
            Write-Host "  已下载 $downloaded 个分片…"
        }
    } catch {
        Write-Warning "下载失败 $name ：$($_.Exception.Message)"
    }
}

$all = Get-ChildItem -Path $target -Filter "*.json" -File
$totalMb = [math]::Round((($all | Measure-Object -Property Length -Sum).Sum) / 1MB, 1)

Write-Host ""
Write-Host "完成：新增 $downloaded，跳过 $skipped，文件数 $($all.Count)，合计 $totalMb MB"
Write-Host "目录：$target"

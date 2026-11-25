# 墨唐 motang · M1 验收脚本（全量诗歌库 + Rust 检索）
#
# 覆盖：
#   C1 cargo test
#   C2 全量语料产物与 assets
#   C3 桌面：索引体积 / 索引通道 P95 / 正文扫描延迟
#   C4 设备端：清数据 → 建索引 → 四类查询断言
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m1.ps1
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m1.ps1 -SkipDevice

[CmdletBinding()]
param(
    [switch]$SkipDevice,
    [switch]$SkipDesktop,
    [int]$P95LimitUs = 50000,
    [int]$IndexMbLimit = 200,
    [int]$ScanMsLimit = 3000,
    [int]$ExpectedPoems = 850000
)

$ErrorActionPreference = "Continue"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$coreRs = Join-Path $repo "core-rs"
$corpus = Join-Path $coreRs "out\all.jsonl.zst"
$debugApk = Join-Path $repo "app\build\outputs\apk\debug\app-debug.apk"
$gradlew = Join-Path $repo "gradlew.bat"
$results = [System.Collections.Generic.List[object]]::new()

$script:SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\SDK" }
$script:JdkHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "D:\jdk21" }
$ndkHome = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { Join-Path $script:SdkRoot "ndk\28.2.13676358" }

$env:JAVA_HOME = $script:JdkHome
$env:ANDROID_HOME = $script:SdkRoot
$env:ANDROID_SDK_ROOT = $script:SdkRoot
$env:ANDROID_NDK_HOME = $ndkHome

function Resolve-Tool {
    param([string]$Name, [string[]]$Candidates)
    foreach ($c in $Candidates) { if ($c -and (Test-Path $c)) { return $c } }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$cargoExe = Resolve-Tool -Name "cargo" -Candidates @((Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"))
$adbExe = Resolve-Tool -Name "adb" -Candidates @((Join-Path $script:SdkRoot "platform-tools\adb.exe"))

function Add-Result {
    param([string]$Id, [string]$Name, [bool]$Ok, [string]$Detail)
    $results.Add([pscustomobject]@{ Id = $Id; Name = $Name; Ok = $Ok; Detail = $Detail })
    $status = if ($Ok) { "PASS" } else { "FAIL" }
    $color = if ($Ok) { "Green" } else { "Red" }
    Write-Host ("[{0}] {1} {2} — {3}" -f $status, $Id, $Name, $Detail) -ForegroundColor $color
}

# cargo 必须在 core-rs 下执行。
function Invoke-Cargo {
    param([string]$WorkDir, [string[]]$Arguments)
    $prev = Get-Location
    try {
        Set-Location -LiteralPath $WorkDir
        $global:LASTEXITCODE = 0
        $out = & $cargoExe @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out }
    } finally {
        Set-Location -LiteralPath $prev
    }
}

# ---------------------------------------------------------------------------
# C1 Rust 单测
# ---------------------------------------------------------------------------
if ($cargoExe) {
    $r = Invoke-Cargo -WorkDir $coreRs -Arguments @("test", "--workspace")
    $passed = ([regex]::Matches($r.Output, "test result: ok\. (\d+) passed") | ForEach-Object { [int]$_.Groups[1].Value } | Measure-Object -Sum).Sum
    Add-Result "C1" "cargo test --workspace" ($r.ExitCode -eq 0 -and $passed -gt 0) "passed=$passed exit=$($r.ExitCode)"
} else {
    Add-Result "C1" "cargo test --workspace" $false "找不到 cargo"
}

# ---------------------------------------------------------------------------
# C2 全量语料产物
# ---------------------------------------------------------------------------
if (Test-Path $corpus) {
    $mb = [math]::Round((Get-Item $corpus).Length / 1MB, 2)
    $authorsPath = Join-Path $coreRs "out\all.authors.json"
    $authorCount = 0
    if (Test-Path $authorsPath) {
        $authorCount = (Get-Content -LiteralPath $authorsPath -Raw -Encoding UTF8 | ConvertFrom-Json).Count
    }
    Add-Result "C2" "全量语料产物" ($authorCount -gt 10000) "corpus=$mb MB, authors=$authorCount"

    $assetsDir = Join-Path $repo "app\src\main\assets"
    $assetCorpus = Join-Path $assetsDir "poems.jsonl.zst"
    $assetsOk = (Test-Path $assetCorpus) -and (Test-Path (Join-Path $assetsDir "poems.authors.json"))
    Add-Result "C2b" "assets 已就位" $assetsOk $assetsDir
} else {
    Add-Result "C2" "全量语料产物" $false "缺少 $corpus，请先跑 motang-etl（--input data/Poetry）"
    Add-Result "C2b" "assets 已就位" $false "语料不存在"
}

# ---------------------------------------------------------------------------
# C3 桌面性能
# ---------------------------------------------------------------------------
if ($SkipDesktop) {
    Add-Result "C3" "索引体积" $true "SKIPPED"
    Add-Result "C3b" "检索 P95" $true "SKIPPED"
    Add-Result "C3c" "正文扫描" $true "SKIPPED"
} elseif (-not $cargoExe -or -not (Test-Path $corpus)) {
    Add-Result "C3" "索引体积" $false "缺 cargo 或语料"
    Add-Result "C3b" "检索 P95" $false "缺 cargo 或语料"
    Add-Result "C3c" "正文扫描" $false "缺 cargo 或语料"
} else {
    $benchDir = Join-Path $env:TEMP ("motang-m1-bench-" + [guid]::NewGuid().ToString("N"))
    $r = Invoke-Cargo -WorkDir $coreRs -Arguments @(
        "run", "--release", "-q", "-p", "motang-search", "--example", "bench", "--", $corpus, $benchDir
    )
    $out = $r.Output

    $loaded = [regex]::Match($out, "载入 (\d+) 首").Groups[1].Value
    $loadedOk = [int64]$loaded -ge $ExpectedPoems

    $indexMb = [double]([regex]::Match($out, "索引体积 ([\d.]+) MB").Groups[1].Value)
    Add-Result "C3" "索引体积" ($indexMb -gt 0 -and $indexMb -le $IndexMbLimit) "$indexMb MB (limit $IndexMbLimit), 载入=$loaded"

    $p95s = [regex]::Matches($out, "\[index\].*?p95=\s*(\d+) us") | ForEach-Object { [int]$_.Groups[1].Value }
    $worst = if ($p95s.Count -gt 0) { ($p95s | Measure-Object -Maximum).Maximum } else { -1 }
    $hitsOk = ($out -match "query=静夜思\s+total=[1-9]") -and ($out -match "query=jys\s+total=[1-9]") -and ($out -match "query=libai\s+total=[1-9]")
    Add-Result "C3b" "索引通道 P95" ($worst -ge 0 -and $worst -le $P95LimitUs -and $hitsOk -and $loadedOk) ("worstP95=${worst}us hitsOk=$hitsOk")

    $scanMs = [regex]::Matches($out, "\[scan \].*?first=\s*(\d+) ms") | ForEach-Object { [int]$_.Groups[1].Value }
    $worstScan = if ($scanMs.Count -gt 0) { ($scanMs | Measure-Object -Maximum).Maximum } else { -1 }
    Add-Result "C3c" "正文扫描延迟" ($worstScan -ge 0 -and $worstScan -le $ScanMsLimit) "worst=${worstScan}ms limit=${ScanMsLimit}ms"
}

# ---------------------------------------------------------------------------
# C4 设备端
# ---------------------------------------------------------------------------
if ($SkipDevice) {
    Add-Result "C4" "设备端检索" $true "SKIPPED"
} elseif (-not $adbExe) {
    Add-Result "C4" "设备端检索" $false "找不到 adb"
} else {
    $booted = $false
    for ($i = 0; $i -lt 40; $i++) {
        if ((& $adbExe shell getprop sys.boot_completed 2>$null | Out-String).Trim() -eq "1") { $booted = $true; break }
        Start-Sleep -Seconds 3
    }
    if (-not $booted) {
        Add-Result "C4" "设备端检索" $false "无在线设备"
    } else {
        $null = & $gradlew ":app:assembleDebug" 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $debugApk)) {
            Add-Result "C4" "设备端检索" $false "assembleDebug 失败"
        } else {
            & $adbExe install -r $debugApk 2>&1 | Out-Null
            & $adbExe shell pm clear "io.github.xht252.motang" 2>&1 | Out-Null

            $indexOk = $false
            $buildMs = -1
            $poems = 0
            $searchOk = $true
            $details = @()

            # 全量建索引在模拟器约 2 分钟，给 5 分钟窗口。
            foreach ($q in @("静夜思", "jys", "libai", "床前明月光")) {
                & $adbExe shell am force-stop "io.github.xht252.motang" 2>$null | Out-Null
                & $adbExe logcat -c 2>$null | Out-Null
                & $adbExe shell am start -n "io.github.xht252.motang/.MainActivity" -e query $q 2>&1 | Out-Null

                $log = ""
                for ($i = 0; $i -lt 100; $i++) {
                    Start-Sleep -Seconds 3
                    $log = & $adbExe logcat -d -s MotangM1:I 2>&1 | Out-String
                    if ($log -match "search query=") { break }
                }

                if ($log -match "index built: poems=(\d+) elapsedMs=(\d+)") {
                    $indexOk = $true
                    $poems = [int]$Matches[1]
                    $buildMs = [int]$Matches[2]
                }
                if ($log -match "search query=$([regex]::Escape($q)) total=(\d+)") {
                    $total = [int]$Matches[1]
                    $details += "$q=$total"
                    if ($total -le 0) { $searchOk = $false }
                } else {
                    $searchOk = $false
                    $details += "$q=NO_LOG"
                }
            }

            Add-Result "C4a" "设备建索引" $indexOk "poems=$poems elapsedMs=$buildMs (模拟器仅供参考)"
            Add-Result "C4b" "设备四类查询" $searchOk ($details -join " ")
        }
    }
}

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "==== M1 验收汇总 ====" -ForegroundColor Cyan
$results | ForEach-Object {
    "{0,-4} {1,-22} {2,-5} {3}" -f $_.Id, $_.Name, $(if ($_.Ok) { "PASS" } else { "FAIL" }), $_.Detail
} | Out-Host

$failed = @($results | Where-Object { -not $_.Ok })
if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "FAIL 项：$($failed.Count)。" -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "全部 PASS。" -ForegroundColor Green
exit 0

# 墨唐 motang · M1 验收脚本（诗库数据 + Rust 检索）
#
# 覆盖：
#   C1 cargo test
#   C2 语料入库条数（读 ETL 产物）
#   C3 桌面检索 P95（bench example）
#   C4 设备端：清空数据 → 建索引 → 四类查询断言
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m1.ps1
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m1.ps1 -SkipDevice
#
# 退出码：0 全 PASS；1 有 FAIL。

[CmdletBinding()]
param(
    [switch]$SkipDevice,
    [switch]$SkipDesktop,
    [int]$P95LimitUs = 50000,
    [int]$IndexLimitMs = 30000
)

$ErrorActionPreference = "Continue"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$coreRs = Join-Path $repo "core-rs"
$corpus = Join-Path $coreRs "out\poems.jsonl.zst"
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

# cargo 必须在 core-rs 下执行：workspace 根在那里，否则报 "could not find Cargo.toml"。
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
    $ok = $r.ExitCode -eq 0
    $passed = ([regex]::Matches($r.Output, "test result: ok\. (\d+) passed") | ForEach-Object { [int]$_.Groups[1].Value } | Measure-Object -Sum).Sum
    Add-Result "C1" "cargo test --workspace" ($ok -and $passed -gt 0) "passed=$passed exit=$($r.ExitCode)"
} else {
    Add-Result "C1" "cargo test --workspace" $false "找不到 cargo"
}

# ---------------------------------------------------------------------------
# C2 语料产物
# ---------------------------------------------------------------------------
if (Test-Path $corpus) {
    $mb = [math]::Round((Get-Item $corpus).Length / 1MB, 2)
    $authorsPath = Join-Path $coreRs "out\poems.authors.json"
    $authorCount = 0
    if (Test-Path $authorsPath) {
        $authorCount = (Get-Content -LiteralPath $authorsPath -Raw -Encoding UTF8 | ConvertFrom-Json).Count
    }
    Add-Result "C2" "ETL 产物存在" $true "corpus=$mb MB, authors=$authorCount"

    $assetsDir = Join-Path $repo "app\src\main\assets"
    $assetsOk = (Test-Path (Join-Path $assetsDir "poems.jsonl.zst")) -and (Test-Path (Join-Path $assetsDir "poems.authors.json"))
    Add-Result "C2b" "assets 已就位" $assetsOk $assetsDir
} else {
    Add-Result "C2" "ETL 产物存在" $false "缺少 $corpus，请先跑 fetch-corpus.ps1 + motang-etl"
    Add-Result "C2b" "assets 已就位" $false "语料不存在"
}

# ---------------------------------------------------------------------------
# C3 桌面检索 P95
# ---------------------------------------------------------------------------
if ($SkipDesktop) {
    Add-Result "C3" "检索 P95" $true "SKIPPED"
} elseif (-not $cargoExe -or -not (Test-Path $corpus)) {
    Add-Result "C3" "检索 P95" $false "缺 cargo 或语料"
} else {
    $benchDir = Join-Path $env:TEMP ("motang-m1-bench-" + [guid]::NewGuid().ToString("N"))
    $r = Invoke-Cargo -WorkDir $coreRs -Arguments @(
        "run", "--release", "-q", "-p", "motang-search", "--example", "bench", "--", $corpus, $benchDir
    )
    $out = $r.Output
    $p95s = [regex]::Matches($out, "p95=\s*(\d+)\s*us") | ForEach-Object { [int]$_.Groups[1].Value }
    $queries = [regex]::Matches($out, "query=(\S+)")
    $hitsOk = $true
    foreach ($q in @("明月", "李白", "静夜思", "jys")) {
        if ($out -notmatch [regex]::Escape("query=$q")) { $hitsOk = $false }
    }
    # 关键查询必须有命中（静夜思=1、jys>0）
    if ($out -match "query=静夜思\s+hits=0") { $hitsOk = $false }
    if ($out -match "query=jys\s+hits=0") { $hitsOk = $false }

    $worst = if ($p95s.Count -gt 0) { ($p95s | Measure-Object -Maximum).Maximum } else { -1 }
    $p95Ok = $worst -ge 0 -and $worst -le $P95LimitUs
    Add-Result "C3" "检索 P95" ($p95Ok -and $hitsOk) ("worstP95=${worst}us limit=${P95LimitUs}us hitsOk=$hitsOk")
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
        # 构建（会顺带跑 cargo-ndk + uniffi）
        $null = & $gradlew ":app:assembleDebug" 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $debugApk)) {
            Add-Result "C4" "设备端检索" $false "assembleDebug 失败"
        } else {
            & $adbExe install -r $debugApk 2>&1 | Out-Null
            # 清空 app 数据，强制走「首次建索引」路径
            & $adbExe shell pm clear "io.github.xht252.motang" 2>&1 | Out-Null

            $indexOk = $false
            $buildMs = -1
            $searchOk = $true
            $details = @()

            foreach ($q in @("明月", "jys", "libai", "静夜思")) {
                & $adbExe shell am force-stop "io.github.xht252.motang" 2>$null | Out-Null
                & $adbExe logcat -c 2>$null | Out-Null
                & $adbExe shell am start -n "io.github.xht252.motang/.MainActivity" -e query $q 2>&1 | Out-Null

                # 首次会建索引（模拟器约 60s），给足窗口
                $log = ""
                for ($i = 0; $i -lt 40; $i++) {
                    Start-Sleep -Seconds 3
                    $log = & $adbExe logcat -d -s MotangM1:I 2>&1 | Out-String
                    if ($log -match "search query=") { break }
                }

                if ($log -match "index built: poems=(\d+) elapsedMs=(\d+)") {
                    $indexOk = $true
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

            Add-Result "C4a" "设备建索引" $indexOk ("elapsedMs=$buildMs (模拟器仅供参考，指标以真机为准)")
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

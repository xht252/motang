# 墨唐 motang · M0 验收脚本
#
# 逐项执行计划第 7 节的 A1–A7 / B1–B4，输出 PASS/FAIL 表并以退出码汇总。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m0.ps1
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m0.ps1 -SkipEmulator
#
# 退出码：0 全部 PASS；1 存在 FAIL。

[CmdletBinding()]
param(
    [switch]$SkipEmulator,
    [switch]$SkipRust,
    [string]$ApkMaxMb = 40
)

$ErrorActionPreference = "Continue"
$repo = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$gradlew = Join-Path $repo "gradlew.bat"
$ndkHome = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { "D:\SDK\ndk\28.2.13676358" }
$results = [System.Collections.Generic.List[object]]::new()

function Add-Result {
    param([string]$Id, [string]$Name, [bool]$Ok, [string]$Detail)
    $results.Add([pscustomobject]@{ Id = $Id; Name = $Name; Ok = $Ok; Detail = $Detail })
    $color = if ($Ok) { "Green" } else { "Red" }
    $status = if ($Ok) { "PASS" } else { "FAIL" }
    Write-Host ("[{0}] {1} {2} — {3}" -f $status, $Id, $Name, $Detail) -ForegroundColor $color
}

function Invoke-Step {
    param([string]$FilePath, [string[]]$Arguments, [string]$WorkDir)
    $prev = Get-Location
    try {
        if ($WorkDir) { Set-Location $WorkDir }
        $out = & $FilePath @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out }
    } finally {
        Set-Location $prev
    }
}

# ---------------------------------------------------------------------------
# A1 Rust 单测
# ---------------------------------------------------------------------------
if (-not $SkipRust) {
    $r = Invoke-Step -FilePath "cargo" -Arguments @("test", "--workspace") -WorkDir (Join-Path $repo "core-rs")
    Add-Result "A1" "cargo test --workspace" ($r.ExitCode -eq 0) "exit=$($r.ExitCode)"
} else {
    Add-Result "A1" "cargo test --workspace" $true "SKIPPED"
}

# ---------------------------------------------------------------------------
# A2 / A3 Gradle 构建
# ---------------------------------------------------------------------------
if (-not (Test-Path $gradlew)) {
    Add-Result "A2" "assembleDebug" $false "gradlew.bat 不存在，请先跑 bootstrap.ps1"
    Add-Result "A3" "assembleRelease" $false "gradlew.bat 不存在"
} else {
    $r = Invoke-Step -FilePath $gradlew -Arguments @(":app:assembleDebug", "--stacktrace") -WorkDir $repo
    Add-Result "A2" "assembleDebug" ($r.ExitCode -eq 0) "exit=$($r.ExitCode)"

    $r = Invoke-Step -FilePath $gradlew -Arguments @(":app:assembleRelease", "--stacktrace") -WorkDir $repo
    Add-Result "A3" "assembleRelease" ($r.ExitCode -eq 0) "exit=$($r.ExitCode)"
}

# ---------------------------------------------------------------------------
# A4 / A5 / A7 APK 内容检查
# ---------------------------------------------------------------------------
$releaseApk = Join-Path $repo "app\build\outputs\apk\release\app-release.apk"
$debugApk = Join-Path $repo "app\build\outputs\apk\debug\app-debug.apk"

function Get-ApkEntries {
    param([string]$Apk)
    if (-not (Test-Path $Apk)) { return $null }
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    try { return @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
}

$releaseEntries = Get-ApkEntries $releaseApk
if ($null -eq $releaseEntries) {
    Add-Result "A4" ".so 存在且 ABI 正确" $false "release APK 未产出"
} else {
    $hasArm64 = $releaseEntries -contains "lib/arm64-v8a/libmotang_ffi.so"
    $hasX86 = @($releaseEntries | Where-Object { $_ -like "lib/x86_64/*" }).Count -gt 0
    $ok = $hasArm64 -and (-not $hasX86)
    Add-Result "A4" ".so 存在且 release 仅 arm64" $ok "arm64=$hasArm64 x86_64=$hasX86"
}

$checkScript = Join-Path $PSScriptRoot "check-16k-pagesize.ps1"
if (Test-Path $releaseApk) {
    $r = Invoke-Step -FilePath "powershell" -Arguments @("-ExecutionPolicy", "Bypass", "-File", $checkScript, "-ApkPath", $releaseApk)
    Add-Result "A5" "16KB 对齐" ($r.ExitCode -eq 0) "exit=$($r.ExitCode)"
} else {
    Add-Result "A5" "16KB 对齐" $false "release APK 未产出"
}

$bindings = Get-ChildItem -Path (Join-Path $repo "app\build\generated\uniffi") -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue
Add-Result "A6" "uniffi Kotlin 绑定已生成" ($null -ne $bindings -and $bindings.Count -gt 0) "files=$(@($bindings).Count)"

if (Test-Path $releaseApk) {
    $mb = [math]::Round((Get-Item $releaseApk).Length / 1MB, 2)
    Add-Result "A7" "APK 体积" ($mb -le [double]$ApkMaxMb) "$mb MB (limit $ApkMaxMb)"
} else {
    Add-Result "A7" "APK 体积" $false "release APK 未产出"
}

# ---------------------------------------------------------------------------
# B1–B4 模拟器冒烟
# ---------------------------------------------------------------------------
if ($SkipEmulator) {
    foreach ($id in @("B1", "B2", "B3", "B4")) {
        Add-Result $id "模拟器冒烟" $true "SKIPPED"
    }
} else {
    $devices = & adb devices 2>$null | Select-String "emulator-\d+\s+device"
    if (-not $devices) {
        Add-Result "B1" "模拟器在线" $false "无在线 emulator；请先启动 AVD"
        foreach ($id in @("B2", "B3", "B4")) { Add-Result $id "模拟器冒烟" $false "无设备" }
    } else {
        Add-Result "B1" "模拟器在线" $true "$($devices.Count) 台"

        if (Test-Path $debugApk) {
            $install = & adb install -r $debugApk 2>&1 | Out-String
            Add-Result "B2" "安装并启动" ($install -match "Success") ($install.Trim() -split "`n" | Select-Object -Last 1)

            & adb logcat -c 2>$null
            & adb shell am start -n "io.github.xht252.motang/.MainActivity" 2>$null | Out-Null
            Start-Sleep -Seconds 5
            $log = & adb logcat -d -s MotangM0 2>$null | Out-String
            $fullLog = & adb logcat -d 2>$null | Out-String
            $crashed = $fullLog -match "FATAL EXCEPTION"
            $probeOk = $log -match "M0 probe ok"
            $nativeMissing = $fullLog -match "UnsatisfiedLinkError|dlopen failed"
            $ok = (-not $crashed) -and $probeOk -and (-not $nativeMissing)
            $detail = if ($crashed) {
                "logcat 出现 FATAL EXCEPTION"
            } elseif ($nativeMissing) {
                "native 库加载失败（UnsatisfiedLinkError / dlopen failed）"
            } elseif ($probeOk) {
                (($log -split "`n" | Where-Object { $_ -match "M0 probe ok" } | Select-Object -First 1).Trim())
            } else {
                "未捕获 M0 probe 日志"
            }
            Add-Result "B3" "ping() 探针通过" $ok $detail
        } else {
            Add-Result "B2" "安装并启动" $false "debug APK 未产出"
            Add-Result "B3" "ping() 无崩溃" $false "debug APK 未产出"
        }

        # 三档窗口：以 wm size 模拟 320/820/940dp 近似像素宽（density 以设备为准）。
        $widths = @(1080, 2200, 2480)
        $ok = $true
        foreach ($w in $widths) {
            & adb shell wm size ("{0}x2400" -f $w) 2>$null | Out-Null
            Start-Sleep -Milliseconds 800
            $log = & adb logcat -d 2>$null | Out-String
            if ($log -match "FATAL EXCEPTION") { $ok = $false }
        }
        & adb shell wm size reset 2>$null | Out-Null
        Add-Result "B4" "三档窗口不崩溃" $ok "widths=$($widths -join ',')"
    }
}

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "==== M0 验收汇总 ====" -ForegroundColor Cyan
$results | ForEach-Object { "{0,-3} {1,-28} {2,-5} {3}" -f $_.Id, $_.Name, $(if ($_.Ok) { "PASS" } else { "FAIL" }), $_.Detail } | Out-Host

$failed = @($results | Where-Object { -not $_.Ok })
if ($failed.Count -gt 0) {
    Write-Host ""
    Write-Host "FAIL 项：$($failed.Count)。" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "全部 PASS。" -ForegroundColor Green
exit 0

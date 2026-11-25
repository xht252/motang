# 墨唐 motang · M0 验收脚本
#
# 逐项执行计划第 7 节的 A1–A7 / B1–B4，输出 PASS/FAIL 表并以退出码汇总。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m0.ps1
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m0.ps1 -SkipEmulator
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m0.ps1 -SkipRust
#
# 退出码：0 全部 PASS；1 存在 FAIL。

[CmdletBinding()]
param(
    [switch]$SkipEmulator,
    [switch]$SkipRust,
    [int]$ApkMaxMb = 40
)

$ErrorActionPreference = "Continue"
$repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$gradlew = Join-Path $repo "gradlew.bat"
$results = [System.Collections.Generic.List[object]]::new()

# ---------------------------------------------------------------------------
# 工具与环境解析
# ---------------------------------------------------------------------------
# 本机 cargo / adb / java 都不在 PATH 上（bootstrap 只改它自己的进程环境），
# 因此验收脚本必须自己解析绝对路径，否则从任意新终端跑都会「命令未找到」。
$script:SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\SDK" }
$script:JdkHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "D:\jdk21" }
$ndkHome = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { Join-Path $script:SdkRoot "ndk\28.2.13676358" }

function Resolve-Tool {
    param([string]$Name, [string[]]$Candidates)
    foreach ($c in $Candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$cargoExe = Resolve-Tool -Name "cargo" -Candidates @(
    (Join-Path $env:USERPROFILE ".cargo\bin\cargo.exe"),
    $(if ($env:CARGO_HOME) { Join-Path $env:CARGO_HOME "bin\cargo.exe" } else { $null })
)
$adbExe = Resolve-Tool -Name "adb" -Candidates @(
    (Join-Path $script:SdkRoot "platform-tools\adb.exe")
)

# Gradle 子进程依赖这些变量。
$env:JAVA_HOME = $script:JdkHome
$env:ANDROID_HOME = $script:SdkRoot
$env:ANDROID_SDK_ROOT = $script:SdkRoot
$env:ANDROID_NDK_HOME = $ndkHome

# 仓库根若存在 keystore.properties，注入签名环境，
# 否则 assembleRelease 只会产出 app-release-unsigned.apk。
$keystorePropsFile = Join-Path $repo "keystore.properties"
if (Test-Path $keystorePropsFile) {
    foreach ($line in Get-Content -LiteralPath $keystorePropsFile) {
        $t = $line.Trim()
        if (-not $t -or $t.StartsWith("#") -or -not $t.Contains("=")) { continue }
        $idx = $t.IndexOf("=")
        $k = $t.Substring(0, $idx).Trim()
        $v = $t.Substring($idx + 1).Trim()
        if (-not [Environment]::GetEnvironmentVariable($k, "Process")) {
            [Environment]::SetEnvironmentVariable($k, $v, "Process")
        }
    }
}

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
        if ($WorkDir) { Set-Location -LiteralPath $WorkDir }
        $out = & $FilePath @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out }
    } finally {
        Set-Location -LiteralPath $prev
    }
}

# ---------------------------------------------------------------------------
# A1 Rust 单测
# ---------------------------------------------------------------------------
if ($SkipRust) {
    Add-Result "A1" "cargo test --workspace" $true "SKIPPED"
} elseif (-not $cargoExe) {
    Add-Result "A1" "cargo test --workspace" $false "找不到 cargo（查 %USERPROFILE%\.cargo\bin）"
} else {
    $r = Invoke-Step -FilePath $cargoExe -Arguments @("test", "--workspace") -WorkDir (Join-Path $repo "core-rs")
    Add-Result "A1" "cargo test --workspace" ($r.ExitCode -eq 0) "exit=$($r.ExitCode)"
}

# ---------------------------------------------------------------------------
# A2 / A3 Gradle 构建
# ---------------------------------------------------------------------------
if (-not (Test-Path $gradlew)) {
    Add-Result "A2" "assembleDebug" $false "gradlew.bat 不存在，请先跑 bootstrap.ps1"
    Add-Result "A3" "assembleRelease" $false "gradlew.bat 不存在"
} else {
    $r = Invoke-Step -FilePath $gradlew -Arguments @(":app:assembleDebug") -WorkDir $repo
    Add-Result "A2" "assembleDebug" ($r.ExitCode -eq 0) "exit=$($r.ExitCode)"
    if ($r.ExitCode -ne 0) {
        ($r.Output -split "`n" | Where-Object { $_ -match "e: |error:|What went wrong" } | Select-Object -First 3) |
            ForEach-Object { Write-Host "      $($_.Trim())" -ForegroundColor DarkYellow }
    }

    $r = Invoke-Step -FilePath $gradlew -Arguments @(":app:assembleRelease") -WorkDir $repo
    Add-Result "A3" "assembleRelease" ($r.ExitCode -eq 0) "exit=$($r.ExitCode)"
    if ($r.ExitCode -ne 0) {
        ($r.Output -split "`n" | Where-Object { $_ -match "e: |error:|What went wrong" } | Select-Object -First 3) |
            ForEach-Object { Write-Host "      $($_.Trim())" -ForegroundColor DarkYellow }
    }
}

# ---------------------------------------------------------------------------
# A4 / A5 / A7 APK 内容检查
# ---------------------------------------------------------------------------
$releaseDir = Join-Path $repo "app\build\outputs\apk\release"
$debugApk = Join-Path $repo "app\build\outputs\apk\debug\app-debug.apk"

$releaseApk = @(
    (Join-Path $releaseDir "app-release.apk"),
    (Join-Path $releaseDir "app-release-unsigned.apk")
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1

function Get-ApkEntries {
    param([string]$Apk)
    if (-not (Test-Path -LiteralPath $Apk)) { return $null }
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
    try { return @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
}

$releaseEntries = if ($releaseApk) { Get-ApkEntries $releaseApk } else { $null }
if ($null -eq $releaseEntries) {
    Add-Result "A4" ".so 存在且 ABI 正确" $false "release APK 未产出"
    Add-Result "A7" "APK 体积" $false "release APK 未产出"
} else {
    $hasArm64 = $releaseEntries -contains "lib/arm64-v8a/libmotang_ffi.so"
    $hasX86 = @($releaseEntries | Where-Object { $_ -like "lib/x86_64/*" }).Count -gt 0
    $ok = $hasArm64 -and (-not $hasX86)
    $signed = [System.IO.Path]::GetFileName($releaseApk) -eq "app-release.apk"
    Add-Result "A4" ".so 存在且 release 仅 arm64" $ok ("arm64=$hasArm64 x86_64=$hasX86 signed=$signed")

    $mb = [math]::Round((Get-Item -LiteralPath $releaseApk).Length / 1MB, 2)
    Add-Result "A7" "APK 体积" ($mb -le [double]$ApkMaxMb) "$mb MB (limit $ApkMaxMb)"
}

$checkScript = Join-Path $PSScriptRoot "check-16k-pagesize.ps1"
if ($releaseApk) {
    $r = Invoke-Step -FilePath "powershell" -Arguments @(
        "-ExecutionPolicy", "Bypass", "-File", $checkScript, "-ApkPath", $releaseApk
    )
    Add-Result "A5" "16KB 对齐" ($r.ExitCode -eq 0) "exit=$($r.ExitCode)"
} else {
    Add-Result "A5" "16KB 对齐" $false "release APK 未产出"
}

$bindings = @(Get-ChildItem -Path (Join-Path $repo "app\build\generated\uniffi") -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue)
$bindingOk = $false
foreach ($b in $bindings) {
    if ((Get-Content -LiteralPath $b.FullName -Raw) -match 'fun\s+`?ping`?\s*\(') { $bindingOk = $true; break }
}
Add-Result "A6" "uniffi Kotlin 绑定已生成" $bindingOk "files=$($bindings.Count)"

# ---------------------------------------------------------------------------
# B1–B4 模拟器冒烟
# ---------------------------------------------------------------------------
if ($SkipEmulator) {
    foreach ($id in @("B1", "B2", "B3", "B4")) {
        Add-Result $id "模拟器冒烟" $true "SKIPPED"
    }
} elseif (-not $adbExe) {
    Add-Result "B1" "模拟器在线" $false "找不到 adb（查 $script:SdkRoot\platform-tools）"
    foreach ($id in @("B2", "B3", "B4")) { Add-Result $id "模拟器冒烟" $false "无 adb" }
} else {
    # 有限等待开机：最多 180s，避免脚本看起来「卡死」。
    $booted = $false
    for ($i = 0; $i -lt 60; $i++) {
        $state = (& $adbExe shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($state -eq "1") { $booted = $true; break }
        Start-Sleep -Seconds 3
    }
    $deviceLines = @((& $adbExe devices 2>$null | Out-String) -split "`n" |
        Where-Object { $_ -match "emulator-\d+\s+device" })

    if (-not $booted) {
        Add-Result "B1" "模拟器在线" $false "180s 内未 boot_completed（在线 $($deviceLines.Count) 台）"
        foreach ($id in @("B2", "B3", "B4")) { Add-Result $id "模拟器冒烟" $false "设备未就绪" }
    } else {
        Add-Result "B1" "模拟器在线" $true "$($deviceLines.Count) 台，boot_completed=1"

        if (Test-Path -LiteralPath $debugApk) {
            # adb 的错误走 stderr，Out-String 会保留 PowerShell 的错误装饰，
            # 且长行会被折断（INSTALL_FAILE\nD_UPDATE_INCOMPATIBLE）。
            # 因此先归一化（去换行/多余空格）再做匹配。
            function Get-AdbNorm([string]$s) { return ($s -replace "\s+", " ") }

            # 先停掉旧进程：否则 am start 只把已有进程拉到前台，
            # Application.onCreate 不会重跑，B3 就抓不到 probe 日志。
            & $adbExe shell am force-stop "io.github.xht252.motang" 2>$null | Out-Null

            $raw = (& $adbExe install -r $debugApk 2>&1 | Out-String)
            $norm = Get-AdbNorm $raw

            # 签名变更会导致覆盖安装被拒（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
            # 这正是「签名一致性」风险的现场：换过签名只能卸载重装。
            $reinstalled = $false
            if ($norm -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE" -or $norm -match "signatures do not match") {
                & $adbExe uninstall "io.github.xht252.motang" 2>$null | Out-Null
                $raw = (& $adbExe install -r $debugApk 2>&1 | Out-String)
                $norm = Get-AdbNorm $raw
                $reinstalled = $true
            }

            $installOk = $norm -match "Success"
            $installDetail = if ($reinstalled) {
                "签名冲突，已卸载重装；result=" + $(if ($installOk) { "Success" } else { "FAILED" })
            } elseif ($installOk) {
                "Success"
            } else {
                ($norm.Substring(0, [Math]::Min(160, $norm.Length)))
            }
            Add-Result "B2" "安装并启动" $installOk $installDetail

            & $adbExe logcat -c 2>$null | Out-Null
            & $adbExe shell am start -n "io.github.xht252.motang/.MainActivity" 2>$null | Out-Null
            Start-Sleep -Seconds 6
            $probeLog = (& $adbExe logcat -d -s MotangM0 2>$null | Out-String)
            $fullLog = (& $adbExe logcat -d 2>$null | Out-String)
            $crashed = $fullLog -match "FATAL EXCEPTION"
            $probeOk = $probeLog -match "M0 probe ok"
            $nativeMissing = $fullLog -match "UnsatisfiedLinkError|dlopen failed"
            $ok = (-not $crashed) -and $probeOk -and (-not $nativeMissing)

            $detail = if ($crashed) {
                "logcat 出现 FATAL EXCEPTION"
            } elseif ($nativeMissing) {
                "native 库加载失败（UnsatisfiedLinkError / dlopen failed）"
            } elseif ($probeOk) {
                (@($probeLog -split "`n") | Where-Object { $_ -match "M0 probe ok" } | Select-Object -First 1).Trim()
            } else {
                "未捕获 M0 probe 日志"
            }
            Add-Result "B3" "ping() 探针通过" $ok $detail
        } else {
            Add-Result "B2" "安装并启动" $false "debug APK 未产出"
            Add-Result "B3" "ping() 探针通过" $false "debug APK 未产出"
        }

        # 三档宽度：按设备实际 density 把 dp 换算成 px，验证 320/820/940dp。
        $density = 420
        $densityLine = (& $adbExe shell wm density 2>$null | Out-String)
        if ($densityLine -match "(\d+)") { $density = [int]$Matches[1] }

        $heightPx = 2400
        $origSize = (& $adbExe shell wm size 2>$null | Out-String)
        if ($origSize -match "(\d+)x(\d+)") { $heightPx = [int]$Matches[2] }

        $ok = $true
        $notes = @()
        foreach ($dp in @(320, 820, 940)) {
            $px = [int][math]::Round($dp * $density / 160.0)
            & $adbExe shell wm size ("{0}x{1}" -f $px, $heightPx) 2>$null | Out-Null
            Start-Sleep -Milliseconds 1200
            $l = (& $adbExe logcat -d 2>$null | Out-String)
            if ($l -match "FATAL EXCEPTION") { $ok = $false; $notes += "$dp dp 崩溃" }
        }
        & $adbExe shell wm size reset 2>$null | Out-Null

        $detail = "density=$density, " + $(if ($notes.Count -gt 0) { $notes -join "; " } else { "三档均正常" })
        Add-Result "B4" "320/820/940dp 不崩溃" $ok $detail
    }
}

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "==== M0 验收汇总 ====" -ForegroundColor Cyan
$results | ForEach-Object {
    "{0,-3} {1,-30} {2,-5} {3}" -f $_.Id, $_.Name, $(if ($_.Ok) { "PASS" } else { "FAIL" }), $_.Detail
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

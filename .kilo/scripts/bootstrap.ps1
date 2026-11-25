# 墨唐 motang · 工具链引导脚本（由你执行，Agent 不代跑）
#
# 用途：把 M0 所需工具链装到位，并让后续会话可直接构建。
# 特性：幂等；可单段注释；每段结尾打印状态表。
#
# 用法：在仓库根执行
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/bootstrap.ps1
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/bootstrap.ps1 -SkipSdk -SkipRust
#
# 前置：本机已存在 D:\jdk21 与 D:\SDK（脚本按此路径，若不同请改 -JdkHome / -SdkRoot）。

[CmdletBinding()]
param(
    [string]$JdkHome = "D:\jdk21",
    [string]$SdkRoot = "D:\SDK",
    [string]$NdkVersion = "28.2.13676358",
    [string]$GradleVersion = "8.14.3",
    [string]$CmdlineToolsBuild = "13114758",
    [string]$AvdName = "motang_fold",
    [string]$SystemImage = "system-images;android-36;google_apis_playstore;x86_64",
    [switch]$SkipSdk,
    [switch]$SkipRust,
    [switch]$SkipGradle,
    [switch]$SkipAvd
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$temp = Join-Path $env:TEMP "kilo"

function Write-Step([string]$Text) {
    Write-Host ""
    Write-Host "== $Text" -ForegroundColor Cyan
}

function Test-Cmd([string]$Name) {
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

# 原生程序把错误写到 stderr 时，$ErrorActionPreference='Stop' 会把它当成
# 终止错误（NativeCommandError）。探测类命令（如 `cargo ndk --version` 在未安装
# 时必然走 stderr）必须用本函数：只看退出码，不把 stderr 当异常。
function Invoke-NativeProbe {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $FilePath @Arguments 2>&1 | Out-String
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out.Trim() }
    } catch {
        return [pscustomobject]@{ ExitCode = -1; Output = $_.Exception.Message }
    } finally {
        $ErrorActionPreference = $prev
    }
}

# 各阶段失败不互相连坐：记录后继续，脚本末尾统一汇总。
$script:PhaseFailures = [System.Collections.Generic.List[string]]::new()

function Invoke-Phase {
    param([string]$Name, [scriptblock]$Body)
    try {
        & $Body
    } catch {
        $msg = "{0} 失败：{1}" -f $Name, $_.Exception.Message
        Write-Warning $msg
        $script:PhaseFailures.Add($msg)
    }
}

if (-not (Test-Path (Join-Path $JdkHome "bin\java.exe"))) {
    throw "找不到 JDK：$JdkHome。请用 -JdkHome 指定正确路径。"
}
if (-not (Test-Path $SdkRoot)) {
    throw "找不到 Android SDK：$SdkRoot。请用 -SdkRoot 指定正确路径。"
}
New-Item -ItemType Directory -Path $temp -Force | Out-Null

# ---------------------------------------------------------------------------
# A1 环境变量（当前进程 + 用户级持久化）
# ---------------------------------------------------------------------------
Write-Step "A1 环境变量"

$ndkHome = Join-Path $SdkRoot "ndk\$NdkVersion"
$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $SdkRoot
$env:ANDROID_SDK_ROOT = $SdkRoot
[Environment]::SetEnvironmentVariable("JAVA_HOME", $JdkHome, "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", $SdkRoot, "User")
[Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $SdkRoot, "User")

$env:Path = @(
    (Join-Path $JdkHome "bin"),
    (Join-Path $SdkRoot "platform-tools"),
    (Join-Path $SdkRoot "cmdline-tools\latest\bin"),
    (Join-Path $SdkRoot "emulator"),
    (Join-Path $env:USERPROFILE ".cargo\bin"),
    $env:Path
) -join ";"

Write-Host "JAVA_HOME   = $env:JAVA_HOME"
Write-Host "ANDROID_HOME= $env:ANDROID_HOME"

# ---------------------------------------------------------------------------
# A2 cmdline-tools（提供 sdkmanager / avdmanager）
# ---------------------------------------------------------------------------
if (-not $SkipSdk) {
    Write-Step "A2 cmdline-tools"

    $sdkManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
    if (Test-Path $sdkManager) {
        Write-Host "已存在，跳过：$sdkManager"
    } else {
        $zip = Join-Path $temp "commandlinetools-win-$CmdlineToolsBuild`_latest.zip"
        if (-not (Test-Path $zip)) {
            $url = "https://dl.google.com/android/repository/commandlinetools-win-$CmdlineToolsBuild`_latest.zip"
            Write-Host "下载 $url"
            Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
        }
        $extract = Join-Path $temp "cmdline-tools-extract"
        if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
        Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force

        # zip 内层目录为 cmdline-tools/，必须重命名为 latest，否则 sdkmanager 找不到自身。
        $target = Join-Path $SdkRoot "cmdline-tools\latest"
        if (Test-Path $target) { Remove-Item -Recurse -Force $target }
        New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
        Move-Item -LiteralPath (Join-Path $extract "cmdline-tools") -Destination $target
        Write-Host "已安装：$target"
    }

    # -----------------------------------------------------------------------
    # A3 SDK 组件
    # -----------------------------------------------------------------------
    Write-Step "A3 SDK 组件（platform-36 / NDK / platform-tools）"

    # 许可需显式接受；此处自动全部接受，等价于 `sdkmanager --licenses` 逐个 y。
    $licenses = 1..50 | ForEach-Object { "y" }
    $licenses | & $sdkManager --licenses | Out-Host

    & $sdkManager "platform-tools" "platforms;android-36" "ndk;$NdkVersion" | Out-Host

    if (Test-Path $ndkHome) {
        [Environment]::SetEnvironmentVariable("ANDROID_NDK_HOME", $ndkHome, "User")
        Write-Host "ANDROID_NDK_HOME = $ndkHome"
    } else {
        Write-Warning "NDK 未落在预期路径：$ndkHome。请检查 sdkmanager 输出。"
    }
} else {
    Write-Host "跳过 SDK（-SkipSdk）。注意：app 的 compileSdk=35 可构建，但无法产出 M0 全量验收。"
}

# ---------------------------------------------------------------------------
# A4 Rust 工具链
# ---------------------------------------------------------------------------
if (-not $SkipRust) {
    Write-Step "A4 Rust / cargo-ndk"

    if (-not (Test-Cmd "rustc")) {
        $rustupInit = Join-Path $temp "rustup-init.exe"
        if (-not (Test-Path $rustupInit)) {
            Invoke-WebRequest -Uri "https://win.rustup.rs/x86_64" -OutFile $rustupInit -UseBasicParsing
        }
        # -y 使用默认参数（stable + 自动 PATH），避免交互。
        & $rustupInit -y --default-toolchain stable --profile default | Out-Host
        $env:Path = "$env:USERPROFILE\.cargo\bin;$env:Path"
    } else {
        Write-Host "rustc 已存在：$((rustc --version))"
    }

    rustup component add rustfmt clippy | Out-Host
    rustup target add aarch64-linux-android x86_64-linux-android | Out-Host

    # 用 Invoke-NativeProbe：未安装 cargo-ndk 时该命令写 stderr 且退出码非零。
    $cargoNdk = Invoke-NativeProbe -FilePath "cargo" -Arguments @("ndk", "--version")
    if ($cargoNdk.ExitCode -ne 0) {
        cargo install cargo-ndk --locked | Out-Host
        $cargoNdk = Invoke-NativeProbe -FilePath "cargo" -Arguments @("ndk", "--version")
    }
    if ($cargoNdk.ExitCode -eq 0) {
        Write-Host "cargo-ndk 就绪：$($cargoNdk.Output)"
    } else {
        throw "cargo-ndk 安装后仍不可用：$($cargoNdk.Output)"
    }
} else {
    Write-Host "跳过 Rust（-SkipRust）。"
}

# ---------------------------------------------------------------------------
# A5 Gradle 发行包（仅用于首次生成 wrapper；wrapper 生成后不再需要它）
# ---------------------------------------------------------------------------
if (-not $SkipGradle) {
    Write-Step "A5 Gradle $GradleVersion → 生成 wrapper"

    $gradleHome = Join-Path $temp "gradle-$GradleVersion"
    if (-not (Test-Path (Join-Path $gradleHome "bin\gradle.bat"))) {
        $zip = Join-Path $temp "gradle-$GradleVersion-bin.zip"
        if (-not (Test-Path $zip)) {
            Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $zip -UseBasicParsing
        }
        Expand-Archive -LiteralPath $zip -DestinationPath $temp -Force
    }

    $gradleBat = Join-Path $gradleHome "bin\gradle.bat"
    $repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
    $wrapperBat = Join-Path $repoRoot "gradlew.bat"

    if (Test-Path $wrapperBat) {
        Write-Host "wrapper 已存在，跳过生成。"
    } else {
        # 关键：在空目录里生成 wrapper，再拷回仓库。
        # 直接在本仓库跑 `gradle wrapper` 会先配置构建脚本，
        # 而构建脚本依赖 AGP/Kotlin 插件下载；一旦网络或版本有问题，
        # wrapper 就生成不出来，形成「没有 wrapper 就无法构建」的死锁。
        $sandbox = Join-Path $temp "wrapper-sandbox"
        if (Test-Path $sandbox) { Remove-Item -Recurse -Force $sandbox }
        New-Item -ItemType Directory -Path $sandbox -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $sandbox "settings.gradle.kts") -Value 'rootProject.name = "wrapper-gen"' -Encoding UTF8

        Push-Location $sandbox
        try {
            & $gradleBat wrapper --gradle-version $GradleVersion --distribution-type bin --no-daemon | Out-Host
        } finally {
            Pop-Location
        }

        foreach ($item in @("gradlew", "gradlew.bat")) {
            $src = Join-Path $sandbox $item
            if (Test-Path $src) { Copy-Item -LiteralPath $src -Destination $repoRoot -Force }
        }
        $wrapperSrc = Join-Path $sandbox "gradle\wrapper"
        $wrapperDst = Join-Path $repoRoot "gradle\wrapper"
        New-Item -ItemType Directory -Path $wrapperDst -Force | Out-Null
        Copy-Item -Path (Join-Path $wrapperSrc "*") -Destination $wrapperDst -Force
        Write-Host "已生成 wrapper 并拷入仓库。"
    }
} else {
    Write-Host "跳过 Gradle（-SkipGradle）。"
}

# ---------------------------------------------------------------------------
# A6 local.properties + AVD
# ---------------------------------------------------------------------------
Write-Step "A6 local.properties"
$localProps = Join-Path $PSScriptRoot "..\..\local.properties"
if (-not (Test-Path $localProps)) {
    # Windows 路径反斜杠需转义，否则 Gradle 解析失败。
    $escaped = $SdkRoot -replace '\\', '\\'
    Set-Content -LiteralPath $localProps -Value "sdk.dir=$escaped" -Encoding ASCII
    Write-Host "已写入 $localProps"
} else {
    Write-Host "已存在 $localProps，跳过。"
}

if (-not $SkipAvd) {
    Invoke-Phase -Name "A6 AVD" -Body {
        Write-Step "A6 AVD（$AvdName）"
        $avdManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
        $existing = (Invoke-NativeProbe -FilePath $avdManager -Arguments @("list", "avd")).Output
        if ($existing -match [regex]::Escape($AvdName)) {
            Write-Host "AVD 已存在：$AvdName"
        } else {
            # pixel_fold 档案若不存在，avdmanager 会报错；此时改 -d pixel_tablet 或其它平板档案。
            "no" | & $avdManager create avd -n $AvdName -k $SystemImage -d pixel_fold | Out-Host
        }
    }
}

# ---------------------------------------------------------------------------
# A7 自检表
# ---------------------------------------------------------------------------
Write-Step "A7 自检"

$checks = [ordered]@{}
$sdkManagerPath = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
$checks["java"] = (Invoke-NativeProbe -FilePath (Join-Path $JdkHome "bin\java.exe") -Arguments @("-version")).Output -split "`n" | Select-Object -First 1
$checks["sdkmanager"] = if (Test-Path $sdkManagerPath) { (Invoke-NativeProbe -FilePath $sdkManagerPath -Arguments @("--version")).Output } else { "缺失" }
$checks["platform-36"] = if (Test-Path (Join-Path $SdkRoot "platforms\android-36")) { "已安装" } else { "缺失" }
$checks["ndk"] = if (Test-Path $ndkHome) { "已安装" } else { "缺失" }
$checks["rustc"] = if (Test-Cmd "rustc") { (Invoke-NativeProbe -FilePath "rustc" -Arguments @("--version")).Output } else { "缺失" }
$checks["android targets"] = if (Test-Cmd "rustup") { (Invoke-NativeProbe -FilePath "rustup" -Arguments @("target", "list", "--installed")).Output -replace "`r?`n", ", " } else { "缺失" }
$cargoNdkProbe = if (Test-Cmd "cargo") { Invoke-NativeProbe -FilePath "cargo" -Arguments @("ndk", "--version") } else { $null }
$checks["cargo-ndk"] = if ($cargoNdkProbe -and $cargoNdkProbe.ExitCode -eq 0) { $cargoNdkProbe.Output } else { "缺失" }
$checks["gradlew"] = if (Test-Path (Join-Path $PSScriptRoot "..\..\gradlew.bat")) { "已生成" } else { "缺失" }
$checks["gradlew 模板"] = if (Test-Path (Join-Path $PSScriptRoot "..\..\gradle\wrapper\gradle-wrapper.properties")) { "properties 已入库" } else { "缺失" }
$checks["adb devices"] = if (Test-Cmd "adb") { ((adb devices) -join " ") } else { "缺失" }
$checks["llvm-readelf"] = if (Test-Path "$ndkHome\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe") { "已安装" } else { "缺失" }

$checks.GetEnumerator() | ForEach-Object { "{0,-18} {1}" -f $_.Key, $_.Value } | Out-Host

Write-Host ""
if ($script:PhaseFailures.Count -gt 0) {
    Write-Host "以下阶段失败（其余阶段已继续执行）：" -ForegroundColor Yellow
    $script:PhaseFailures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Yellow }
    Write-Host ""
    Write-Host "修复后可单独重跑对应阶段，例如：" -ForegroundColor Yellow
    Write-Host "  powershell -ExecutionPolicy Bypass -File .kilo/scripts/bootstrap.ps1 -SkipSdk -SkipAvd" -ForegroundColor Yellow
    exit 1
}

Write-Host "引导完成。下一步：.\gradlew.bat :app:assembleDebug" -ForegroundColor Green
Write-Host "然后执行：powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m0.ps1" -ForegroundColor Green

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

    $cargoNdkVersion = cargo ndk --version 2>$null
    if (-not $cargoNdkVersion) {
        cargo install cargo-ndk --locked | Out-Host
    } else {
        Write-Host "cargo-ndk 已存在：$cargoNdkVersion"
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
    if (-not (Test-Path (Join-Path $PSScriptRoot "..\..\gradlew.bat"))) {
        & $gradleBat wrapper --gradle-version $GradleVersion --distribution-type bin
    } else {
        Write-Host "wrapper 已存在，跳过生成。"
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
    Write-Step "A6 AVD（$AvdName）"
    $avdManager = Join-Path $SdkRoot "cmdline-tools\latest\bin\avdmanager.bat"
    $existing = & $avdManager list avd 2>$null | Out-String
    if ($existing -match [regex]::Escape($AvdName)) {
        Write-Host "AVD 已存在：$AvdName"
    } else {
        # pixel_fold 档案若不存在，avdmanager 会报错；此时改 -d pixel_tablet 或其它平板档案。
        "no" | & $avdManager create avd -n $AvdName -k $SystemImage -d pixel_fold | Out-Host
    }
}

# ---------------------------------------------------------------------------
# A7 自检表
# ---------------------------------------------------------------------------
Write-Step "A7 自检"

$checks = [ordered]@{}
$checks["java"] = (cmd /c "`"$JdkHome\bin\java.exe`" -version 2>&1" | Select-Object -First 1)
$sdkManagerPath = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
$checks["sdkmanager"] = if (Test-Path $sdkManagerPath) { (& $sdkManagerPath --version 2>$null) } else { "缺失" }
$checks["platform-36"] = if (Test-Path (Join-Path $SdkRoot "platforms\android-36")) { "已安装" } else { "缺失" }
$checks["ndk"] = if (Test-Path $ndkHome) { "已安装" } else { "缺失" }
$checks["rustc"] = if (Test-Cmd "rustc") { (rustc --version) } else { "缺失" }
$checks["android targets"] = if (Test-Cmd "rustup") { ((rustup target list --installed) -join ", ") } else { "缺失" }
$checks["cargo-ndk"] = if (Test-Cmd "cargo") { (cargo ndk --version 2>$null) } else { "缺失" }
$checks["gradlew"] = if (Test-Path (Join-Path $PSScriptRoot "..\..\gradlew.bat")) { "已生成" } else { "缺失" }
$checks["adb devices"] = if (Test-Cmd "adb") { ((adb devices) -join " ") } else { "缺失" }
$checks["llvm-readelf"] = if (Test-Path "$ndkHome\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe") { "已安装" } else { "缺失" }

$checks.GetEnumerator() | ForEach-Object { "{0,-18} {1}" -f $_.Key, $_.Value } | Out-Host

Write-Host ""
Write-Host "引导完成。下一步：.\gradlew.bat :app:assembleDebug" -ForegroundColor Green
Write-Host "然后执行：powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-m0.ps1" -ForegroundColor Green

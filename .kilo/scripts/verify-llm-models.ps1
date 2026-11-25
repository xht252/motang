# 墨唐 M5 · 端侧模型可加载性验证（本机 Windows 侧，不涉及 Android）
#
# 目的：在写 :llm 模块之前，先用 llama.cpp 官方 CLI 验证两件事：
#   1) llama.cpp @ b12726 能否识别并推理 MiniCPM5-2B-Q4_K_M.gguf
#   2) llama.cpp 的 mtmd（多模态/音频）能否把 Qwen3-ASR-0.6B 用于语音转写
#
# 为什么必须先验证：模型能否加载取决于 llama.cpp 版本是否支持对应架构。
# 若不支持，先写 Android 接线就是返工。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-llm-models.ps1
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-llm-models.ps1 -SkipBuild
#   powershell -ExecutionPolicy Bypass -File .kilo/scripts/verify-llm-models.ps1 -AudioFile "C:\path\test.wav"
#
# 退出码：0 全部 PASS / 1 有 FAIL / 2 环境缺失（cmake 或编译器）

[CmdletBinding()]
param(
    [string]$RepoRoot,
    [string]$ModelDir,
    [string]$BuildDir,
    [string]$AudioFile,
    # ASR 的音频编码器（mmproj）。Qwen3-ASR 主 GGUF 不含音频塔，必须单独提供。
    [string]$MmprojFile,
    [int]$TextTokens = 24,
    [int]$TimeoutSec = 600,
    [switch]$SkipBuild,
    [switch]$SkipAudio
)

$ErrorActionPreference = "Continue"

# 注意：不能在 param 默认值里用 $PSScriptRoot —— powershell -File 求值默认值时它仍为空。
if (-not $RepoRoot) { $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path }
if (-not $ModelDir) { $ModelDir = Join-Path $RepoRoot "model" }
if (-not $BuildDir) { $BuildDir = Join-Path $RepoRoot "llm\build-host" }

$LlamaDir = Join-Path $RepoRoot "llm\llama.cpp"
$TextModel = Join-Path $ModelDir "MiniCPM5-2B-Q4_K_M.gguf"
$AsrModel = Join-Path $ModelDir "Qwen3-ASR-0.6B-Q8_0.gguf"

$results = [System.Collections.Generic.List[object]]::new()

function Add-Result {
    param([string]$Id, [string]$Name, [string]$State, [string]$Detail)
    $results.Add([pscustomobject]@{ Id = $Id; Name = $Name; State = $State; Detail = $Detail })
    $color = switch ($State) { "PASS" { "Green" } "FAIL" { "Red" } "SKIP" { "Yellow" } default { "Gray" } }
    Write-Host ("[{0}] {1} {2} — {3}" -f $State, $Id, $Name, $Detail) -ForegroundColor $color
}

function Invoke-Logged {
    param([string]$FilePath, [string[]]$Arguments, [string]$WorkDir, [int]$Timeout = 600)
    $prev = Get-Location
    try {
        if ($WorkDir) { Set-Location -LiteralPath $WorkDir }
        $job = Start-Job -ScriptBlock {
            param($exe, $args2, $wd)
            Set-Location -LiteralPath $wd
            & $exe @args2 2>&1 | Out-String
        } -ArgumentList $FilePath, $Arguments, (Get-Location).Path

        if (-not (Wait-Job $job -Timeout $Timeout)) {
            Stop-Job $job
            Remove-Job $job -Force
            return [pscustomobject]@{ TimedOut = $true; Output = "" }
        }
        $out = Receive-Job $job
        Remove-Job $job -Force
        return [pscustomobject]@{ TimedOut = $false; Output = $out }
    } finally {
        Set-Location -LiteralPath $prev
    }
}

# ---------------------------------------------------------------------------
# 0. 前置检查
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "==== 墨唐 M5 · 模型可加载性验证 ====" -ForegroundColor Cyan
Write-Host "仓库：$RepoRoot"
Write-Host ""

Add-Result "P1" "llama.cpp 子模块" $(if (Test-Path (Join-Path $LlamaDir "CMakeLists.txt")) { "PASS" } else { "FAIL" }) $LlamaDir

$textOk = Test-Path $TextModel
$textMb = if ($textOk) { [math]::Round((Get-Item $TextModel).Length / 1MB, 1) } else { 0 }
Add-Result "P2" "文本模型存在" $(if ($textOk) { "PASS" } else { "FAIL" }) ("{0} ({1} MB)" -f $TextModel, $textMb)

$asrOk = Test-Path $AsrModel
$asrMb = if ($asrOk) { [math]::Round((Get-Item $AsrModel).Length / 1MB, 1) } else { 0 }
Add-Result "P3" "ASR 模型存在" $(if ($asrOk) { "PASS" } else { "FAIL" }) ("{0} ({1} MB)" -f $AsrModel, $asrMb)

# GGUF 魔数快速自检：前 4 字节应为 "GGUF"
function Test-GgufMagic {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return $false }
    $fs = [System.IO.File]::OpenRead($Path)
    try {
        $buf = New-Object byte[] 4
        $null = $fs.Read($buf, 0, 4)
        return ([System.Text.Encoding]::ASCII.GetString($buf) -eq "GGUF")
    } finally { $fs.Dispose() }
}
Add-Result "P4" "GGUF 魔数" $(if ((Test-GgufMagic $TextModel) -and (Test-GgufMagic $AsrModel)) { "PASS" } else { "FAIL" }) "前 4 字节应为 GGUF"

$cmake = Get-Command cmake -ErrorAction SilentlyContinue
if (-not $cmake) {
    Add-Result "P5" "CMake" "FAIL" "未安装。请安装 CMake 3.27+ 并加入 PATH"
    Write-Host ""
    Write-Host "缺少 CMake，无法构建。安装后重跑本脚本。" -ForegroundColor Yellow
    exit 2
}
Add-Result "P5" "CMake" "PASS" ((& cmake --version) | Select-Object -First 1)

# 编译器：优先 Ninja + MSVC，否则 VS generator
$ninja = Get-Command ninja -ErrorAction SilentlyContinue
$generator = if ($ninja) { "Ninja" } else { "Visual Studio 17 2022" }
Add-Result "P6" "构建器" "PASS" $generator

# ---------------------------------------------------------------------------
# 1. 构建 llama.cpp（host，CPU）
# ---------------------------------------------------------------------------
function Get-ToolPath([string]$Name) {
    $exe = Get-ChildItem -Path $BuildDir -Recurse -Filter $Name -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($exe) { return $exe.FullName }
    return $null
}

if ($SkipBuild) {
    Add-Result "B1" "构建 llama.cpp" "SKIP" "-SkipBuild"
} else {
    Write-Host ""
    Write-Host "构建中（首次约 5–15 分钟）…" -ForegroundColor Cyan
    $cfg = Invoke-Logged -FilePath "cmake" -WorkDir $LlamaDir -Timeout $TimeoutSec -Arguments @(
        "-S", ".", "-B", $BuildDir,
        "-G", $generator,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DLLAMA_CURL=OFF",
        "-DLLAMA_BUILD_EXAMPLES=ON",
        "-DLLAMA_BUILD_TESTS=OFF",
        "-DLLAMA_BUILD_SERVER=OFF",
        "-DGGML_NATIVE=ON"
    )
    if ($cfg.TimedOut) {
        Add-Result "B1" "配置 CMake" "FAIL" "超时（$TimeoutSec s）"
    } else {
        $cfgOk = $cfg.Output -match "Configuring done|Build files have been written"
        Add-Result "B1" "配置 CMake" $(if ($cfgOk) { "PASS" } else { "FAIL" }) ($cfg.Output -split "`n" | Select-Object -Last 1)

        if ($cfgOk) {
            $bld = Invoke-Logged -FilePath "cmake" -WorkDir $LlamaDir -Timeout ($TimeoutSec * 4) -Arguments @(
                "--build", $BuildDir, "--config", "Release", "-j"
            )
            $bldOk = (-not $bld.TimedOut) -and ($bld.Output -match "Build succeeded|Linking CXX executable")
            Add-Result "B2" "编译 llama.cpp" $(if ($bldOk) { "PASS" } else { "FAIL" }) ($bld.Output -split "`n" | Select-Object -Last 1)
        }
    }
}

# 该 llama.cpp 版本已把 llama-cli 改名为 llama-completion。
$llamaCli = Get-ToolPath "llama-completion.exe"
if (-not $llamaCli) { $llamaCli = Get-ToolPath "llama-cli.exe" }
$mtmdCli = Get-ToolPath "llama-mtmd-cli.exe"
$cliDetail = if ($llamaCli) { $llamaCli } else { "未找到" }
Add-Result "B3" "llama-cli 存在" $(if ($llamaCli) { "PASS" } else { "FAIL" }) $cliDetail
$mtmdDetail = if ($mtmdCli) { $mtmdCli } else { "未找到（音频验证需要）" }
Add-Result "B4" "llama-mtmd-cli 存在" $(if ($mtmdCli) { "PASS" } else { "FAIL" }) $mtmdDetail

if (-not $llamaCli) {
    Write-Host ""
    Write-Host "没有 llama-cli，后续模型验证无法进行。" -ForegroundColor Red
    $results | Format-Table -AutoSize | Out-Host
    exit 1
}

# ---------------------------------------------------------------------------
# 2. 文本模型：MiniCPM5-2B 能否加载并生成
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "测试文本模型（${TextTokens} tokens）…" -ForegroundColor Cyan
$gen = Invoke-Logged -FilePath $llamaCli -WorkDir $RepoRoot -Timeout $TimeoutSec -Arguments @(
    "-m", $TextModel,
    "-p", "床前明月光，疑是地上霜。请用一句话解释这句诗。",
    "-n", "$TextTokens",
    "--no-display-prompt",
    "--no-warmup",
    "-t", "6"
)

if ($gen.TimedOut) {
    Add-Result "T1" "MiniCPM5-2B 推理" "FAIL" "超时（$TimeoutSec s）"
} else {
    $o = $gen.Output
    $archBad = $o -match "unknown model architecture|unsupported model|error loading model"
    $loaded = $o -match "llama_model_loader|llama_model_load|load_tensors|n_ctx"
    $hasText = ($o -replace "\s", "").Length -gt 40
    if ($archBad) {
        Add-Result "T1" "MiniCPM5-2B 推理" "FAIL" "架构不被 b12726 支持"
        Write-Host ($o -split "`n" | Select-Object -Last 6) -ForegroundColor DarkYellow
    } elseif ($loaded -and $hasText) {
        Add-Result "T1" "MiniCPM5-2B 推理" "PASS" "加载成功并生成文本"
        Write-Host "---- 模型输出 ----" -ForegroundColor DarkGray
        Write-Host (($o -split "`n" | Where-Object { $_ -and $_ -notmatch "^(llm_load|llama_|ggml_|build:|main:|clip_|load_)" }) -join "`n").Trim()
        Write-Host "------------------" -ForegroundColor DarkGray
    } else {
        Add-Result "T1" "MiniCPM5-2B 推理" "FAIL" "未见有效输出"
        Write-Host ($o -split "`n" | Select-Object -Last 8) -ForegroundColor DarkYellow
    }
}

# 记录吞吐（llama-cli 会打印 tokens/s）
if ($gen.Output -match "([\d.]+)\s*tokens per second") {
    Add-Result "T2" "文本吞吐" "PASS" ("{0} tok/s（host CPU，仅作参考）" -f $Matches[1])
} else {
    Add-Result "T2" "文本吞吐" "SKIP" "未从输出解析到 tok/s"
}

# ---------------------------------------------------------------------------
# 3. ASR 模型：mtmd 是否支持音频
# ---------------------------------------------------------------------------
if ($SkipAudio) {
    Add-Result "A1" "Qwen3-ASR 转写" "SKIP" "-SkipAudio"
} elseif (-not $mtmdCli) {
    Add-Result "A1" "Qwen3-ASR 转写" "FAIL" "缺少 llama-mtmd-cli（该 tag 无音频示例）"
} elseif (-not $AudioFile -or -not (Test-Path $AudioFile)) {
    Add-Result "A1" "Qwen3-ASR 转写" "SKIP" "未提供 -AudioFile（需 16kHz 单声道 wav）"
} else {
    Write-Host ""
    Write-Host "测试 ASR 模型…" -ForegroundColor Cyan
    # mtmd-cli 不支持 --no-display-prompt（实测报错），不要加。
    $asrArgs = @("-m", $AsrModel, "--mmproj", $MmprojFile, "--audio", $AudioFile, "-p", "转写这段音频。", "-n", "64")
    $asr = Invoke-Logged -FilePath $mtmdCli -WorkDir $RepoRoot -Timeout $TimeoutSec -Arguments $asrArgs
    if ($asr.TimedOut) {
        Add-Result "A1" "Qwen3-ASR 转写" "FAIL" "超时"
    } else {
        $o = $asr.Output
        $unsupported = $o -match "unknown model architecture|does not support audio|no audio|failed to load audio"
        if ($unsupported) {
            Add-Result "A1" "Qwen3-ASR 转写" "FAIL" "b12726 不支持该音频架构"
            Write-Host ($o -split "`n" | Select-Object -Last 8) -ForegroundColor DarkYellow
        } else {
            Add-Result "A1" "Qwen3-ASR 转写" "PASS" "已完成一次转写"
            Write-Host "---- 转写结果 ----" -ForegroundColor DarkGray
            Write-Host (($o -split "`n" | Where-Object { $_ -and $_ -notmatch "^(llm_load|llama_|ggml_|build:|main:|clip_|load_)" }) -join "`n").Trim()
            Write-Host "------------------" -ForegroundColor DarkGray
        }
    }
}

# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "==== 汇总 ====" -ForegroundColor Cyan
$results | ForEach-Object {
    "{0,-4} {1,-24} {2,-5} {3}" -f $_.Id, $_.Name, $_.State, $_.Detail
} | Out-Host

$failed = @($results | Where-Object { $_.State -eq "FAIL" })
Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host "FAIL 项：$($failed.Count)" -ForegroundColor Red
    Write-Host "若 T1 失败 → MiniCPM5 架构不被该 tag 支持，需换 tag 或换模型。" -ForegroundColor Yellow
    Write-Host "若 A1 失败 → llama.cpp 音频路径不支持 Qwen3-ASR，建议改用 SenseVoice-Small。" -ForegroundColor Yellow
    exit 1
}
Write-Host "全部 PASS。可以开始 :llm 模块接线。" -ForegroundColor Green
exit 0

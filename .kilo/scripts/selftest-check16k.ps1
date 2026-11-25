# 冒烟：校验 check-16k-pagesize.ps1 的解析与失败路径（无需 NDK/APK）。
# 用法：powershell -File .kilo/scripts/selftest-check16k.ps1
#
# 注意：本脚本刻意用 Continue，因为被测脚本会向 stderr 输出错误信息，
# 在 Stop 模式下会被当成 NativeCommandError 而中断测试。

$ErrorActionPreference = "Continue"
$script = Join-Path $PSScriptRoot "check-16k-pagesize.ps1"
$failures = 0

function Invoke-Child {
    param([string[]]$Arguments)
    $out = & powershell -ExecutionPolicy Bypass -File $script @Arguments 2>&1 | Out-String
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $out }
}

function Assert-True {
    param([bool]$Condition, [string]$Name)
    if ($Condition) {
        Write-Host "PASS $Name" -ForegroundColor Green
    } else {
        Write-Host "FAIL $Name" -ForegroundColor Red
        $script:failures++
    }
}

# 1) 语法解析
$errs = $null
[void][System.Management.Automation.Language.Parser]::ParseFile($script, [ref]$null, [ref]$errs)
Assert-True (($null -eq $errs) -or ($errs.Count -eq 0)) "check-16k-pagesize.ps1 语法解析"

# 2) 不存在的 .so → 非零退出码
$r = Invoke-Child @("-SoPath", "C:\definitely-not-exist\libmotang_ffi.so")
Assert-True ($r.ExitCode -ne 0) "不存在的 .so 返回非零"

# 3) 同一场景加 -WarnOnly → 退出码 0
$r = Invoke-Child @("-SoPath", "C:\definitely-not-exist\libmotang_ffi.so", "-WarnOnly")
Assert-True ($r.ExitCode -eq 0) "WarnOnly 不阻断"

# 4) 缺 NDK（找不到 llvm-readelf）→ 非零，且错误信息可读
$r = Invoke-Child @("-SoPath", "C:\Windows\System32\kernel32.dll", "-NdkHome", "C:\definitely-not-exist")
Assert-True ($r.ExitCode -ne 0) "缺 llvm-readelf 返回非零"
Assert-True ($r.Output -match "llvm-readelf") "缺 NDK 时提示 llvm-readelf 路径"

if ($failures -gt 0) {
    Write-Host ""
    Write-Host "自检失败：$failures 项" -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "自检全部通过。" -ForegroundColor Green
exit 0

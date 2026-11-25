# 墨唐 motang · 构建与验收踩坑记录

> 记录「在 Windows + PowerShell 5.1 上实际跑通 M0」时踩到的真实问题。
> 这些坑会重复出现（重建环境、换机器、CI），所以必须留档。

---

## 1. PowerShell 5.1 的 `$ErrorActionPreference = "Stop"` 会把 stderr 当异常

**症状**：`bootstrap.ps1` 在 `cargo ndk --version`（未安装时）处直接终止，
后续的 Gradle wrapper 生成、AVD 创建全都没执行。

**原因**：PS 5.1 把原生命令写到 stderr 的输出包装成 `NativeCommandError`，
在 `Stop` 模式下等价于终止错误。

**修法**：探测类原生命令统一走 `Invoke-NativeProbe`（内部临时切 `Continue`，
只看 `$LASTEXITCODE`）。见 `bootstrap.ps1`。

**通用规则**：不要用 `if (-not (cargo xxx --version))` 这类写法判断工具是否存在；
用 `Get-Command` 或退出码。

---

## 2. Gradle 守护进程的 PATH 不含 `.cargo\bin`

**症状**：命令行 `cargo ndk ...` 能跑，但 `./gradlew assembleDebug` 报
「找不到 cargo」。

**原因**：`bootstrap.ps1` 只改了它自己那个 PowerShell 进程的 `$env:Path`；
Gradle 守护进程是独立进程，不会继承。

**修法**：`app/build.gradle.kts` 用 `resolveCargo()` 解析绝对路径
（Gradle 属性 > `CARGO_HOME` > `%USERPROFILE%\.cargo\bin` > PATH）。

**同类问题**：`adb` 也不在 PATH。验收脚本必须自己解析
`$ANDROID_HOME\platform-tools\adb.exe`。

---

## 3. `tasks.named("preDebugBuild")` 在配置阶段会抛异常

**症状**：`Task with name 'preDebugBuild' not found in project ':app'`。

**原因**：AGP 的 `preBuild` / `preDebugBuild` 由 Variant API 在配置后段创建，
脚本 `android {}` 块执行时它们还不存在。

**修法**：用 `tasks.matching { ... }.configureEach { dependsOn(...) }`，
任务出现时才挂依赖。见 `app/build.gradle.kts` 的「任务接线」小节。

---

## 4. Gradle 版本目录 alias 用 kebab-case 会撞访问器前缀

**症状**：同时存在 `compose-ui` 与 `compose-ui-graphics` 时，访问器冲突。

**原因**：Gradle 把 `-` 分段生成嵌套访问器，两者都要占用 `libs.compose.ui`。

**修法**：全部改 camelCase（`composeUi`、`composeUiGraphics`），
见 `gradle/libs.versions.toml` 顶部注释与 DECISIONS 第 12 条。

---

## 5. `signingConfig = null` 会让 debug 产出 `app-debug-unsigned.apk`

**症状**：`assembleDebug` 成功，但产物叫 `app-debug-unsigned.apk`，装不上。

**原因**：写成
```kotlin
signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
```
未配置 release 密钥时右侧为 `null`，把 AGP 默认的 debug 签名覆盖掉了。

**修法**：只在非空时赋值：
```kotlin
signingConfigs.getByName("release").takeIf { it.storeFile != null }?.let { signingConfig = it }
```

---

## 6. 换签名后覆盖安装必然失败（这是设计风险，不是 bug）

**症状**：`INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package ... signatures do not match`。

**原因**：先用默认 debug 密钥装过一次，之后 `keystore.properties` 生效、
debug 也改用 release 密钥，签名不一致。

**处置**：这是 DECISIONS 第 9 条「签名一致性」的现实体现——
换签名只能卸载重装，Room 数据随之丢失。

**验收脚本行为**：`verify-m0.ps1` 检测到该错误时自动 `adb uninstall` 后重装，
并在结果里显式标注「签名冲突，已卸载重装」，不静默吞掉。

**对真实用户的含义**：**第一次装机就必须用最终 release 密钥**。
M0 阶段的 keystore 是验证用临时密钥，正式使用前应重新生成并固定。

---

## 7. `adb ... 2>&1 | Out-String` 的文本会被折断

**症状**：用正则匹配 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 匹配不到。

**原因**：PowerShell 把 stderr 包成 ErrorRecord 渲染，长行按终端宽度折行
（变成 `INSTALL_FAILE\nD_UPDATE_INCOMPATIBLE`）。

**修法**：匹配前先归一化：`($s -replace "\s+", " ")`。

---

## 8. `am start` 不会重跑 `Application.onCreate`

**症状**：App 已在运行，重新 `am start` 后抓不到 `M0 probe ok` 日志。

**修法**：抓日志前先 `am force-stop <pkg>`，确保进程冷启动。
见 `verify-m0.ps1` 的 B3 段。

---

## 9. PowerShell 5.1 需要 UTF-8 BOM 才能正确读中文

**症状**：脚本里的中文注释导致语法解析报错（乱码字符被当成代码）。

**原因**：PS 5.1 对无 BOM 文件按 ANSI（本机为 GBK）解码。

**修法**：所有 `.ps1` 必须是 **UTF-8 with BOM**。
`.kilo/scripts/*.ps1` 均已带 BOM；新增脚本务必保持。

---

## 10. PowerShell 没有 `when` 关键字

**症状**：`必须在"-"运算符后面提供一个值表达式`。

**原因**：写成 Kotlin/Swift 风格的 `$x = when { ... }`。

**修法**：用 `if / elseif / else`。

---

## 11. 用 `-replace` 改脚本时 `$` 会被当成捕获组

**症状**：一次 `-replace` 把 `$releaseApk` 展开成空串，整个脚本膨胀损坏。

**修法**：改 PowerShell 脚本优先用编辑器级精确替换（Edit 工具），
不要在 shell 里用 `-replace` 拼含 `$` 的替换串；若必须用，替换串里的
`$` 要写成 `$$`。

---

## 12. 环境事实（本机，2026-11-25）

| 项 | 值 |
|---|---|
| JDK | `D:\jdk21`（21，未在 PATH） |
| Android SDK | `D:\SDK`（platform-36、build-tools 34/35/36、NDK 28.2.13676358） |
| cmdline-tools | `D:\SDK\cmdline-tools\latest` |
| Rust | `C:\Users\30669\.cargo`（1.100.0，未在 PATH） |
| cargo-ndk | 4.1.2 |
| Gradle | wrapper 8.14.3（已入库） |
| AVD | `motang_fold`（pixel_fold 档案，API 36 google_apis_playstore x86_64） |

**Gradle 构建前必须设置**：
```powershell
$env:JAVA_HOME="D:\jdk21"
$env:ANDROID_HOME="D:\SDK"
$env:ANDROID_NDK_HOME="D:\SDK\ndk\28.2.13676358"
```
（`verify-m0.ps1` 已内置这些默认值，可免设。）

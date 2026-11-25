# 墨唐 motang · 架构决策记录

> 采用本文件记录「与原始方案不一致」或「方案未覆盖但必须拍板」的决策。
> 修改硬约束时必须同步本文件。
> 构建/验收类问题的排查记录另见 `BUILD-TROUBLESHOOTING.md`。

---

## 1. 配置目录统一为 `.kilo/`

**决策**：rules / skills / agents / scripts 全部放 `.kilo/`，不使用方案原文的 `.kilocode/`。

**理由**：本仓库环境约定 `.kilo/` 为规范目录（`.kilocode/` 仅作 legacy 兼容）。
两套并存会造成规则重复加载与优先级混淆。

**补充**：Kilo 只自动加载 `AGENTS.md` 与 `kilo.json` 里 `instructions` 通配的文件，
`.kilo/rules/*.md` **不会**自动生效。因此已在 `kilo.json` 中显式声明：
```json
"instructions": ["AGENTS.md", ".kilo/rules/project.md"]
```

---

## 2. compileSdk / targetSdk 暂锁 35

**决策**：M0 使用 compileSdk 35 / targetSdk 35，而非方案要求的 36。

**理由**：初次实施时本机仅有 `platforms/android-35`。

**现状更新（2026-11-25）**：`platforms;android-36` 与 `ndk;28.2.13676358`
已通过 `bootstrap.ps1` 安装完成，**升 36 的技术前提已具备**，只是尚未执行。

**升级清单（执行时）**：
1. `app/build.gradle.kts` 的 `compileSdk` / `targetSdk` → 36
2. Compose BOM → 更新版本，material3-adaptive 评估 1.3.x
3. 重新验证：`assembleDebug`、16KB 对齐、targetSdk 36 行为（边到边、FGS 类型、通知）
4. 更新本文件与 `MILESTONES.md` 版本矩阵

---

## 3. release `panic = "unwind"`（而非方案要求的 `abort`）

**决策**：整个 workspace 的 release profile 使用 `panic = "unwind"`。

**理由**：
- uniffi 依赖 panic unwind 才能把 Rust panic 包装成 `UniffiRustCallStatus`
  并转成 Kotlin 侧的 `InternalException`；`abort` 会让进程直接终止。
- 方案建议「`motang-ffi` 用 unwind、bin 用 abort」，但 Cargo 明确不支持：
  > Overrides cannot specify the `panic`, `lto`, or `rpath` settings.
  （Cargo Book · Profiles · Overrides）

**补偿措施**：
- 所有 `#[uniffi::export]` 函数返回 `Result<_, MotangError>`，避免 panic 逃逸。
- 禁止在生产路径使用 `unwrap()` / `expect()`。

---

## 4. 唐诗三百首无注释/译文/赏析 → 解释全走 LLM

**决策**：M1 schema 保留 `notes` / `translation` / `appreciation` 字段但恒为 `null`；
M5 解释统一由端侧 LLM 生成并缓存。

**理由**：实测 `chinese-poetry` 的 `全唐诗/唐诗三百首.json` 每条只有
`author` / `title` / `paragraphs` / `tags` / `id` 五个字段，**不含任何注释译文**。

**影响**：
- 规则措辞改为「预置注释**若存在**则优先」，保留该分支。
- M5 的免责标注压力更大：所有解释均为 AI 生成。

---

## 5. Gradle wrapper 由 bootstrap 脚本生成

**决策**：`gradlew` / `gradlew.bat` / `gradle-wrapper.jar` 由
`bootstrap.ps1` 的 A5 段生成后入库。

**理由**：初次实施时本机无任何 Gradle 安装，无法离线生成 wrapper jar，
且 wrapper jar 是二进制、不应手写。

**实现要点**：在**空目录**里跑 `gradle wrapper` 再拷回仓库——
直接在本仓库跑会先配置构建脚本，一旦插件下载失败就形成
「没有 wrapper 就无法构建」的死锁。

**现状**：已生成并入库（Gradle 8.14.3）。

---

## 6. 绑定从 debug 产物生成

**决策**：`generateUniffiBindings` 依赖 `cargoBuildDebug`，从
`build/rustJniLibs/debug/x86_64/libmotang_ffi.so` 读取 uniffi 元数据。

**理由**：debug 产物未 strip，符号表与元数据必然完整；绑定内容由源码决定，
与 profile 无关，对 release 同样有效。

---

## 7. JNA 必须用 `@aar` 变体

**决策**：`implementation("net.java.dev.jna:jna:5.19.1@aar")`，并在 ProGuard 中 keep。

**理由**：uniffi 0.32 的 Kotlin 绑定使用 JNA **direct mapping**
（`Native.register`、`com.sun.jna.Callback`、`Structure.ByValue`）。
Maven Central 同时发布 `jna.jar`（无 Android native）与 `jna.aar`
（含 `jni/<abi>/libjnidispatch.so`）；默认会解析到 jar，
运行期找不到 jnidispatch 直接崩。

**实测确认**：release APK 内已包含 `lib/arm64-v8a/libjnidispatch.so`。

---

## 8. M0 关闭 R8

**决策**：M0 `release` 的 `isMinifyEnabled = false`。

**理由**：JNA + uniffi 绑定在 M0 尚未验证混淆安全性；先保证能装机验证。
`proguard-rules.pro` 已预置 keep 规则，M6 可直接启用。

---

## 9. 签名一致性是第一风险（实测命中）

**决策**：
- release keystore 自 M0 固定；`keystore/`、`keystore.properties` 全部 gitignore。
- 签名配置来源优先级：Gradle 属性 > 环境变量 > 仓库根 `keystore.properties`。

**理由**：自签直装下签名不一致会导致 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
只能卸载重装，Room 中的打卡与统计随之丢失。

**实测**：M0 验收时确实命中了该错误（先用 debug 默认密钥装过，后改 release 密钥）。

**给用户的明确警告**：
- `keystore/motang-release.jks`（口令 `motangdev`）**只是验证用临时密钥**。
- 正式长期使用前应重新生成一个强口令密钥，并从此永不更换。
- 首次正式装机必须用最终密钥，不要先用临时密钥装完再换。

---

## 10. 数据量口径以实测为准

**决策**：M1 必须实测入库条数并写入 `MILESTONES.md`，验收指标按实测值重设。

**理由**：方案写「5.7 万首」，上游 `chinese-poetry` README 称「5.5 万首唐诗」。

---

## 11. release `strip = "debuginfo"`（不剥离符号表）

**决策**：`[profile.release] strip = "debuginfo"`，而非方案要求的 `strip = true`。

**理由**：
- `strip = true` 等价于 `strip = "symbols"`，会连 `.symtab` 一起移除。
- uniffi 的 `--library` 模式通过静态符号表提取接口元数据，剥掉后
  「从 release 产物生成绑定」这条兜底路径失效。
- `"debuginfo"` 只移除 DWARF，减体积效果接近，且不影响 dlsym 与元数据提取。

---

## 12. 版本目录别名统一 camelCase

**决策**：`gradle/libs.versions.toml` 的 library/plugin 别名全部用 camelCase。

**理由**：Gradle 按 `-`/`_` 分段生成嵌套访问器。若同时存在 `compose-ui` 与
`compose-ui-graphics`，两者都要占用 `libs.compose.ui` 前缀，构建直接失败。
官方文档「Avoiding subgroup accessors」明确建议 camelCase。

---

## 13. 中文脚本必须 UTF-8 with BOM

**决策**：所有 `.kilo/scripts/*.ps1` 都必须保存为 **UTF-8 with BOM**。

**理由**：Windows PowerShell 5.1 对无 BOM 文件按 ANSI（本机 GBK）解码，
中文注释会变成乱码并被当作代码解析，导致语法错误。

**新增脚本时务必保持**；`verify-m0.ps1` 已通过写入 BOM 修复过一次。

---

## 14. 验收脚本必须自解析工具绝对路径

**决策**：`bootstrap.ps1` / `verify-m0.ps1` 不用裸 `cargo` / `adb` / `java`。

**理由**：本机这些工具都不在 PATH（Rust 在 `%USERPROFILE%\.cargo\bin`，
adb 在 `$ANDROID_HOME\platform-tools`，JDK 在 `D:\jdk21`），
而 Gradle 守护进程也不会继承 bootstrap 会话的 PATH。
裸命令名会让脚本在任意新终端里直接失败。

**实现**：`Resolve-Tool` 按候选绝对路径 → `Get-Command` 顺序解析；
Gradle 侧用 `resolveCargo()`。
---

## 15. 繁简转换改用 `zhhz`（Apache-2.0），禁用 `zhconv`

**决策**：繁简转换用 `zhhz 0.8.2`（纯 Rust、词典内嵌、Apache-2.0）。

**理由**：`zhconv` 功能更全，但许可证是 **GPL-2.0-or-later**——打进分发的 APK
会强制整个 App 变为 GPL，与 MIT 仓库冲突。`opencc-rust` / `opencc-sys` 是
C++ OpenCC 的 FFI 绑定，会给 Android 交叉编译引入额外原生依赖。

**实测**：`zhhz::Converter::new(zhhz::Config::T2s).convert("靜夜思") == "静夜思"`。

---

## 16. 语料是《静夜思》的**原始版本**，与流行版本不同

**决策**：忠实保留上游原文，不做「流行版替换」。

**事实**：`chinese-poetry` 全唐诗里的《静夜思》是
「床前**看**月光……举头望**山**月」，而大众熟悉的是
「床前**明**月光……举头望**明**月」。

**影响**：用户按记忆搜「床前明月光」会 **0 命中**（实测确认，属正确行为）。
M2 可考虑加同义/异文提示，M5 可让 LLM 说明异文。当前不特殊处理，避免引入
无法溯源的改写数据。

---

## 17. 拼音派生字段不落盘

**决策**：assets 只存 `pinyin_full`（逐段、带空格）；首字母与紧凑全拼在
**建索引时**由它派生。

**理由**：三个字段内容高度冗余。实测把 `pinyin_initials` / `pinyin_compact`
从 JSONL 去掉后，`poems.jsonl.zst` 从 22.5 MB 降到 **15.05 MB**，
而检索能力完全不变（`jys`、`jingyesi`、`chuangqiankanyueguang` 均有命中）。

---

## 18. 索引在首次启动时构建（模拟器耗时待真机复核）

**决策**：assets 只带语料，不带预建索引；首次启动解压 15 MB 并建索引，
之后复用 `filesDir/index`。

**体积收益**：APT 内不放索引，避免 APK 再涨十几 MB。

**实测**：桌面 3.1–4.4 s；x86_64 模拟器 **65–75 s**，**超出 30 s 目标**。
模拟器无硬件加速与真实 CPU，指标须以 vivo X Fold5 真机复核；若真机仍超标，
则改为「预建索引随 assets 分发」或「后台建索引 + 首页先用作者/标题快速通道」。

---

## 19. 压缩 assets 不能用 `openFd()` 取长度

**决策**：语料展开的「是否需要重新解压」判断，用
`filesDir/corpus/.asset_version` 标记文件（内容为 `versionCode:assetName`）。

**理由**：APK 内 assets 默认压缩，`AssetManager.openFd()` 会抛
`FileNotFoundException: probably compressed`（设备实测崩溃）。
标记文件既能避免每次启动重拷 15 MB，又不依赖 asset 的随机访问能力。

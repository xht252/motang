# 墨唐 motang · 架构决策记录

> 采用本文件记录「与原始方案不一致」或「方案未覆盖但必须拍板」的决策。
> 修改硬约束时必须同步本文件。

---

## 1. 配置目录统一为 `.kilo/`

**决策**：rules / skills / agents / scripts 全部放 `.kilo/`，不使用方案原文的 `.kilocode/`。

**理由**：本仓库环境约定 `.kilo/` 为规范目录（`.kilocode/` 仅作 legacy 兼容）。
两套并存会造成规则重复加载与优先级混淆。

---

## 2. compileSdk / targetSdk 暂锁 35

**决策**：M0 使用 compileSdk 35 / targetSdk 35，而非方案要求的 36。

**理由**：本机 Android SDK 仅安装 `platforms/android-35`，无 platform-36，
且当前会话无法执行 sdkmanager（无 cmdline-tools，且需接受许可）。

**升级清单（装好 platform-36 后执行）**：
1. `sdkmanager "platforms;android-36"`
2. `app/build.gradle.kts` 的 `compileSdk` / `targetSdk` → 36
3. Compose BOM → 更新的版本，material3-adaptive 评估 1.3.x
4. 重新验证：`assembleDebug`、16KB 对齐、targetSdk 36 行为（边到边、FGS 类型、通知）
5. 更新本文件与 `.kilo/memory/MILESTONES.md` 版本矩阵

---

## 3. release `panic = "unwind"`（而非方案要求的 `abort`）

**决策**：整个 workspace 的 release profile 使用 `panic = "unwind"`。

**理由**：
- uniffi 依赖 panic unwind 才能把 Rust panic 包装成 `UniffiRustCallStatus`
  并转成 Kotlin 侧的 `InternalException`；`abort` 会让进程直接终止。
- 方案建议「`motang-ffi` 用 unwind、bin 用 abort」，但 Cargo 明确不支持：
  > Overrides cannot specify the `panic`, `lto`, or `rpath` settings.
  （Cargo Book · Profiles · Overrides）
  即 `[profile.release.package.motang-ffi] panic = "unwind"` 是无效配置。

**补偿措施**：
- 所有 `#[uniffi::export]` 函数返回 `Result<_, MotangError>`，避免 panic 逃逸。
- 禁止在生产路径使用 `unwrap()` / `expect()` / 索引越界，`cargo clippy` 纳入检查。
- M6 若确需 `abort` 的体积收益，需拆分为独立 workspace 或用
  `-C panic=abort` 单独编译 bin crate，届时重新评估。

---

## 4. 唐诗三百首无注释/译文/赏析 → 解释全走 LLM

**决策**：M1 schema 保留 `notes` / `translation` / `appreciation` 字段但恒为 `null`；
M5 解释统一由端侧 LLM 生成并缓存。

**理由**：实测 `chinese-poetry` 的 `全唐诗/唐诗三百首.json` 每条只有
`author` / `title` / `paragraphs` / `tags` / `id` 五个字段，**不含任何注释译文**。
方案原文「预置人工注释优先」在当前数据源下无法实现。

**影响**：
- 规则措辞改为「预置注释**若存在**则优先」，并保留该分支（未来接入自有数据集时即可生效）。
- M5 的免责标注压力更大：所有解释均为 AI 生成。

---

## 5. Gradle wrapper 由 bootstrap 脚本生成

**决策**：`gradle/wrapper/gradle-wrapper.jar` 不在本会话生成，由
`.kilo/scripts/bootstrap.ps1` 的 A5 段解压 Gradle 发行包后执行 `gradle wrapper` 产出。

**理由**：本机无任何 Gradle 安装（无 `gradle` 命令、无 `~/.gradle`、无 Android Studio），
无法离线生成 wrapper jar；且 wrapper jar 是二进制，不应手写。

**影响**：首次构建前必须先跑 bootstrap。生成后 wrapper 应入库。

---

## 6. 绑定从 debug（未 strip）产物生成

**决策**：`generateUniffiBindings` 依赖 `cargoBuildDebug`，从
`build/rustJniLibs/debug/x86_64/libmotang_ffi.so` 读取 uniffi 元数据。

**理由**：debug 产物未做任何 strip，符号表与元数据必然完整；绑定的接口内容
由源码决定，与 debug/release 无关，因此生成的绑定对 release 同样有效。
`strip = "debuginfo"` 已保留 `.symtab`（见第 11 条），release 产物理论上
也可生成绑定，但用 debug 更稳妥。

---

## 7. JNA 为必需依赖（uniffi 0.32 Kotlin 后端）

**决策**：`app` 直接依赖 `net.java.dev.jna:jna:5.19.1`，并在 ProGuard 保留规则中豁免。

**理由**：uniffi 0.32 的 Kotlin 绑定使用 JNA **direct mapping**
（`Native.register`、`com.sun.jna.Callback`、`Structure.ByValue`），
不引入 JNA 会在运行期 `NoClassDefFoundError`。

---

## 8. M0 关闭 R8

**决策**：M0 `release` 的 `isMinifyEnabled = false`。

**理由**：JNA + uniffi 生成的绑定在 M0 尚未验证混淆安全性；
先保证 `assembleRelease` 可通过且能装机验证，M6 再开 full mode。
`proguard-rules.pro` 已预置 keep 规则，M6 可直接启用。

---

## 9. 仅模拟器验证，真机项列为外部依赖

**决策**：M0 验收（B1–B4）在 x86_64 AVD 上执行；真机（vivo X Fold5 / OriginOS）项
在计划中标注为外部依赖，不阻塞 M0。

**理由**：现有可用设备为 HarmonyOS NEXT，不兼容 APK 侧载；本机也无折叠屏真机。
跨设备结论（OriginOS 后台白名单、折叠悬停姿态、T1/T2 档位）必须在真机复核。

---

## 10. 数据量口径以实测为准

**决策**：M1 必须实测入库条数并写入 `MILESTONES.md`，验收指标按实测值重设。

**理由**：方案写「5.7 万首」，上游 `chinese-poetry` README 称「5.5 万首唐诗」。
两者差异会直接影响建索引耗时与检索 P95 指标的解读。

---

## 11. release `strip = "debuginfo"`（不剥离符号表）

**决策**：`[profile.release] strip = "debuginfo"`，而非方案要求的 `strip = true`。

**理由**：
- `strip = true` 等价于 `strip = "symbols"`，会连 `.symtab` 一起移除。
- uniffi 的 `--library` 模式通过 **静态符号表**（`.symtab`）提取接口元数据。
  符号被剥离后，`uniffi-bindgen` 会报找不到元数据，导致「从 release 产物生成
  绑定」这条兜底路径失效（排查 native 问题时很关键）。
- `"debuginfo"` 只移除 DWARF 调试信息，减体积效果接近，且不影响 dlsym
  与元数据提取。

**验证**：M6 若实测体积收益不可忽略，可改为 `strip = "symbols"` 并接受
「绑定只能从 debug .so 生成」的限制——届时同步改 `generateUniffiBindings`。

---

## 12. 版本目录别名统一 camelCase

**决策**：`gradle/libs.versions.toml` 的 library/plugin 别名全部用 camelCase
（如 `composeUi`、`composeBom`、`androidApplication`）。

**理由**：Gradle 按 `-`/`_` 分段生成嵌套访问器。若同时存在 `compose-ui` 与
`compose-ui-graphics`，两者都要占用 `libs.compose.ui` 这个访问器前缀，
Gradle 会因访问器冲突而构建失败。官方文档「Avoiding subgroup accessors」
明确建议用 camelCase 规避。


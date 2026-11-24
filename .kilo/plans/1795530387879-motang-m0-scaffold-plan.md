# 墨唐 motang · M0 脚手架实施计划（M1–M6 路线图）

## 1. 目标与范围

**本计划范围**：把 M0（工具链引导 → 仓库骨架 → Rust workspace → uniffi ping 管线 → 签名/ABI → 校验脚本 → 模拟器冒烟 → `.kilo` 配置）拆成可逐条执行的任务；M1–M6 只写接口契约、验收门槛与阻塞项，各自开工时再出独立计划。

**不在范围**：NPU/QNN 后端、GitHub Actions、应用商店上架、折叠屏真机验证、人工注释数据接入。

**仓库现状**：`E:\github\motang` 为零提交空仓（`main` 无 commit），remote 为 `https://github.com/xht252/motang.git`，仅有 `.kilo/plans/`。

## 2. 已确认决策

| 项 | 决策 | 理由 |
|---|---|---|
| 目录规范 | `.kilo/`（rules / skills / agent / command），不用 `.kilocode/` | 与本仓库环境约定一致；方案原文路径全部改写 |
| toolchain 安装 | **本计划只产出脚本，由你执行**；Agent 不跑 sdkmanager/rustup | 你已确认「先不装，只写计划」 |
| SDK 版本 | M0 用 **compileSdk 35 / targetSdk 35**，platform-36 到位后升 36 | 本机仅有 `android-35`；升级单列一节 |
| 验证设备 | **模拟器（AVD）**，不做真机调试 | 鸿蒙 NEXT 只能靠第三方安装器侧载，adb 不可用 |
| 注释/译文/赏析 | schema 字段**保留但全部可空**，M1 不产出；解释统一走 M5 LLM + 缓存 + 免责标注 | `chinese-poetry` 唐诗三百首 JSON 实测只有 `author/title/paragraphs/tags/id`，**无注释译文**；避免版权与虚构数据 |
| 分发 | 自签直装，不上 Play；`:llm` 打进 APK | v2.1 决策 |

### 2.1 关键事实核对（会改方案的地方）

1. **三百首无注释**：已核对 `全唐诗/唐诗三百首.json` 字段，确认无 `notes/translation/appreciation`。原 v1「预置人工注释优先」改为「若存在则优先」，实现上当前恒为空。
2. **数据量口径**：上游 README 称「5.5 万首唐诗」，方案写「5.7 万」。M1 必须实测入库条数并写入 Memory Bank，验收指标按实测值重设。
3. **`panic="abort"` 与 uniffi 冲突**：uniffi 依赖 panic unwind 才能把 Rust panic 转成 Kotlin 异常；`panic="abort"` 会让任何 panic 直接杀进程。**M0 定为：`motang-ffi` 用 `panic="unwind"`；`motang-etl` / `motang-search` 的 bin 用 `panic="abort"`。** M6 再评估体积收益。
4. **Gradle wrapper 缺失**：本机无 `gradle`、无 `~/.gradle`、无 Android Studio，**无法用 `gradle wrapper` 生成 wrapper**。引导步骤必须显式解决（解压 Gradle 发行包后用其 `gradle wrapper`）。
5. **无 cmdline-tools**：`D:\SDK` 只有 platform-tools / build-tools / platforms / emulator / system-images，缺 `cmdline-tools`（即无 `sdkmanager`、`avdmanager`），也**未创建任何 AVD**。
6. **JAVA_HOME / ANDROID_HOME 均未设置**：JDK 在 `D:\jdk21`（21.0.7），SDK 在 `D:\SDK`。

## 3. 环境差距清单

| 组件 | 现状 | 需要 |
|---|---|---|
| JDK | `D:\jdk21` 21.0.7，未设 `JAVA_HOME` | 设 `JAVA_HOME` |
| Android SDK | `D:\SDK`（platforms: 35；build-tools: 34/35/36；system-images: android-36 google_apis_playstore x86_64） | `ANDROID_HOME`、`local.properties` |
| cmdline-tools | 缺失 | 下载解压（提供 `sdkmanager`/`avdmanager`） |
| platform-36 | 缺失（35 存在） | 可选，升 36 时安装 |
| NDK | 缺失 | r28（r27+ 亦可），`ANDROID_NDK_HOME` |
| Rust | 缺失（无 rustup/cargo） | rustup + `aarch64-linux-android`、`x86_64-linux-android` |
| cargo-ndk | 缺失 | `cargo install cargo-ndk`（4.1.x） |
| Gradle | 缺失 | 发行包解压 + 生成 wrapper（8.14.x） |
| AVD | 无 | 用 `pixel_fold` 设备档案创建可折叠 AVD |

## 4. 阶段 A · 工具链引导（你执行）

产出：`.kilo/scripts/bootstrap.ps1`（幂等、每步可单独注释掉）。Agent 负责写脚本，你负责跑。

**A1 环境变量**（当前会话与永久用户级）
```powershell
[Environment]::SetEnvironmentVariable('JAVA_HOME','D:\jdk21','User')
[Environment]::SetEnvironmentVariable('ANDROID_HOME','D:\SDK','User')
[Environment]::SetEnvironmentVariable('ANDROID_NDK_HOME',"$env:ANDROID_HOME\ndk\28.2.13676358",'User')
```

**A2 cmdline-tools**：从 `https://dl.google.com/android/repository/commandlinetools-win-<build>_latest.zip` 下载，解压到 `D:\SDK\cmdline-tools\latest`（zip 内层目录需重命名为 `latest`），校验 `sdkmanager.bat --version` 可用。

**A3 SDK 组件**（`sdkmanager --licenses` 先行）
```powershell
sdkmanager "platform-tools" "platforms;android-36" "ndk;28.2.13676358" "emulator"
sdkmanager --list | Select-String "system-images;android-36"
```
> 若 A3 失败，M0 用 `platforms;android-35` 继续（编译可行），只阻塞 AVD 冒烟的 API 36 部分。

**A4 Rust**
```powershell
winget install Rustlang.Rustup   # 或 rustup-init.exe
rustup default stable
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
cargo ndk --version
```

**A5 Gradle**
```powershell
# 下载 gradle-8.14.3-bin.zip 到 C:\Users\30669\AppData\Local\Temp\kilo，解压
& "$env:TEMP\kilo\gradle-8.14.3\bin\gradle.bat" --version
# wrapper 由骨架创建后生成（见 C1）
```

**A6 AVD**（可折叠档案，供 M0 冒烟与后续 M2）
```powershell
avdmanager create avd -n motang_fold -k "system-images;android-36;google_apis_playstore;x86_64" -d pixel_fold
$env:ANDROID_HOME\emulator\emulator.exe -avd motang_fold -no-snapshot -gpu swiftshader_indirect
adb devices   # 期望出现 emulator-5554
adb shell cmd device_state print-states    # 记录可用折叠态 id
```
> 若 `pixel_fold` 档案不存在，用 `avdmanager list device` 选最接近的可折叠/平板档案，并在 Memory Bank 记录取舍。

**A7 引导自检**（脚本末尾输出一张表）：`java -version`、`sdkmanager --version`、`cargo -V`、`rustup target list --installed`、`cargo ndk --version`、`gradle -v`、`adb devices`、`Test-Path $env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt`.

## 5. 阶段 B · 仓库骨架

```
motang/
├─ settings.gradle.kts            # pluginManagement + dependencyResolutionManagement(Fail on project repos)
├─ build.gradle.kts               # 根：plugins alias apply false
├─ gradle/libs.versions.toml      # 唯一版本来源
├─ gradle.properties              # AndroidX、Kotlin code style、jvmargs、motang 签名读取
├─ gradlew / gradlew.bat / gradle/wrapper/{jar,properties}
├─ local.properties               # sdk.dir=D\:\\SDK（gitignore）
├─ .gitignore                     # build/ .gradle/ local.properties *.jks keystore.properties .kilo/worktrees/
├─ app/
│  └─ src/main/{AndroidManifest.xml, kotlin/io/github/xht252/motang/**, res/**, assets/}
├─ core-rs/
│  ├─ Cargo.toml                  # [workspace] members + [workspace.dependencies]
│  ├─ rust-toolchain.toml         # channel = stable
│  ├─ .cargo/config.toml          # 目标链接参数（见 D3）
│  ├─ motang-etl/                 # bin
│  ├─ motang-search/              # lib
│  └─ motang-ffi/                 # lib(cdylib) + bin(uniffi-bindgen)
├─ benchmark/                     # 空模块骨架（M6 填充）
├─ keystore/                      # 不入库，只放 README 说明
├─ .kilo/{rules,skills,agent,command,scripts,memory}/
└─ docs/                          # 数据出处声明、字体许可（M1/M2 填）
```

### 5.1 版本矩阵（写入 `gradle/libs.versions.toml`，以 Context7 复核后再定稿）

| 坐标 | 版本 | 备注 |
|---|---|---|
| AGP | 8.13.2 | 支持 compileSdk 35/36 |
| Gradle | 8.14.3 | 与 AGP 8.13 匹配 |
| Kotlin | 2.2.20 | Compose Compiler 随 Kotlin |
| KSP | 对齐 Kotlin 2.2.20 的版本 | Room 注解处理 |
| JDK / jvmTarget | 21 / 17 | `D:\jdk21` |
| Compose BOM | 2025.06.01 | compileSdk 35 兼容；升 36 时换 2026.11.01 |
| material3-adaptive (`adaptive`/`adaptive-layout`/`adaptive-navigation`) | 1.1.0 | 升 36 时评估 1.3.0 |
| androidx.window | 1.3.0 | WindowInfoTracker / FoldingFeature |
| Room | 当前稳定 | KSP |
| DataStore | 当前稳定 | preferences |
| Navigation Compose | 当前稳定 | 单 Activity |
| uniffi (Rust) | 0.32.x | 生成 Kotlin |
| tantivy | 待定（M1） | 见 M1 依赖配对风险 |
| jieba-rs | 0.11.x | 内嵌词典，影响 .so 体积 |
| pinyin | 0.11.x | |
| cargo-ndk | 4.1.x | |

### 5.2 清单
- **B1** `local.properties` 写 `sdk.dir`；`.gitignore` 覆盖 `local.properties`、`**/build/`、`.gradle/`、`*.jks`、`keystore.properties`。
- **B2** `settings.gradle.kts` 只 include `:app`（benchmark 模块在 M6 前不加，避免 AGP test 模块配置分心）。
- **B3** Manifest：`resizeableActivity="true"`，**不写** `screenOrientation`，**不写** `configChanges` 的 `screenSize|smallestScreenSize|screenLayout|density`；`minSdk 26`、`targetSdk 35`。
- **B4** `MainActivity`：单 Activity + `enableEdgeToEdge()` + 一个 Compose 页面，显示 `MotangCore().ping()` 返回值与 ABI/进程信息（用于冒烟）。

## 6. 阶段 C · Rust workspace 与 uniffi

**C1** `cargo new` 三个 crate，`core-rs/Cargo.toml` 用 `[workspace.dependencies]` 统一 uniffi 版本。
**C2** `motang-ffi/Cargo.toml`：`crate-type = ["cdylib", "lib"]`；`uniffi` 依赖开 `cli`/`build` 所需 feature；加
```toml
[[bin]]
name = "uniffi-bindgen"
path = "src/bin/uniffi-bindgen.rs"
```
`src/bin/uniffi-bindgen.rs` = `fn main() { uniffi::uniffi_bindgen_main() }`。
> 目的：绑定生成器与库**同版本同 crate**，避免全局 `cargo install uniffi-bindgen` 版本漂移。

**C3** 导出面（M0 只此两个）：
```rust
#[uniffi::export] pub fn ping() -> String;           // 返回 "motang-ffi <ver> <abi>"
#[uniffi::export] pub fn core_version() -> String;
```
`uniffi::setup_scaffolding!()` 置于 crate 根。所有导出函数返回 `Result<_, MotangError>` 或纯值，**禁止 unwrap**（因 unwind 策略见 §2.1(3)）。

**C4** `.cargo/config.toml`：
```toml
[target.aarch64-linux-android]
rustflags = ["-C","link-arg=-Wl,-z,max-page-size=16384"]
[target.x86_64-linux-android]
rustflags = ["-C","link-arg=-Wl,-z,max-page-size=16384"]
```
**C5** `core-rs/Cargo.toml` 的 `[profile.release]`：`opt-level=3`、`lto="fat"`、`strip=true`、`panic="abort"`；`[profile.release.package.motang-ffi] panic="unwind"`。

**C6** 本地验证（不依赖 Gradle）：
```powershell
cargo test --workspace
cargo ndk -t arm64-v8a -t x86_64 -o ..\app\build\rustJniLibs build --release -p motang-ffi
```

## 7. 阶段 D · Gradle ↔ Rust 管线

**D1** 任务图（`app/build.gradle.kts`）：
- `cargoBuild<Debug|Release>`：`Exec` 调 `cargo ndk -t … -o <app.buildDir>/rustJniLibs build`（debug 用 `--profile dev`，release 用 `--release`）；注入 `ANDROID_NDK_HOME`；release 只 `arm64-v8a`，debug 加 `x86_64`。
- `generateUniffiBindings`：`Exec` 跑 `cargo run -p motang-ffi --bin uniffi-bindgen -- generate --library <so> --language kotlin --out-dir <app.buildDir>/generated/uniffi`；`dependsOn` 对应 `cargoBuild`。
- 接线：`preBuild.dependsOn(generateUniffiBindings)`；`android.sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/uniffi"))`；`sourceSets["main"].jniLibs.srcDirs(layout.buildDirectory.dir("rustJniLibs"))`。
- 已解压/未解压混用时，绑定生成优先用 **debug 未 strip 产物**；若 release strip 后 metadata 读不到，改用 `--library` 指向 debug .so。
- 任务输入输出声明（`.so`、生成目录、Rust 源目录）以启用增量构建，避免每次全量重编。
- **Windows 路径**：`Exec` 里用 `workingDir = rootProject.file("core-rs")`，路径不做字符串拼接的引号假设。

**D2** 包名与 so 名对齐：crate `motang-ffi` → `libmotang_ffi.so`；JNI 绑定 `System.loadLibrary("motang_ffi")` 由 uniffi 生成代码处理，不要在 Kotlin 手写。

**D3** 16KB 对齐校验脚本 `.kilo/scripts/check-16k-pagesize.ps1`：
```powershell
$readelf = "$env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe"
& $readelf -lW <so> | Select-String "LOAD"   # 断言 align 列为 0x4000 或更大
```
M0 作为**硬门槛**（本机可跑）；M6 保留为警告项。

**D4** 签名（M0 定稿，后续不可换）：
```powershell
keytool -genkeypair -v -keystore keystore\motang-release.jks -alias motang -keyalg RSA -keysize 4096 -validity 10000
```
- `keystore/`、`keystore.properties` 全部 gitignore；口令经 `~/.gradle/gradle.properties`（`MOTANG_STORE_FILE` / `MOTANG_STORE_PASSWORD` / `MOTANG_KEY_ALIAS` / `MOTANG_KEY_PASSWORD`）或环境变量注入。
- `signingConfigs` 缺省时 **回退 debug 签名并打印 WARN**，不阻断他人构建；但**首次装机必须用 release 签名**，否则后续无法覆盖安装。
- 记录 `keytool -list -v` 的 SHA-256 到 Memory Bank。

**D5** ABI：`ndk { abiFilters += if (release) ["arm64-v8a"] else ["arm64-v8a","x86_64"] }`；`splits` 不启用。

**D6** 版本号规则（写入 Memory Bank）：`versionCode = major*10000 + minor*100 + patch`，`versionName` 语义化，起始 `1.0.0 / 10000`。

## 8. 阶段 E · 校验与冒烟

`.kilo/scripts/verify-m0.ps1` 顺序执行并输出 PASS/FAIL 表：

| ID | 检查 | 命令 | 门槛 |
|---|---|---|---|
| A1 | Rust 单测 | `cargo test --workspace` | 全绿 |
| A2 | Debug 构建 | `.\gradlew.bat :app:assembleDebug` | BUILD SUCCESSFUL |
| A3 | Release 构建 | `.\gradlew.bat :app:assembleRelease` | BUILD SUCCESSFUL |
| A4 | .so 存在 | `unzip -l app-release.apk` 查 `lib/arm64-v8a/libmotang_ffi.so` | 存在且 release 包内**无** `lib/x86_64` |
| A5 | 16KB 对齐 | D3 脚本（两个 ABI） | LOAD align ≥ 0x4000 |
| A6 | Kotlin 绑定生成 | `Test-Path app/build/generated/uniffi/.../motang_ffi.kt` | 存在，且可被 `:app` 编译引用 |
| A7 | APK 体积 | `Get-Item app-release.apk` | ≤ 40 MB（M0 应远小于） |
| B1 | 模拟器启动 | `adb devices` | emulator 在线 |
| B2 | 安装启动 | `adb install -r` + `adb shell am start` | 无 crash（`adb logcat -d *:E` 无 FATAL） |
| B3 | ping() 冒烟 | UI 文本 / `adb logcat` 关键字 | 显示 `motang-ffi … arm64-v8a`（或 x86_64，模拟器 ABI 为准） |
| B4 | 三档窗口骨架 | 360 / 820 / 940 dp 截图（`adb shell wm size`） | 不崩溃、无溢出 |

## 9. 阶段 F · `.kilo` 配置

| 文件 | 内容要点 |
|---|---|
| `.kilo/rules/project.md` | v2.1 硬规则：命名/工具链/16KB/自适应硬规则（WindowSizeClass 唯一依据）/线程/内容合规/Agent 纪律；补 ABI 与签名条款；**补 panic 策略**；注释数据条款改为「若存在优先」 |
| `.kilo/skills/`（9 个 SKILL.md） | `kotlin-compose-ui`、`rust-ffi-uniffi`、`motang-data-etl`、`chinese-search`、`android-reminders`、`study-stats`、`ondevice-llm`、`perf-release`、`foldable-adaptive-ui`（M0 只建目录 + 各 5–15 行骨架，M1/M2 开工前补全正文） |
| `.kilo/agent/architect.md` | `mode: subagent`，只产出设计文档与接口契约，禁止写码；M1/M5 前调用 |
| `.kilo/agent/debug.md` | 排障专用，允许 `adb logcat`/`gradlew --stacktrace`；禁止改 Gradle/Rust 版本 |
| `.kilo/memory/MILESTONES.md` | 里程碑状态、接口契约、版本矩阵、签名指纹、实测数据量、AVD 档案与可折叠态 id |
| `.kilo/memory/DECISIONS.md` | 本计划 §2 与 §2.1 全部决策（含四条事实纠偏） |
| `AGENTS.md`（仓库根） | 最短入口：指向上表三处；写明「本机 compileSdk 35 过渡」「仅模拟器验证」 |

## 10. M0 完成定义

全部满足才算 M0 完成：
1. 阶段 A 你已执行且 A7 自检全绿；
2. 阶段 E 表格 A1–A7 + B1–B4 全部 PASS；
3. `git commit` 至少 3 个中文提交（骨架 / Rust 管线 / `.kilo` 配置），无 `local.properties`、`*.jks`、`build/` 入库；
4. Memory Bank 的 `MILESTONES.md` 已写入 M0 状态、版本矩阵、签名指纹、实测 ABI 与 .so 对齐结果；
5. 输出 M1 子任务清单（可直接作为下一个计划的输入）。

### 若中途卡住的处理
- 卡在 A2/A3（下载/许可）：M0 降级为 compileSdk 35 + 跳过 AVD 的 API 36 部分，B1–B4 用 `android-35` 镜像 AVD；在 Memory Bank 标 `SDK-36-BLOCKED`。
- 卡在 D1（Gradle 调 cargo）：先用 C6 手工产物 + `jniLibs.srcDirs` 指向固定目录，把任务接线拆成独立子任务，不让 M0 其余项阻塞。
- 卡在 D4（签名口令）：先只做 debug 签名，release 签名任务单列并标记为「首次安装前必须完成」。

## 11. M1–M6 路线图

### M1 · 诗库数据 + Rust 检索
- **交付**：`motang-etl` CLI（下载/清洗/繁简/拼音/去重/压缩）；`motang-search`（tantivy schema + jieba + 拼音全拼/首字母）；`motang-ffi` 导出 `search / get_poem / list_authors / build_index(progress)`。
- **Schema v1（JSONL）**：`id, title, author, dynasty, paragraphs[], paragraphs_simplified[], pinyin_full[], pinyin_initials[], tags[], notes?, translation?, appreciation?, source`。`notes/translation/appreciation` **恒为 null（M1）**。
- **接口契约**：`search(query, author_filter: Option<String>, page: u32, page_size: u32) -> SearchResult { hits: Vec<Hit{poem_id,title,author,snippet,score}>, total: u64 }`；分页粗粒度（一次一页），禁止逐条跨边界。
- **验收**：`cargo test` 覆盖分词/拼音/去重/繁简；模拟器建索引耗时可测（目标 ≤ 30 s，**以实测为准并更新指标**）；`明月` / `libai` / `jys` P95 ≤ 50 ms；Instrumented test 走通 Kotlin→Rust 调用链。
- **阻塞/风险**：① `tantivy` 与 `tantivy-jieba` 版本配对（jieba 适配层对 tokenizer-api 版本敏感，需先锁定组合再写代码）；② jieba 内嵌词典体积对 .so 与 APK 的影响；③ 5.5 万/5.7 万口径实测；④ 多音字导致首字母歧义（长/重/行），需定义「首字母取主读音」并写单测。

### M2 · 自适应水墨 UI
- **交付**：设计令牌（宣纸/墨/黛蓝/赭石/竹青/胭脂 + 霞鹜文楷）、`NavigationSuiteScaffold` + `NavigableListDetailPaneScaffold`、Tabletop/Book 姿态分区、竖排 Layout、远山/噪点 Canvas、夜读主题、六态 PREVIEW。
- **依赖**：M1 的 schema 与 `search` 契约；adaptive 1.1.0 或升 36 后的 1.3.0。
- **验收**：六态截图（360/420/820/940 + Tabletop/Book）；320 dp 全功能；开合 20 次零状态丢失；滚动 jank < 1%。
- **风险**：AVD 的 `device_state` 未必能稳定产生 HALF_OPENED，姿态验证可能需要 hardware profile 调整或延后到折叠真机。

### M3 · 定时提醒 + OriginOS 兼容
- **权限矩阵**：API 26–30 直接 `setExactAndAllowWhileIdle`；31–32 `SCHEDULE_EXACT_ALARM`（`maxSdkVersion=32`）；33+ `USE_EXACT_ALARM`。13+ 仍请求 `POST_NOTIFICATIONS`；保留 `canScheduleExactAlarms` 防御检查 + WorkManager flex 降级。
- **交付**：DataStore 提醒时间/开关、链式注册、BOOT/TIMEZONE/REPLACED 重注册、OriginOS 引导页（`Build.MANUFACTURER == "vivo"`）、通知直达打卡页、streak。
- **验收**：模拟器重启后闹钟仍生效；streak 跨月/时区单测。**OriginOS 真机项不可在本机验证，标记为外部依赖。**

### M4 · 背诵记录与统计
- **交付**：会话计时（`active_ms` 口径）、`ON_STOP` 暂停/`ON_START` 恢复、前台下计时器、`mastered` 标记、Room 聚合、周柱状图 + 月热力图、悬停背诵模式。
- **验收**：进程被杀后可恢复或安全结算；开合 10 次计时误差 < 1 s；聚合结果与手工构造数据逐项一致（含跨周/月/时区）。

### M5 · 端侧 LLM（先 Architect 后 Code）
- **先行契约**：`model_catalog.json` schema（id/源/SHA256/minRam/ctx/tier）、档位探测状态机（`SOC_MODEL` + `MemTotal` + 64-token 微基准）、下载状态机、`:llm` 绑定协议、流式回调、缓存键 `sha256(poem_id+prompt_ver+model_id)`。
- **交付**：`:llm` 独立进程 Service、llama.cpp + `-DGGML_CPU_KLEIDIAI=ON`、三档模型目录、断点续传下载、解释 Tab 流式渲染、免责标注、热约束监听。
- **注意**：因 M1 无人工注释，**所有解释均由 LLM 生成**，「预置优先」分支保留但空实现；需在 UI 与文档中更明确免责。
- **验收**：T1 首 token ≤ 2 s / ≥ 15 tok/s；飞行模式缓存可读；推理期 UI jank < 1%；杀 `:llm` 不影响主进程。

### M6 · 性能与直装发布
- **交付**：Baseline Profile（启动/检索/详情/统计）、Macrobenchmark（冷启动/检索/列表 jank/resize）、连续性 Instrumented test、R8 full + 资源收缩、arm64-only、`motang-v{name}-{code}.apk`、导出/导入（Room 全表 + DataStore → zip + manifest + 校验和，SAF）。
- **验收**：冷启动 ≤ 1 s；检索 P95 ≤ 50 ms；滚动/开合 jank < 1%；覆盖安装数据无损；导出→卸载→重装→导入 后逐项一致。

## 12. 全局风险台账

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| 1 | 无折叠真机，鸿蒙 NEXT 无法 adb | M2/M4 姿态与开合验收不可完成 | AVD `device_state` + 软门槛；真机项列入外部依赖 |
| 2 | Gradle wrapper 需先有 Gradle | 骨架起不来 | A5 解压发行包生成 wrapper，wrapper 入库 |
| 3 | uniffi bindgen 版本漂移 | 生成代码与 .so 不匹配、运行时崩 | bindgen 与库同 crate（C2），CI/本地脚本校验版本 |
| 4 | `strip=true` 影响 `--library` 元数据读取 | 绑定生成失败 | 用 debug 未 strip .so 生成绑定 |
| 5 | `panic="abort"` 杀进程 | 任意 FFI panic 崩溃 | ffi crate 用 unwind，入口全 Result |
| 6 | tantivy/jieba 版本配对 | M1 编译或分词行为异常 | M1 先做依赖锁定 spike，再写业务 |
| 7 | 5.5/5.7 万口径不符 | 验收指标失真 | 实测入库并更新指标 |
| 8 | 多音字首字母 | 拼音检索漏命中 | 定主读音策略 + 单测 + 接受已知偏差清单 |
| 9 | 签名不一致 | 覆盖安装失败、数据丢失 | M0 定稿 release keystore、首次装机即用 |
| 10 | AVD 折叠档案缺失/不可用 | B4 与 M2 验证受阻 | `avdmanager list device` 选替代档案并记录 |

## 13. 首个提交建议（中文 commit）

1. `chore: 初始化 motang 工程骨架与版本目录`
2. `feat(core-rs): 建立 motang-etl/search/ffi workspace 与 uniffi ping 导出`
3. `build(app): 接线 cargo-ndk、uniffi 绑定生成与 jniLibs`
4. `build: 固定 release 签名与 ABI 策略`
5. `chore(kilo): 加入 rules、skills 骨架与里程碑 Memory Bank`
6. `test: 加入 M0 校验脚本与模拟器冒烟记录`

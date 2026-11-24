# 墨唐 motang · 项目硬性约束（所有模式必须遵守）

## 工程与命名
- 仓库 motang；applicationId `io.github.xht252.motang`；Rust crates：
  `motang-etl` / `motang-search` / `motang-ffi`；JNI 产物 `libmotang_ffi.so`。
- Kotlin 2.x + Rust stable；Gradle KTS + Version Catalog（唯一版本来源）。
- **SDK 版本（M0 现状）**：compileSdk/targetSdk = **35**，minSdk 26。
  本机仅有 platform-35；需要升 36 时按 `.kilo/memory/DECISIONS.md` 的升级清单执行，
  禁止只改数字不看依赖是否要求 36。
- Rust 目标 aarch64/x86_64-linux-android；NDK r27+（当前 28.2.13676358）；
  链接参数 `-Wl,-z,max-page-size=16384`；release：opt-level=3、lto="fat"、
  strip=true、codegen-units=1、**panic="unwind"**。
- 禁止引入 Python 运行时。

## 自适应硬规则（违反即打回）
- Manifest：`resizeableActivity=true`；禁止锁定 `screenOrientation`；
  禁止用 `configChanges` 拦截 `screenSize`/`smallestScreenSize`/`screenLayout`/`density`。
- 布局决策唯一依据 = 当前窗口 WindowSizeClass（material3-adaptive）；
  严禁以折叠状态、`Build.MODEL`、屏幕英寸数选择布局。
- `FoldingFeature`（androidx.window）仅用于：悬停姿态（Tabletop/Book）与铰链区避让；
  不得用于选择单栏/双栏。
- 检索↔详情用 `NavigableListDetailPaneScaffold`；导航用 `NavigationSuiteScaffold`
  （Compact 底部栏 / Medium+ 侧边 Rail）。
- 全部页面在 320dp 宽度（分屏/小窗）下功能完整；文本区最大行宽 ≤ 600dp。
- 折叠/展开、旋转、分屏切换零状态丢失：滚动位置、背诵会话、LLM 流式输出
  必须经 ViewModel + `rememberSaveable` 延续。

## 性能与线程
- 主线程禁止 ffi 调用与磁盘/网络 IO；统一 `Dispatchers.IO`/`Dispatchers.Default`。
- UI 全 Compose；`LazyColumn` + 稳定 key；禁止 XML 布局。
- llama.cpp 构建含 `-DGGML_CPU_KLEIDIAI=ON`；推理仅在 `:llm` 独立进程。
- 模型档位由运行时探测（`SOC_MODEL` + MemTotal + 首跑微基准）决定，
  模型目录 `model_catalog.json` 数据驱动，禁止在代码中写死机型白名单。

## Rust ↔ Kotlin 边界
- 导出函数一律返回 `Result<_, MotangError>` 或纯值；**禁止 unwrap/expect**。
- 接口粗粒度：一次调用返回一页结果，禁止跨语言逐条传对象。
- 绑定生成器与库同 crate（`cargo run -p motang-ffi --bin uniffi-bindgen`），
  禁止 `cargo install uniffi-bindgen` 造成版本漂移。
- 禁止手写 JNI；禁止把大字符串按字符跨边界传递。

## 分发与权限
- 分发方式：自签名 APK 直装，不上架 Google Play；禁止引入 Play Feature Delivery /
  Play Integrity 等 GMS 依赖（国行设备无 GMS）。
- release keystore 自 M0 固定不变；keystore 文件与口令不入仓库
  （`.gitignore` 已排除，经 `~/.gradle/gradle.properties` 或环境变量注入）。
- 精确闹钟：API 33+ 声明 `USE_EXACT_ALARM`（安装即授予）；
  API 31–32 声明 `SCHEDULE_EXACT_ALARM`（`maxSdkVersion=32`）；
  保留 `canScheduleExactAlarms` 防御性检查与 WorkManager 降级链。
- 16KB 对齐：保留链接参数；M0 作为硬门槛校验，M6 起降为警告项。
- ABI：release 仅 `arm64-v8a`；`x86_64` 仅供 debug/模拟器。

## 内容与合规
- LLM 输出固定标注"AI 生成，仅供参考"；预置人工注释**若存在**则优先于生成结果。
- 字体霞鹜文楷（OFL）；数据 chinese-poetry（保留出处声明）；
  禁止版权不明注释（当前 `chinese-poetry` 唐诗三百首**不含**注释/译文/赏析）。
- 不得虚构典故出处；不确定处以"存疑"标注。

## Agent 纪律
- 每个任务结束 `./gradlew assembleDebug` 与 `cargo test` 必须通过。
- 小步提交，commit message 用中文。
- 不确定 API 一律先查文档（uniffi / llama.cpp / material3-adaptive / WindowManager），
  禁止凭记忆编造接口。
- 新增/修改硬约束时同步更新 `.kilo/memory/DECISIONS.md`。

# 墨唐 motang · 里程碑状态

> 每个里程碑完成后更新本文件；下一个里程碑开始时先读本文件与 DECISIONS.md。

## 当前状态

| 里程碑 | 状态 | 备注 |
|---|---|---|
| M0 脚手架 | **已完成（11/11 PASS）** | 模拟器实测 |
| M1 数据 + 检索 | **已完成（C1–C4 PASS）** | 全量 853,349 首；设备端四类查询通过 |
| M2 自适应 UI | **进行中（UI 骨架已落地，待字体/截图测试）** | 见下 |
| M3 提醒 | **已完成（通知/重启已实测）** | 见 M3 小节 |
| M4 统计 | **已完成** | 背单词式打卡/已背会/统计/PDF |
| M5 端侧 LLM | **进行中（原生库已交叉编译）** | 见 M5 小节 |
| M6 性能发布 | 未开始 | |

## M0 交付物

- Gradle 骨架：根 `build.gradle.kts`、`settings.gradle.kts`、`gradle/libs.versions.toml`、
  `gradle.properties`、`app/build.gradle.kts`、`app/proguard-rules.pro`。
- Rust workspace：`core-rs/{Cargo.toml,rust-toolchain.toml,.cargo/config.toml}` +
  三个 crate（`motang-etl` bin、`motang-search` lib、`motang-ffi` cdylib + bindgen bin）。
- Android 侧：`MainActivity`（单 Activity + 边到边）、`MotangApplication`（native 预热）、
  `ui/MotangApp.kt`（三个宽度档 Preview）、`ui/theme/Theme.kt`（水墨令牌初版）。
- 脚本：`.kilo/scripts/{bootstrap,verify-m0,check-16k-pagesize,selftest-check16k}.ps1`。
- 配置入口：`kilo.json`（把 `.kilo/rules/project.md` 加入 `instructions`，
  否则 rules 不会被自动加载）、根 `AGENTS.md`。

### 本会话已验证 / 未验证

**已验证（实测，2026-11-25）**

| ID | 检查 | 实测结果 |
|---|---|---|
| A1 | cargo test --workspace | 4 个测试全绿 |
| A2 | assembleDebug | SUCCESS |
| A3 | assembleRelease | SUCCESS（已签名） |
| A4 | .so 存在 / ABI | arm64=True, x86_64=False, signed=True |
| A5 | 16KB 对齐 | 两个 ABI 的 LOAD align 均 0x4000 |
| A6 | uniffi Kotlin 绑定 | 1 个文件，含 `fun ping` |
| A7 | APK 体积 | 8.47 MB（上限 40） |
| B1 | 模拟器在线 | motang_fold, boot_completed=1 |
| B2 | 安装启动 | Success（签名冲突，卸载重装一次） |
| B3 | ping() | `motang-ffi 0.1.0 / motang-search 0.1.0 / abi x86_64 / core 0.1.0` |
| B4 | 320/820/940dp | density=420，三档均不崩溃 |

**尚未验证**
- arm64 真机（无设备）。
- 折叠悬停（Tabletop/Book）与 `cmd device_state` 实态切换。
- 覆盖安装数据无损（M6 才涉及 Room 持久数据）。

## 版本矩阵（与 gradle/libs.versions.toml 同步）

| 坐标 | 版本 |
|---|---|
| AGP | 8.13.2 |
| Gradle | 8.14.3 |
| Kotlin / KSP | 2.2.20 / 2.2.20-2.0.4 |
| JDK / jvmTarget | 21 / 17 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Compose BOM | 2025.06.01 |
| material3-adaptive | 1.1.0 |
| androidx.window | 1.3.0 |
| Room / DataStore | 2.7.2 / 1.1.7 |
| JNA | 5.19.1 |
| uniffi (Rust) | 0.32.2（=0.32.2 精确锁定） |
| NDK | 28.2.13676358 |

## 待填（验收后写入）

- [x] 实测入库诗数：**57444 首**（去重丢弃 529）— 见下方 M1 结果
- [x] release keystore SHA-256 指纹
- [x] `.so` LOAD 段 align 实测值（arm64-v8a / x86_64 均 0x4000）
- [x] `ping()` 实际输出
- [x] AVD 档案名与可用折叠态 id
- [x] release APK 体积

## M1 实测结果（2026-11-25，全量诗歌库）

| 项 | 结果 |
|---|---|
| 数据源 | `data/Poetry`（MIT, Copyright 2018 Werner），简体，34 个 CSV |
| 语料入库 | **853,349 首**（去重丢弃 36 首） |
| 作者数 | 28,424 |
| ETL 产物 | `poems.jsonl.zst` 111.51 MB + `poems.authors.json` 0.30 MB |
| 拼音覆盖 | 标题 + 作者（正文不生成，省 ~60 MB） |
| 索引内容 | 仅 标题 / 作者 / 拼音（正文不进倒排） |
| 索引体积 | 105.9 MB |
| 建索引（桌面） | 6.4 s |
| 建索引（模拟器） | **117.9 s**（真机待测） |
| 索引通道 P95 | 最差 2.82 ms（目标 ≤ 50 ms） |
| 正文扫描 | 0–739 ms（常见词提前终止；生僻长句需扫完） |
| release APK | **129.02 MB**（arm64-only） |
| Rust 单测 | 21 个全绿 |

**体积说明**：原「APK ≤ 40 MB」已废止（DECISIONS 第 20 条）。
M2 引入霞鹜文楷时需评估字体子集化，避免再涨 10–20 MB。
## 接口契约（已定）

```
// M0
fn ping() -> Result<String, MotangError>
fn core_version() -> Result<String, MotangError>

// M1（已实现；open_index / get_poem 需要语料路径）
fn open_index(corpus_path: String, index_dir: String) -> Result<(), MotangError>
fn build_index(corpus_path: String, index_dir: String) -> Result<BuildStats, MotangError>
fn search(query: String, page: u32, page_size: u32) -> Result<Page, MotangError>
fn get_poem(corpus_path: String, id: String) -> Result<Option<PoemView>, MotangError>
fn list_authors(meta_dir: String, prefix: String, limit: u32) -> Result<Vec<String>, MotangError>
```

## 数据 Schema v1（M1，JSONL，简体）

```json
{
  "id": "mt-<fnv64>",
  "title": "string",
  "author": "string",
  "dynasty": "宋",
  "paragraphs": ["string（按句号切分）"],
  "pinyin_full": ["（默认档为空）"],
  "title_pinyin_full": "jing ye si",
  "author_pinyin_full": "li bai",
  "tags": [],
  "notes": null,
  "translation": null,
  "appreciation": null,
  "source": "Poetry, MIT, Copyright 2018 Werner"
}
```

**不落盘的派生字段**（建索引时计算）：`title_initials` / `author_initials` /
`title_pinyin`（去空格紧凑）等均由 `*_pinyin_full` 派生。

`notes` / `translation` / `appreciation` 恒为 `null`（数据源不含），
解释统一走 M5 端侧 LLM + 缓存。
## 验证结果

> 由 `.kilo/scripts/verify-m0.ps1` 填写。本机当前无 Rust/Gradle/NDK，尚未执行。

| ID | 检查 | 结果 |
|---|---|---|
| A1 | cargo test --workspace | 未执行 |
| A2 | assembleDebug | 未执行 |
| A3 | assembleRelease | 未执行 |
| A4 | .so 存在且 release 仅 arm64 | 未执行 |
| A5 | 16KB 对齐 | 未执行 |
| A6 | uniffi 绑定生成 | 未执行 |
| A7 | APK ≤ 40MB | 未执行 |
| B1 | 模拟器在线 | 未执行 |
| B2 | 安装启动 | 未执行 |
| B3 | ping() 无崩溃 | 未执行 |
| B4 | 三档窗口不崩溃 | 未执行 |
## M2 进展（2026-11-25）

**已落地**
- 设计令牌：水墨六色 + E-Ink/Paper 建议（纸白高对比、无重渐变）合并；
  `InkTokens` 经 CompositionLocal 暴露，含 `maxReadingWidth=600dp`、
  `verticalMinWidth=560dp`。
- 「夜读」深色主题：降低亮度与对比实现，不采用纯黑反白。
- 宣纸底 + 多层远山 Canvas（窄窗口自动减层）+ 静态纸纹噪点。
- 顶层导航 `NavigationSuiteScaffold`：Compact 底部栏 / Medium+ 侧边 Rail。
- 检索↔详情：≥600dp 双栏（列表 320–380dp），<600dp 单栏整页切换。
- 竖排诗文：逐字纵向堆叠（非旋转），右起列序；仅详情 pane ≥560dp 时提供切换。
- 状态保持：目的地与竖排开关用 `rememberSaveable`。
- Preview：360/820/940 三档 + 检索/详情/竖排。

**已验证（模拟器）**
- 360dp / 838dp / 944dp 三档均无 FATAL。
- UI dump 确认双栏同屏出现列表与详情；点击「阅读」能打开诗文与注释占位。

**未完成**
- ~~字体内置~~ 已完成：ssets/fonts/LXGWWenKai-Regular.ttf（24.7 MB）加载成功（实测日志 霞鹜文楷已加载：25928940 bytes）。
- ~~Tabletop/Book~~ 已实现（FoldPosture.kt + FoldedDetailPane.kt）：水平半折上诗下控、垂直半折左注右诗，铰链处留 12dp 缓冲；**真机悬停待验**。
- 截图测试（Roborazzi/Paparazzi）未接入。
- 打卡/统计/设置仍是占位页（M3/M4 实现）。

**取舍**：列表/详情用宽度驱动 Row，未用 `NavigableListDetailPaneScaffold`
（见 DECISIONS 第 23 条）。
## M3 进展（2026-11-25）

**已落地**
- `ReminderScheduler`：API 26–30 直接精确闹钟；31–32 `canScheduleExactAlarms` 检查；
  33+ `USE_EXACT_ALARM`；失败一律降级 WorkManager flex。
- 链式注册次日；`ReminderBootReceiver` 覆盖 BOOT_COMPLETED / TIMEZONE_CHANGED /
  MY_PACKAGE_REPLACED。
- 通知渠道 + 每日名句；点击直达主界面；13+ 在 Activity 申请 POST_NOTIFICATIONS。
- 打卡：`checkInToday()` 按天判定，断签清零；设置页四档字号旁新增提醒开关与时间步进。
- OriginOS 引导：仅 `Build.MANUFACTURER == vivo` 时展示三步白名单文案。

**实测**
- 切换「开启每日提醒」后日志：`精确闹钟已安排：1795636800000`。
- 模拟器非 vivo，OriginOS 引导正确不显示。
- 通知权限在自动化下被拒（无法点弹窗），降级为日志提示。

**未验证**
- 重启后闹钟是否仍生效（需 `adb reboot` 实测）。
- WorkManager 降级链的真实触发（需撤销精确闹钟授权）。
- 通知实际到达（需授权 POST_NOTIFICATIONS）。
## M3 验收结果（2026-11-25 实测）

| 项 | 结果 |
|---|---|
| 精确闹钟注册 | ✅ `dumpsys alarm` 见 `RTC_WAKEUP … action.REMIND` |
| 通知投递 | ✅ `id=4201`，点击直达主界面 |
| **静默通知** | ✅ 渠道 `daily_reminder_v2` importance=LOW、`sound=null`、`vibration=false`、`isNoisy=false`（用户要求「只要消息不要响铃」） |
| 重启后重排 | ✅ reboot 后 `精确闹钟已安排`，dumpsys 确认闹钟恢复 |
| WorkManager 降级 | ⚠️ 模拟器不可触发：`USE_EXACT_ALARM` 为 normal 权限、`canScheduleExactAlarms()` 恒 true；降级链保留为 OEM 异常兜底 |

**实现备注**
- 通知渠道换 ID 为 `daily_reminder_v2`：Android 渠道的声音/重要级一旦创建即由系统记住，改代码不会生效。
- `MotangApplication.onCreate` 增加提醒校正：覆盖 BOOT 广播未送达、闹钟被系统清理、应用被强停后重开。

## M4 进展（2026-11-25）

**已落地**
- Room：`study_session` 表（poemId/startedAt/endedAt/activeMs/mastered/day），
  聚合查询 `dailyStats` / `totalMsOn` / `poemCountOn` / `activeDays`。
- `SessionTracker`：activeMs 口径（不含暂停与后台），`snapshotMs` 供 UI 每秒投影，
  `finish()` 产出落库数据。
- 详情页「开始背诵 / 暂停背诵」+ 实时时长；关闭详情即结算并自动打卡。
- 统计页：近 7 天 Canvas 柱状图 + 本周时长/打卡天数/新背篇数；时长格式化与星期标签。
- 时长口径文案固定显示「active_ms（不含暂停与后台）」。

**实测**
- `motang.db` 已创建；统计页渲染正常（背书统计 / 本周时长 / 打卡天数 / 新背篇数 / 近 7 天）。

**未完成**
- 会话计时在真机开合/分屏下的连续性断言（需 Instrumented test）。
- 「已背会」标记 UI 与列表。
- 月视图热力图与作者分布（topPoems 查询已备，UI 未做）。
- 杀进程后未结束会话的恢复策略（当前关闭详情即结算）。
## M4 收尾（2026-11-25）

**新增**
- 会话表加 `title` / `author`（DB v2，开发期用 destructive migration），统计页无需回查语料。
- 「标记已背会」按钮 + `markMastered` / `masteredCount`。
- 月热力图（7 列网格，按当日 activeMs 归一着色）。
- 本月常背作者 Top 10（按 author 分组）。
- 详情页新增「结束并记录」：**双栏模式下没有返回按钮，旧实现无法结算会话**（实测发现的缺口）。
- 未结算会话持久化：开始/暂停写入 DataStore，冷启动安全结算，`clearOngoing` 在结算后清理。

**实测**
- 点击链：阅读 → 开始背诵 → 结束并记录 → 统计；`本周时长` 由 0 变为「不足 1 分」，
  证明会话已落库、聚合生效。
- 月热力图、累计已背会文案渲染正常。

**待修**
- 「标记已背会」后 `累计已背会` 仍显示 0：UPDATE 影响 0 行，疑似 `poemId` 与
  统计刷新时机不匹配，需下一步定位（优先）。
- 开合/分屏连续性 Instrumented test 未写。
## M4 完成（2026-11-25）

**背单词式每日打卡（实测全通）**
- 随机抽诗（Rust 蓄水池抽样，111MB 单遍约 1s，不引入 rand 依赖）
- 背诵阶段只显示诗文 + 出处「作者 · 唐」
- 「我背完了」→ 逐句解析（note 字段为 M5 预留，当前占位）
- 「学习完毕 · 已背会」→ 今日打卡完成；可「继续学习」再抽一首
- 打卡口径：走完解析才算打卡，中途退出不计

**数据与统计**
- `study_session` 存 title/author（DB v2），统计页不回查语料
- 周柱状图、月热力图、作者 Top10、累计已背会
- 「继续学习」多轮累计实测：今日首数由 1 递增到 5

**已背会列表**：独立页签，显示「共 N 篇」+ 诗名/作者/日期

**PDF 导出**：系统 `PdfDocument` + SAF，内容含日期、连续天数、累计已背会、近 7 天明细、已背会清单（≤200 条）

**单测（8 个，全绿）**
- `SessionTrackerTest`（5）：activeMs 不含暂停、重复暂停幂等、finish 携带元数据并清空、无会话返回 null、restoreStopped 保留累计
- `StatsFormatTest`（3）：时长格式化、星期标签、非法日期回退

**未做（唯一剩余项）**
- 开合/分屏连续性的 **Instrumented test**：需 androidTest 依赖 + 折叠态 AVD，
  当前仅有人工验证（三档宽度无崩溃）与纯逻辑单测。
## M5 进展（2026-11-25）

**已完成：模型可加载性验证（host CPU）**

| 项 | 结果 |
|---|---|
| llama.cpp b12726 编译 | ✅ MSVC x64 + AVX2 |
| 可执行名 | `llama-cli` → **`llama-completion.exe`** |
| MiniCPM5-2B 加载 | ✅ 3.33 s |
| 文本生成 | ✅ **24.0 tok/s**（prompt 165 tok/s） |
| ASR 音频编码 | ✅ **89 ms** |
| ASR 转写 | ✅ `床前明月光，疑是地上霜。`（逐字正确） |

**已完成：`:llm` 模块（第 1 步）**
- `llm/build.gradle.kts`：`com.android.library` + `externalNativeBuild` CMake
- `llm/src/main/CMakeLists.txt`：`add_subdirectory(llama.cpp)` 静态链接
- `llm/src/main/cpp/llm_bridge.cpp`：JNI 探针（`probe` / `echoModelPath`）
- `llm/src/main/kotlin/.../LlmNative.kt`
- 产物：`libmotang_llm.so` arm64-v8a **6.5 MB** / x86_64 **6.9 MB**（stripped）
- **16KB 对齐**：所有 LOAD 段 `align=0x4000` ✅
- `git submodule` 固定 b12726；`model/`、`llm/build-host/` 已 gitignore

**关键实测结论**
- MiniCPM5 默认输出 `<think>` → **必须关闭 thinking** 才能保吞吐
- `llama-mtmd-cli` **不支持 `--no-display-prompt`**
- 测试音频可用 Windows SAPI（Microsoft Huihui）合成 16kHz 单声道

**下一步**
1. `:llm` Service（`android:process=":llm"`）+ 模型加载/流式生成
2. 逐句解析接详情页 + Room 缓存
3. 语音通道（mtmd audio + mmproj）
4. 生命周期：崩溃重启、热约束
### M5 第 2 步（推理封装，2026-11-25）

- `llm_bridge.cpp` 实现：`loadModel` / `releaseModel` / `generate`。
- **关闭 thinking 的方式**：输出侧过滤 `</think>` 之后的内容。
  理由：MiniCPM5 与 Qwen3 的 chat template 开关表达不同，依赖模板私有参数不可移植；
  输出侧过滤对所有模型一致，且不会把思考段泄漏到 UI。
- 采样：`temperature <= 0.01` 走 greedy（确定性强），否则 top_k(40) + top_p(0.9) + temp。
- 踩坑（b12726 API 漂移）：
  - `llama_model_params.use_mmap` 已移除 → 改用 `load_mode = LLAMA_LOAD_MODE_MMAP`
  - `llama_kv_self_clear` 已移除 → 改用 `llama_memory_clear(llama_get_memory(ctx), true)`
- 产物：`:llm:assembleDebug` 通过，libmotang_llm.so 6.5/6.9 MB，16KB 对齐。
### M5 第 3 步（端到端跑通，2026-11-25）

**已实现**
- `LlmService`：`android:process=":llm"` 绑定式 Service，Messenger 跨进程协议。
- `LlmClient`：挂起式 `loadModel` / `generate`，单飞请求（推理串行）。
- `LlmProtocol`：消息常量。
- 详情页「AI 解读」按钮 + 结果区 + 固定「AI 生成，仅供参考」标注。
- 模型路径：`getExternalFilesDir("models")/MiniCPM5-2B-Q4_K_M.gguf`。

**模拟器实测**
| 项 | 结果 |
|---|---|
| 模型加载（:llm 进程） | ✅ 冷启动 ~10 s |
| 生成 1 | 255 字 / 23.3 s（≈11 tok/s） |
| 生成 2 | 244 字 / 27.8 s（≈9 tok/s） |
| 思考段过滤 | ✅ 返回内容不含 `<think>` |
| 进程隔离 | ✅ 日志 PID 6994 为 `:llm` 进程 |

**未完成**
- 流式输出（当前整段返回，UI 一次性显示）
- Room 解释缓存表（`cache_key = sha256(poem_id+prompt_ver+model_id)` 未建）
- 语音输入 UI（mtmd 已验证可用，未接前端）
- 热约束、`:llm` 崩溃自动重试
- 模型重复加载（每次点击都会 load，应缓存句柄）
### M5 第 4 步（流式 + 句柄复用，2026-11-25）

**已实现**
- **流式输出**：`generateStream(prompt, maxTokens, temp, sink)`，JNI 逐 token 回调
  `TokenSink.onToken`，经 Messenger `MSG_TOKEN` 送到主线程，UI 追加显示。
- **思考段流式过滤**：`ThinkFilter` 状态机——遇到 `</think>` 前不发送任何内容，
  之后立即转发；模型未思考时结束时一次性 flush。避免「首字延迟 = 总时长」。
- **模型句柄复用**：`loadModel` 发现路径相同直接返回，省去约 10 s 重载。

**实测**
- 生成完成日志：`generated 230 chars in 30692 ms`（模拟器 x86_64，约 7.5 tok/s）。
- `:llm` 进程内模型加载 + 生成链路正常。

**未完成**
- Room 解释缓存（`sha256(poem_id+prompt_ver+model_id)`）
- 语音输入 UI（mtmd 已可用）
- 热约束 / `:llm` 崩溃重试

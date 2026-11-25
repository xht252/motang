# 墨唐 motang · 里程碑状态

> 每个里程碑完成后更新本文件；下一个里程碑开始时先读本文件与 DECISIONS.md。

## 当前状态

| 里程碑 | 状态 | 备注 |
|---|---|---|
| M0 脚手架 | **已完成（11/11 PASS）** | 模拟器实测 |
| M1 数据 + 检索 | **已完成（C1–C4 PASS）** | 57444 首；设备端四类查询通过 |
| M2 自适应 UI | 未开始 | |
| M3 提醒 | 未开始 | |
| M4 统计 | 未开始 | |
| M5 端侧 LLM | 未开始 | |
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

## M1 实测结果（2026-11-25）

| 项 | 结果 |
|---|---|
| 语料入库 | 57444 首，去重丢弃 529 首 |
| 作者数 | 3667 |
| ETL 产物 | `poems.jsonl.zst` 15.05 MB + `poems.authors.json` 0.04 MB |
| 桌面建索引 | 3.1–4.4 s（57k 首） |
| 桌面检索 P95 | 最差 2.29 ms（目标 ≤ 50 ms） |
| 设备建索引 | **65.6–75.5 s**（x86_64 模拟器；目标 ≤ 30 s **需真机复核**） |
| 设备查询 | 明月=942，jys=254，libai=1263，静夜思=1 |
| release APK | 32.43 MB（arm64-only；.so 9.37 MB + assets 15.05 MB） |
| Rust 单测 | 23 个全绿 |

**体积预警**：40 MB 预算下，M2 的霞鹜文楷**必须子集化**（剩余空间约 7.5 MB）。

## 接口契约（已定）

```rust
// M0
fn ping() -> Result<String, MotangError>
fn core_version() -> Result<String, MotangError>

// M1（已实现）
fn open_index(index_dir: String) -> Result<(), MotangError>
fn build_index(corpus_path: String, index_dir: String) -> Result<BuildStats, MotangError>
fn search(query: String, page: u32, page_size: u32) -> Result<Page, MotangError>
fn get_poem(id: String) -> Result<Option<PoemView>, MotangError>
fn list_authors(meta_dir: String, prefix: String, limit: u32) -> Result<Vec<String>, MotangError>
```

## 数据 Schema v1（M1，JSONL）

```json
{
  "id": "string",
  "title": "string（繁体，上游原貌）",
  "title_simplified": "string",
  "author": "string",
  "author_simplified": "string",
  "dynasty": "唐",
  "paragraphs": ["string"],
  "paragraphs_simplified": ["string"],
  "pinyin_full": ["string（逐段，音节空格分隔）"],
  "title_pinyin_full": "string",
  "author_pinyin_full": "string",
  "tags": [],
  "notes": null,
  "translation": null,
  "appreciation": null,
  "source": "chinese-poetry"
}
```

**不落盘的派生字段**（建索引时计算，省 assets 体积）：
`pinyin_initials`、`pinyin_compact` 均由 `*_pinyin_full` 派生。

`notes` / `translation` / `appreciation` 在 M1 恒为 `null`（上游数据不提供），
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

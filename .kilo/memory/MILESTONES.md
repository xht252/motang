# 墨唐 motang · 里程碑状态

> 每个里程碑完成后更新本文件；下一个里程碑开始时先读本文件与 DECISIONS.md。

## 当前状态

| 里程碑 | 状态 | 备注 |
|---|---|---|
| M0 脚手架 | 代码已就绪，待本机 toolchain 安装后验收 | 见下表验证结果 |
| M1 数据 + 检索 | 未开始 | |
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
- 脚本：`.kilo/scripts/{bootstrap,verify-m0,check-16k-pagesize}.ps1`。

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

- [ ] 实测入库诗数（M1，方案写 5.7 万，上游 README 称 5.5 万，以实测为准）
- [ ] release keystore SHA-256 指纹
- [ ] `.so` LOAD 段 align 实测值（arm64-v8a / x86_64）
- [ ] `ping()` 实际输出（含运行 ABI）
- [ ] AVD 档案名与可用折叠态 id（`adb shell cmd device_state print-states`）
- [ ] release APK 体积

## 接口契约（已定）

```rust
// M0
fn ping() -> Result<String, MotangError>
fn core_version() -> Result<String, MotangError>
fn set_runtime_abi(abi: String) -> Result<(), MotangError>

// M1 计划（届时细化为最终版）
fn search(query: String, author: Option<String>, page: u32, page_size: u32)
    -> Result<SearchResult, MotangError>
fn get_poem(id: String) -> Result<Option<Poem>, MotangError>
fn list_authors(prefix: String) -> Result<Vec<String>, MotangError>
fn build_index(progress: impl Fn(u32)) -> Result<(), MotangError>
```

## 数据 Schema v1（M1，JSONL）

```json
{
  "id": "string",
  "title": "string",
  "author": "string",
  "dynasty": "唐",
  "paragraphs": ["string"],
  "paragraphs_simplified": ["string"],
  "pinyin_full": ["string"],
  "pinyin_initials": ["string"],
  "tags": ["string"],
  "notes": null,
  "translation": null,
  "appreciation": null,
  "source": "chinese-poetry"
}
```

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

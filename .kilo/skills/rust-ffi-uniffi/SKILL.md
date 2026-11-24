---
name: rust-ffi-uniffi
description: 当任务涉及 Rust 交叉编译、uniffi 绑定、cargo-ndk、jniLibs、Gradle↔Cargo 接线或 16KB 对齐时使用。
---

# 现状（M0 已落地，直接沿用，不要重建）

- workspace：`core-rs/Cargo.toml`，成员 `motang-etl` / `motang-search` / `motang-ffi`。
- `motang-ffi`：`crate-type = ["cdylib", "lib"]`，`lib.name = "motang_ffi"`
  （产物 `libmotang_ffi.so`）；`uniffi::setup_scaffolding!()` 在 crate 根。
- 绑定生成器与库同 crate：`[[bin]] uniffi-bindgen`，`required-features = ["bindgen-cli"]`。
  调用方式：`cargo run -p motang-ffi --features bindgen-cli --bin uniffi-bindgen -- generate <so> --language kotlin --out-dir <dir> --no-format`
  （`--library` 在 0.32 是已废弃的布尔开关，**不要**再传路径）。
- `uniffi.toml`：`package_name = "io.github.xht252.motang.core"`、`cdylib_name = "motang_ffi"`、
  `android = true`（minSdk 26 必须开，否则 Cleaner 走 JVM 特化实现）、
  `kotlin_target_version = "2.2"`。
- Gradle 任务：`cargoBuildDebug` / `cargoBuildRelease` / `generateUniffiBindings`；
  `preDebugBuild` / `preReleaseBuild` 已接线。
- 绑定从 **debug 未 strip** 的 x86_64 产物生成（release `strip=true` 会剥掉元数据）。
- 运行期依赖 JNA（uniffi 0.32 Kotlin 后端用 direct mapping），已在 `app` 声明。

# 新增导出函数时的流程

1. 在 `motang-ffi/src/lib.rs` 写 `#[uniffi::export] pub fn f(...) -> Result<T, MotangError>`。
   **禁止 unwrap/expect**；错误用 `MotangError` 枚举（按需加变体）。
2. 复杂类型用 `#[derive(uniffi::Record)]` / `uniffi::Enum`；保持粗粒度
   （返回整页结果，不逐条跨边界）。
3. `cargo test -p motang-ffi`；`cargo clippy --workspace -- -D warnings`。
4. 重新构建即自动生成绑定；Kotlin 侧引用 `io.github.xht252.motang.core.*`。
5. 若新增对象类型（带方法的 struct），确认生成的 `UniffiLib.CLEANER` 存在
   （只有存在对象类型时才会生成）。

# 交叉编译

```powershell
cargo ndk -t arm64-v8a -t x86_64 -o <outdir> build            # debug
cargo ndk -t arm64-v8a            -o <outdir> build --release # release
```

需要 `ANDROID_NDK_HOME`（或 `motang.ndkHome` Gradle 属性）。
链接参数已在 `core-rs/.cargo/config.toml` 为两个 target 配好
`-Wl,-z,max-page-size=16384`。

# 验证

```powershell
powershell -File .kilo/scripts/check-16k-pagesize.ps1 -SoPath <so>     # 或 -ApkPath <apk>
```
断言 LOAD 段 `align >= 0x4000`。

# 禁止

- 手写 JNI；在主线程调用 ffi；把大字符串按字符跨边界传递。
- `cargo install uniffi-bindgen`（版本漂移）。
- 给 `motang-ffi` 单独设 `panic = "abort"`（Cargo 不支持按包覆盖 panic，
  见 DECISIONS 第 3 条）。

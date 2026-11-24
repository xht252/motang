//! 墨唐 Kotlin↔Rust 绑定层。
//!
//! M0 只导出三个无副作用的探针函数，用于验证
//! `cargo-ndk → jniLibs → uniffi Kotlin 绑定 → Gradle` 全链路。
//!
//! 纪律（见 .kilo/rules/project.md）：
//! - 导出函数一律返回 `Result<_, MotangError>` 或纯值，**禁止 unwrap/expect**。
//! - 接口保持粗粒度：一次调用返回一页数据，不逐条跨语言传对象。
//! - 不在此层做磁盘/网络 IO；M1 起由调用方切到 Dispatchers.IO。

uniffi::setup_scaffolding!();

use std::sync::OnceLock;

/// 绑定层错误。M0 仅保留一个泛化分支，M1 起按检索/索引细分为枚举变体。
///
/// 注意：uniffi 会把 `MotangError` 生成为 Kotlin 的 `MotangException`
/// （去掉 Error 后缀 + 追加 Exception），且变体成为 `kotlin.Exception` 的子类。
/// 因此**字段名不能叫 `message`**——会与 `Throwable.message: String?` 冲突，故用 `reason`。
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MotangError {
    #[error("内部错误：{reason}")]
    Internal { reason: String },
}

/// 运行期探测到的进程 ABI 名称（Android 上由 JNI 层注入，桌面测试返回 unknown）。
static ABI: OnceLock<String> = OnceLock::new();

/// 由 Kotlin 侧在加载库后调用，记录当前进程 ABI，便于日志与验收核对。
///
/// 之所以不在 Rust 侧编译期写死 `target_arch`：x86_64 模拟器与 arm64 真机的
/// 验收输出需要可区分，且要让 UI 显示真实运行 ABI 而非构建目标。
#[uniffi::export]
pub fn set_runtime_abi(abi: String) -> Result<(), MotangError> {
    if abi.is_empty() {
        return Err(MotangError::Internal {
            reason: "abi 不能为空".to_string(),
        });
    }
    // 重复设置时保留首个值，避免热重载场景下的竞态。
    let _ = ABI.set(abi);
    Ok(())
}

/// M0 验收探针：返回绑定层、检索核心版本与运行 ABI。
#[uniffi::export]
pub fn ping() -> Result<String, MotangError> {
    Ok(format!(
        "motang-ffi {} / motang-search {} / abi {}",
        env!("CARGO_PKG_VERSION"),
        motang_search::version(),
        current_abi()
    ))
}

/// 绑定层语义版本，供 UI 与诊断页展示。
#[uniffi::export]
pub fn core_version() -> Result<String, MotangError> {
    Ok(env!("CARGO_PKG_VERSION").to_string())
}

fn current_abi() -> &'static str {
    ABI.get().map(String::as_str).unwrap_or("unknown")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ping_contains_versions() {
        let value = ping().expect("ping 不应失败");
        assert!(value.contains("motang-ffi"), "got: {value}");
        assert!(value.contains("motang-search"), "got: {value}");
    }

    #[test]
    fn empty_abi_is_rejected() {
        let err = set_runtime_abi(String::new()).expect_err("空 abi 应报错");
        assert!(matches!(err, MotangError::Internal { .. }));
    }}

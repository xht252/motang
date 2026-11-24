//! 墨唐检索核心。
//!
//! M0 只提供 crate 存活证明与版本号；M1 接入 tantivy / jieba-rs / pinyin。

/// 检索核心的语义版本。
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

#[cfg(test)]
mod tests {
    #[test]
    fn version_is_not_empty() {
        assert!(!super::version().is_empty());
    }
}

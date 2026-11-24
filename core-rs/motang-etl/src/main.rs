//! 墨唐离线语料 ETL。
//!
//! M0 只有命令行入口与版本探针；M1 接入 chinese-poetry 清洗、繁简转换、
//! 拼音生成、去重与压缩产物输出。

fn main() {
    println!("motang-etl {}", env!("CARGO_PKG_VERSION"));
}

#[cfg(test)]
mod tests {
    #[test]
    fn crate_builds() {
        assert!(!env!("CARGO_PKG_VERSION").is_empty());
    }
}

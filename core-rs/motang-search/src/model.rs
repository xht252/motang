//! 语料数据模型。
//!
//! 数据源：`data/Poetry` 下全部 CSV（MIT，Copyright 2018 Werner），
//! 字段为 `题目 / 朝代 / 作者 / 内容`，**本身即简体**。
//!
//! 因此不再需要「繁简双字段」：
//!
//! - 原文即简体，直接作为展示文本与检索文本
//! - 去掉 `*_simplified` 冗余字段后，assets 体积近乎减半
//!   （见 .kilo/memory/DECISIONS.md 第 20 条）
//!
//! 派生字段不落盘（建索引时计算）：
//! - `pinyin_initials` 由 `pinyin_full` 各音节首字母派生
//! - `pinyin_compact`  由 `pinyin_full` 去空格派生

use serde::{Deserialize, Serialize};

/// 一首诗的完整记录。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Poem {
    /// 稳定 ID。由「作者+标题+正文」哈希派生，保证同一首诗跨构建不变。
    pub id: String,
    pub title: String,
    pub author: String,
    /// 朝代。唐诗语料全部为「唐」。
    pub dynasty: String,
    /// 正文，按句号切分为段（一段通常对应律诗的一联）。
    pub paragraphs: Vec<String>,
    /// 逐段拼音全拼，音节以空格分隔，例："chuang qian ming yue guang"。
    /// 首字母与紧凑形式均由此派生，不再单独落盘。
    pub pinyin_full: Vec<String>,
    /// 标题拼音全拼，例："jing ye si"。
    #[serde(default)]
    pub title_pinyin_full: String,
    /// 作者拼音全拼，例："li bai"。
    #[serde(default)]
    pub author_pinyin_full: String,
    /// 预留标签位。当前数据源不提供。
    #[serde(default)]
    pub tags: Vec<String>,
    /// 预置注释。数据源不含，恒为 None。
    #[serde(default)]
    pub notes: Option<String>,
    /// 预置译文。数据源不含，恒为 None。
    #[serde(default)]
    pub translation: Option<String>,
    /// 预置赏析。数据源不含，恒为 None。
    #[serde(default)]
    pub appreciation: Option<String>,
    /// 数据出处标识，用于合规声明。
    pub source: String,
}

impl Poem {
    /// 整首诗的拼音全拼（空格连接），用于带空格前缀检索。
    pub fn pinyin_full_joined(&self) -> String {
        self.pinyin_full.join(" ")
    }

    /// 逐段拼音首字母，例："cqmyg"。
    pub fn paragraph_initials(&self) -> Vec<String> {
        self.pinyin_full.iter().map(|p| initials_of(p)).collect()
    }

    /// 标题拼音首字母，例："jys"。
    pub fn title_initials(&self) -> String {
        initials_of(&self.title_pinyin_full)
    }

    /// 作者拼音首字母，例："lb"。
    pub fn author_initials(&self) -> String {
        initials_of(&self.author_pinyin_full)
    }

    /// 全诗（含标题）拼音去空格紧凑形式。
    pub fn compact_pinyin(&self) -> String {
        compact_of(&self.title_pinyin_full, &self.pinyin_full)
    }
}

/// 单条检索命中。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SearchHit {
    pub poem_id: String,
    pub title: String,
    pub author: String,
    /// 高亮片段（M1 为纯文本片段，M2 交给 Compose 渲染）。
    pub snippet: String,
    pub score: f32,
}

/// 一页检索结果。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SearchResult {
    pub hits: Vec<SearchHit>,
    /// 命中总数（由 Count collector 统计，非页大小）。
    pub total: u64,
}

/// 从带空格拼音串取各音节首字母。
fn initials_of(pinyin: &str) -> String {
    pinyin
        .split_whitespace()
        .filter_map(|s| s.chars().next())
        .collect()
}

/// 标题 + 各段拼音去空格拼接。
fn compact_of(title_pinyin: &str, paragraphs_pinyin: &[String]) -> String {
    let mut out = String::new();
    for s in title_pinyin.split_whitespace() {
        out.push_str(s);
    }
    for p in paragraphs_pinyin {
        for s in p.split_whitespace() {
            out.push_str(s);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Poem {
        Poem {
            id: "1".into(),
            title: "静夜思".into(),
            author: "李白".into(),
            dynasty: "唐".into(),
            paragraphs: vec!["床前明月光，疑是地上霜。".into()],
            pinyin_full: vec!["chuang qian ming yue guang yi shi di shang shuang".into()],
            title_pinyin_full: "jing ye si".into(),
            author_pinyin_full: "li bai".into(),
            tags: vec![],
            notes: None,
            translation: None,
            appreciation: None,
            source: "test".into(),
        }
    }

    #[test]
    fn initials_are_derived_from_pinyin() {
        let p = sample();
        assert_eq!(p.title_initials(), "jys");
        assert_eq!(p.author_initials(), "lb");
        assert_eq!(
            p.paragraph_initials(),
            vec!["cqmygysdss".to_string()],
            "首字母应覆盖整段所有音节"
        );
    }

    #[test]
    fn compact_pinyin_joins_title_and_body() {
        let p = sample();
        assert_eq!(
            p.compact_pinyin(),
            "jingyesichuangqianmingyueguangyishidishangshuang"
        );
    }

    #[test]
    fn empty_pinyin_yields_empty_initials() {
        assert_eq!(initials_of(""), "");
        assert_eq!(compact_of("", &[]), "");
    }
}

//! 语料数据模型。
//!
//! 这份类型同时被 `motang-etl`（写）与 `motang-search`（读）使用，
//! 放在 search crate 里避免两边定义漂移。
//!
//! 体积约束：assets 里**只存不可推导的字段**。
//!
//! - `pinyin_initials` 由 `pinyin_full` 各音节首字母派生
//! - `pinyin_compact`  由 `pinyin_full` 去空格派生
//!
//! 两者都在建索引时计算，不落盘——实测可显著压小 assets。
//!
//! 注意 `notes` / `translation` / `appreciation` 在 M1 恒为 `None`：
//! 上游 `chinese-poetry` 的唐诗三百首**不含**注释译文（见 DECISIONS 第 4 条）。

use serde::{Deserialize, Serialize};

/// 一首诗的完整记录。
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Poem {
    /// 稳定 ID。优先沿用上游 `id`；三百首无 id 时用内容哈希。
    pub id: String,
    pub title: String,
    /// 标题简体，用于简体输入命中繁体语料。
    #[serde(default)]
    pub title_simplified: String,
    pub author: String,
    /// 作者简体。
    #[serde(default)]
    pub author_simplified: String,
    /// 朝代。当前语料全部为「唐」。
    pub dynasty: String,
    /// 原文（繁体，保留上游原貌）。
    pub paragraphs: Vec<String>,
    /// 简体对照，逐段对应 `paragraphs`。
    pub paragraphs_simplified: Vec<String>,
    /// 逐段拼音全拼，音节以空格分隔，例："chuang qian kan yue guang"。
    /// 首字母与紧凑形式均由此派生，不再单独落盘。
    pub pinyin_full: Vec<String>,
    /// 标题拼音全拼，例："jing ye si"。
    #[serde(default)]
    pub title_pinyin_full: String,
    /// 作者拼音全拼，例："li bai"。
    #[serde(default)]
    pub author_pinyin_full: String,
    /// 上游 tags（三百首可能提供）。M1 全唐诗分片无此字段。
    #[serde(default)]
    pub tags: Vec<String>,
    /// 预置注释。M1 恒为 None。
    #[serde(default)]
    pub notes: Option<String>,
    /// 预置译文。M1 恒为 None。
    #[serde(default)]
    pub translation: Option<String>,
    /// 预置赏析。M1 恒为 None。
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

    /// 逐段拼音首字母，例："cqkyg"。
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
    /// 带高亮的片段（M1 为纯文本片段，M2 交给 Compose 渲染）。
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
            title: "靜夜思".into(),
            title_simplified: "静夜思".into(),
            author: "李白".into(),
            author_simplified: "李白".into(),
            dynasty: "唐".into(),
            paragraphs: vec!["床前看月光".into()],
            paragraphs_simplified: vec!["床前看月光".into()],
            pinyin_full: vec!["chuang qian kan yue guang".into()],
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
        assert_eq!(p.paragraph_initials(), vec!["cqkyg".to_string()]);
    }

    #[test]
    fn compact_pinyin_joins_title_and_body() {
        let p = sample();
        assert_eq!(p.compact_pinyin(), "jingyesichuangqiankanyueguang");
    }

    #[test]
    fn empty_pinyin_yields_empty_initials() {
        assert_eq!(initials_of(""), "");
        assert_eq!(compact_of("", &[]), "");
    }
}

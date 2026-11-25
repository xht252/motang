//! 墨唐检索核心。
//!
//! M1 提供：tantivy 索引（jieba 分词 + 拼音字段）、检索、按 ID 取诗、作者前缀列举。
//!
//! 版本组合由 `.kilo/memory/M1-DEPENDENCY-SPIKE.md` 实测锁定：
//! tantivy 0.26.2 + tantivy-jieba 0.21 + jieba-rs 0.11。
//!
//! 索引设计要点（均为实测踩出来的，改动前请先读 M1-DEPENDENCY-SPIKE.md）：
//! 1. 中文字段必须显式 `set_tokenizer("jieba")`，否则中文 0 命中。
//! 2. 同一字段同时索引繁体与简体，否则简体输入查不到繁体语料。
//! 3. 全拼要额外建「去空格紧凑」字段，否则 `libai` 查不到 `li bai`。
//! 4. `total` 必须用 Count collector 单独统计，TopDocs 的收集数不是命中总数。

pub mod model;

use std::path::Path;

use tantivy::collector::{Count, TopDocs};
use tantivy::query::{BooleanQuery, Query, QueryParser, RegexQuery, TermQuery};
use tantivy::schema::{
    Field, IndexRecordOption, Schema, TextFieldIndexing, TextOptions, Value, STORED, STRING,
};
use tantivy::{Index, IndexReader, IndexWriter, TantivyDocument, Term};

pub use model::{Poem, SearchHit, SearchResult};

/// 索引布局版本。schema 变更时必须自增，否则旧索引会被复用导致字段错位。
pub const INDEX_SCHEMA_VERSION: u32 = 2;

/// 检索核心的语义版本。
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

/// 索引字段句柄。
#[derive(Debug, Clone, Copy)]
pub struct Fields {
    pub id: Field,
    /// 标题：繁体 + 简体都写入同一字段（多值）。
    pub title: Field,
    /// 作者：繁体 + 简体都写入同一字段。
    pub author: Field,
    /// 正文：繁体 + 简体都写入同一字段。
    pub paragraphs: Field,
    /// 拼音全拼（空格分隔）——用于「jing ye si」这类带空格输入。
    pub pinyin_full: Field,
    /// 拼音去空格紧凑形式——用于「jingyesi」这类连续输入。
    pub pinyin_compact: Field,
    /// 拼音首字母——用于「jys」。
    pub pinyin_initials: Field,
}

/// 构建 schema。
pub fn schema() -> (Schema, Fields) {
    // 中文字段走 jieba；必须显式指定，否则默认空白分词导致中文 0 命中。
    let jieba = TextFieldIndexing::default()
        .set_tokenizer("jieba")
        .set_index_option(IndexRecordOption::WithFreqsAndPositions);
    let jieba_opts = TextOptions::default()
        .set_indexing_options(jieba)
        .set_stored();

    // 拼音是 ASCII：紧凑串与首字母必须用 raw，才能保留整体 token 做前缀匹配。
    let raw = TextFieldIndexing::default()
        .set_tokenizer("raw")
        .set_index_option(IndexRecordOption::WithFreqsAndPositions);
    let raw_opts = TextOptions::default()
        .set_indexing_options(raw)
        .set_stored();

    // 带空格全拼用 default（按空白切分），便于逐音节匹配。
    let default_opts = TextOptions::default()
        .set_indexing_options(
            TextFieldIndexing::default()
                .set_tokenizer("default")
                .set_index_option(IndexRecordOption::WithFreqsAndPositions),
        )
        .set_stored();

    let mut builder = Schema::builder();
    let id = builder.add_text_field("id", STRING | STORED);
    let title = builder.add_text_field("title", jieba_opts.clone());
    let author = builder.add_text_field("author", jieba_opts.clone());
    let paragraphs = builder.add_text_field("paragraphs", jieba_opts);
    let pinyin_full = builder.add_text_field("pinyin_full", default_opts);
    let pinyin_compact = builder.add_text_field("pinyin_compact", raw_opts.clone());
    let pinyin_initials = builder.add_text_field("pinyin_initials", raw_opts);

    let schema = builder.build();
    (
        schema,
        Fields {
            id,
            title,
            author,
            paragraphs,
            pinyin_full,
            pinyin_compact,
            pinyin_initials,
        },
    )
}

/// 检索索引封装。
pub struct SearchEngine {
    index: Index,
    reader: IndexReader,
    fields: Fields,
}

impl SearchEngine {
    /// 打开或新建索引目录。
    ///
    /// `create_dir_all` 不能省：`Index::create_in_dir` 要求目录已存在
    /// （spike 实测 `OpenDirectoryError(DoesNotExist)`）。
    pub fn open_or_create(dir: &Path) -> tantivy::Result<Self> {
        std::fs::create_dir_all(dir)?;
        let (schema, fields) = schema();

        let index = if Index::exists(&tantivy::directory::MmapDirectory::open(dir)?)? {
            Index::open_in_dir(dir)?
        } else {
            Index::create_in_dir(dir, schema)?
        };

        index
            .tokenizers()
            .register("jieba", tantivy_jieba::JiebaTokenizer::new());

        let reader = index.reader()?;
        Ok(Self {
            index,
            reader,
            fields,
        })
    }

    pub fn fields(&self) -> Fields {
        self.fields
    }

    /// 批量重建索引。返回写入条数。
    ///
    /// M1 语料是静态的，重建比增量合并更简单可靠。
    pub fn rebuild(&self, poems: &[Poem]) -> tantivy::Result<usize> {
        let mut writer: IndexWriter = self.index.writer(64_000_000)?;
        writer.delete_all_documents()?;

        let f = self.fields;
        for poem in poems {
            let mut document = TantivyDocument::default();
            document.add_text(f.id, &poem.id);

            // 繁简都进同一字段：简体输入也能命中繁体语料。
            document.add_text(f.title, &poem.title);
            if !poem.title_simplified.is_empty() {
                document.add_text(f.title, &poem.title_simplified);
            }
            document.add_text(f.author, &poem.author);
            if !poem.author_simplified.is_empty() {
                document.add_text(f.author, &poem.author_simplified);
            }
            for p in &poem.paragraphs {
                document.add_text(f.paragraphs, p);
            }
            for p in &poem.paragraphs_simplified {
                document.add_text(f.paragraphs, p);
            }

            document.add_text(f.pinyin_full, poem.pinyin_full_joined());
            // pinyin_compact 是**多值**字段：整诗、作者、以及每一段各一份。
            // 前缀匹配只能命中值的开头，所以必须按段拆分，
            // 否则「chuangqianmingyueguang」（正文中段）永远查不到。
            document.add_text(f.pinyin_compact, poem.compact_pinyin());
            let author_compact: String = poem
                .author_pinyin_full
                .chars()
                .filter(|c| !c.is_whitespace())
                .collect();
            if !author_compact.is_empty() {
                document.add_text(f.pinyin_compact, &author_compact);
            }
            for paragraph_pinyin in &poem.pinyin_full {
                let compact: String = paragraph_pinyin
                    .chars()
                    .filter(|c| !c.is_whitespace())
                    .collect();
                if !compact.is_empty() {
                    document.add_text(f.pinyin_compact, &compact);
                }
            }

            // 标题拼音也进首字母字段，否则「jys」查不到《静夜思》。
            let title_initials = poem.title_initials();
            if !title_initials.is_empty() {
                document.add_text(f.pinyin_initials, &title_initials);
            }
            // 作者拼音：让「libai」「lb」能命中该作者的诗。
            if !poem.author_pinyin_full.is_empty() {
                document.add_text(f.pinyin_full, &poem.author_pinyin_full);
            }
            let author_initials = poem.author_initials();
            if !author_initials.is_empty() {
                document.add_text(f.pinyin_initials, &author_initials);
            }
            for initials in poem.paragraph_initials() {
                document.add_text(f.pinyin_initials, &initials);
            }

            writer.add_document(document)?;
        }

        writer.commit()?;
        writer.wait_merging_threads()?;
        self.reader.reload()?;
        Ok(poems.len())
    }

    /// 检索一页结果。
    pub fn search(&self, query: &str, page: u32, page_size: u32) -> tantivy::Result<SearchResult> {
        let trimmed = query.trim();
        if trimmed.is_empty() {
            return Ok(SearchResult {
                hits: Vec::new(),
                total: 0,
            });
        }

        let page_size = page_size.clamp(1, 100) as usize;
        let offset = (page.max(1) - 1) as usize * page_size;

        let (parsed, is_pinyin) = self.build_query(trimmed)?;
        let searcher = self.reader.searcher();

        // 真·总数：TopDocs 只收集 limit 条，其长度不能当 total。
        let total = searcher.search(parsed.as_ref(), &Count)? as u64;

        let top = searcher.search(
            parsed.as_ref(),
            &TopDocs::with_limit(offset + page_size).order_by_score(),
        )?;

        let mut hits = Vec::new();
        for (score, addr) in top.iter().skip(offset) {
            let stored: TantivyDocument = searcher.doc(*addr)?;
            hits.push(SearchHit {
                poem_id: text_of(&stored, self.fields.id),
                title: text_of(&stored, self.fields.title),
                author: text_of(&stored, self.fields.author),
                snippet: self.snippet(parsed.as_ref(), &stored, is_pinyin),
                score: *score,
            });
        }

        Ok(SearchResult { hits, total })
    }

    /// 按 ID 取诗（存储字段子集）。
    pub fn get_poem_summary(&self, id: &str) -> tantivy::Result<Option<Poem>> {
        let searcher = self.reader.searcher();
        let term = Term::from_field_text(self.fields.id, id);
        let query = TermQuery::new(term, IndexRecordOption::Basic);
        let top = searcher.search(&query, &TopDocs::with_limit(1).order_by_score())?;

        let Some((_, addr)) = top.first() else {
            return Ok(None);
        };
        let stored: TantivyDocument = searcher.doc(*addr)?;
        Ok(Some(Poem {
            id: text_of(&stored, self.fields.id),
            title: text_of(&stored, self.fields.title),
            title_simplified: String::new(),
            author: text_of(&stored, self.fields.author),
            author_simplified: String::new(),
            dynasty: "唐".to_string(),
            paragraphs: all_text(&stored, self.fields.paragraphs),
            paragraphs_simplified: Vec::new(),
            pinyin_full: all_text(&stored, self.fields.pinyin_full),
            title_pinyin_full: String::new(),
            author_pinyin_full: String::new(),
            tags: Vec::new(),
            notes: None,
            translation: None,
            appreciation: None,
            source: "chinese-poetry".to_string(),
        }))
    }

    fn build_query(&self, query: &str) -> tantivy::Result<(Box<dyn Query>, bool)> {
        let is_ascii_letters = query
            .chars()
            .all(|c| c.is_ascii_alphabetic() || c.is_ascii_whitespace());

        if !is_ascii_letters {
            let mut parser = QueryParser::for_index(
                &self.index,
                vec![self.fields.title, self.fields.author, self.fields.paragraphs],
            );
            parser.set_field_boost(self.fields.title, 3.0);
            parser.set_field_boost(self.fields.author, 2.0);
            return Ok((parser.parse_query(query)?, false));
        }

        // 拼音查询：输入压缩成无空格小写。
        let compact: String = query
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect::<String>()
            .to_ascii_lowercase();

        // 首字母 / 紧凑全拼：前缀匹配（用户通常从头输入）。
        let prefix = format!("{compact}.*");
        let by_initials =
            RegexQuery::from_pattern(&prefix, self.fields.pinyin_initials)?;
        let by_compact =
            RegexQuery::from_pattern(&prefix, self.fields.pinyin_compact)?;

        // 带空格全拼：把连续输入拆成音节前缀，逐个要求出现在 pinyin_full 里。
        // 例 "chuangqian" 对 jieba 无意义，这里退化为对 pinyin_full 的短语近似，
        // 因此仅在全拼字段上做「任意音节组合」的宽松匹配。
        let mut clauses: Vec<(tantivy::query::Occur, Box<dyn Query>)> = vec![
            (tantivy::query::Occur::Should, Box::new(by_initials)),
            (tantivy::query::Occur::Should, Box::new(by_compact)),
        ];

        // 若输入本身含空格，直接按短语查带空格全拼字段。
        // 注意要用**保留空格**的串：compact 已去掉空格，拿去查 "jing ye si" 会失配。
        if query.chars().any(|c| c.is_whitespace()) {
            let spaced = query.trim().to_ascii_lowercase();
            let mut parser = QueryParser::for_index(&self.index, vec![self.fields.pinyin_full]);
            parser.set_conjunction_by_default();
            if let Ok(q) = parser.parse_query(spaced.as_str()) {
                clauses.push((tantivy::query::Occur::Should, q));
            }
        }

        Ok((Box::new(BooleanQuery::new(clauses)), true))
    }

    fn snippet(&self, query: &dyn Query, document: &TantivyDocument, _is_pinyin: bool) -> String {
        // 拼音命中时中文上不会高亮，取正文首段更有意义；
        // 生成器无能为力时也会回落到首段，这里统一处理。
        let field = self.fields.paragraphs;
        if let Ok(generator) =
            tantivy::snippet::SnippetGenerator::create(&self.reader.searcher(), query, field)
        {
            let snippet = generator.snippet_from_doc(document);
            let text = snippet.fragment().trim().to_string();
            if !text.is_empty() {
                return text;
            }
        }
        text_of(document, field)
    }
}

fn text_of(document: &TantivyDocument, field: Field) -> String {
    document
        .get_first(field)
        .and_then(|v| v.as_str().map(str::to_string))
        .unwrap_or_default()
}

fn all_text(document: &TantivyDocument, field: Field) -> Vec<String> {
    document
        .get_all(field)
        .filter_map(|v| v.as_str().map(str::to_string))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn poem(id: &str, title: &str, author: &str, body: &str) -> Poem {
        Poem {
            id: id.to_string(),
            title: title.to_string(),
            title_simplified: title.to_string(),
            author: author.to_string(),
            author_simplified: author.to_string(),
            dynasty: "唐".to_string(),
            paragraphs: vec![body.to_string()],
            paragraphs_simplified: vec![body.to_string()],
            pinyin_full: vec!["jing ye si".to_string()],
            title_pinyin_full: "jing ye si".to_string(),
            author_pinyin_full: "li bai".to_string(),
            tags: vec![],
            notes: None,
            translation: None,
            appreciation: None,
            source: "test".to_string(),
        }
    }

    /// 繁体标题 + 简体标题正确分离的夹具。
    /// 注意不能把 title_simplified 也填成繁体，否则测不出「简体输入命中繁体语料」。
    fn traditional_poem() -> Poem {
        Poem {
            id: "1".to_string(),
            title: "靜夜思".to_string(),
            title_simplified: "静夜思".to_string(),
            author: "李白".to_string(),
            author_simplified: "李白".to_string(),
            dynasty: "唐".to_string(),
            paragraphs: vec!["床前明月光".to_string()],
            paragraphs_simplified: vec!["床前明月光".to_string()],
            pinyin_full: vec!["chuang qian kan yue guang".to_string()],
            title_pinyin_full: "jing ye si".to_string(),
            author_pinyin_full: "li bai".to_string(),
            tags: vec![],
            notes: None,
            translation: None,
            appreciation: None,
            source: "test".to_string(),
        }
    }

    fn engine_with(poems: &[Poem]) -> (tempfile::TempDir, SearchEngine) {
        let dir = tempfile::tempdir().expect("tempdir");
        let engine = SearchEngine::open_or_create(dir.path()).expect("engine");
        engine.rebuild(poems).expect("rebuild");
        (dir, engine)
    }

    #[test]
    fn chinese_query_hits() {
        let (_d, engine) = engine_with(&[traditional_poem()]);
        let r = engine.search("明月", 1, 10).expect("search");
        assert_eq!(r.total, 1, "中文分词检索应命中 1 条");
        assert_eq!(r.hits[0].title, "靜夜思");
    }

    #[test]
    fn simplified_query_hits_traditional_corpus() {
        let (_d, engine) = engine_with(&[traditional_poem()]);
        let r = engine.search("静夜思", 1, 10).expect("search");
        assert_eq!(r.total, 1, "简体输入应命中繁体标题");
    }

    #[test]
    fn initials_query_hits() {
        let (_d, engine) = engine_with(&[traditional_poem()]);
        let r = engine.search("jys", 1, 10).expect("search");
        assert_eq!(r.total, 1, "首字母 jys 应命中《静夜思》");
    }

    #[test]
    fn compact_pinyin_query_hits() {
        let (_d, engine) = engine_with(&[traditional_poem()]);
        let r = engine.search("jingyesi", 1, 10).expect("search");
        assert_eq!(r.total, 1, "紧凑全拼应命中");
    }

    #[test]
    fn spaced_pinyin_query_hits() {
        let (_d, engine) = engine_with(&[traditional_poem()]);
        let r = engine.search("jing ye si", 1, 10).expect("search");
        assert_eq!(r.total, 1, "带空格全拼应命中");
    }

    #[test]
    fn paragraph_pinyin_query_hits() {
        let (_d, engine) = engine_with(&[traditional_poem()]);
        // 正文中段的紧凑拼音：必须靠「按段落拆分」的多值字段才能命中。
        let r = engine.search("chuangqiankanyueguang", 1, 10).expect("search");
        assert_eq!(r.total, 1, "段落全拼应命中");
    }

    #[test]
    fn author_pinyin_query_hits() {
        let (_d, engine) = engine_with(&[traditional_poem()]);
        let r = engine.search("libai", 1, 10).expect("search");
        assert_eq!(r.total, 1, "作者全拼 libai 应命中李白");
    }

    #[test]
    fn author_initials_query_hits() {
        let (_d, engine) = engine_with(&[traditional_poem()]);
        let r = engine.search("lb", 1, 10).expect("search");
        assert_eq!(r.total, 1, "作者首字母 lb 应命中李白");
    }

    #[test]
    fn total_is_real_count_not_page_size() {
        let poems: Vec<Poem> = (0..25)
            .map(|i| poem(&format!("{i}"), "明月", "李白", "床前明月光"))
            .collect();
        let (_d, engine) = engine_with(&poems);
        let r = engine.search("明月", 1, 10).expect("search");
        assert_eq!(r.hits.len(), 10, "一页 10 条");
        assert_eq!(r.total, 25, "total 应为真实命中数，不是页大小");
    }

    #[test]
    fn pagination_offset_works() {
        let poems: Vec<Poem> = (0..15)
            .map(|i| poem(&format!("{i}"), "明月", "李白", "床前明月光"))
            .collect();
        let (_d, engine) = engine_with(&poems);
        let p1 = engine.search("明月", 1, 10).expect("search");
        let p2 = engine.search("明月", 2, 10).expect("search");
        assert_eq!(p1.hits.len(), 10);
        assert_eq!(p2.hits.len(), 5, "第二页应只剩 5 条");
    }

    #[test]
    fn empty_query_returns_empty() {
        let (_d, engine) = engine_with(&[poem("1", "靜夜思", "李白", "床前明月光")]);
        let r = engine.search("   ", 1, 10).expect("search");
        assert_eq!(r.total, 0);
        assert!(r.hits.is_empty());
    }
}

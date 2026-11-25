//! 墨唐检索核心。
//!
//! 规模事实（全量 853,349 首）决定了架构：
//! - 全量全文倒排约 1.3 GB、设备建索引十几分钟 → 不可行
//! - 因此索引**只覆盖标题 / 作者 / 拼音**（体积与耗时都可控）
//! - 正文检索改为对语料文件做流式扫描（见 [`BodyScanner`]）
//!
//! tantivy 相关踩坑见 `.kilo/memory/M1-DEPENDENCY-SPIKE.md`：
//! 中文字段必须显式 `set_tokenizer("jieba")`；`TopDocs` 需 `.order_by_score()`；
//! 真·总数要用 `Count` collector。

pub mod model;

use std::path::{Path, PathBuf};
use std::sync::Mutex;

use tantivy::collector::{Count, TopDocs};
use tantivy::query::{BooleanQuery, Occur, Query, QueryParser, RegexQuery, TermQuery};
use tantivy::schema::{
    Field, IndexRecordOption, Schema, TextFieldIndexing, TextOptions, Value, STORED, STRING,
};
use tantivy::{Index, IndexReader, IndexWriter, TantivyDocument, Term};

pub use model::{Poem, SearchHit, SearchResult};

/// 索引布局版本。schema 变更时必须自增。
pub const INDEX_SCHEMA_VERSION: u32 = 3;

pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

#[derive(Debug, Clone, Copy)]
pub struct Fields {
    pub id: Field,
    pub title: Field,
    pub author: Field,
    /// 标题拼音去空格紧凑串（raw tokenizer，前缀匹配）。
    pub title_pinyin: Field,
    /// 作者拼音去空格紧凑串。
    pub author_pinyin: Field,
    /// 标题拼音首字母。
    pub title_initials: Field,
    /// 作者拼音首字母。
    pub author_initials: Field,
}

pub fn schema() -> (Schema, Fields) {
    // 必须带位置：QueryParser 对「静夜思」这类多词中文会生成 PhraseQuery，
    // Basic（无位置）会导致 0 命中（实测）。体积差异仅 8MB，不值得。
    let jieba = TextFieldIndexing::default()
        .set_tokenizer("jieba")
        .set_index_option(IndexRecordOption::WithFreqsAndPositions);
    let jieba_opts = TextOptions::default()
        .set_indexing_options(jieba)
        .set_stored();

    let raw = TextFieldIndexing::default()
        .set_tokenizer("raw")
        .set_index_option(IndexRecordOption::Basic);
    let raw_opts = TextOptions::default().set_indexing_options(raw);

    let mut builder = Schema::builder();
    let id = builder.add_text_field("id", STRING | STORED);
    let title = builder.add_text_field("title", jieba_opts.clone());
    let author = builder.add_text_field("author", jieba_opts);
    let title_pinyin = builder.add_text_field("title_pinyin", raw_opts.clone());
    let author_pinyin = builder.add_text_field("author_pinyin", raw_opts.clone());
    let title_initials = builder.add_text_field("title_initials", raw_opts.clone());
    let author_initials = builder.add_text_field("author_initials", raw_opts);

    let schema = builder.build();
    (
        schema,
        Fields {
            id,
            title,
            author,
            title_pinyin,
            author_pinyin,
            title_initials,
            author_initials,
        },
    )
}

/// 标题/作者/拼音索引。
pub struct SearchEngine {
    index: Index,
    reader: IndexReader,
    fields: Fields,
}

impl SearchEngine {
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

    /// 重建标题/作者/拼音索引。
    ///
    /// 正文不进索引——全量 85 万首的正文倒排会到 GB 级。
    pub fn rebuild(&self, poems: &[Poem]) -> tantivy::Result<usize> {
        let mut writer: IndexWriter = self.index.writer(64_000_000)?;
        writer.delete_all_documents()?;

        let f = self.fields;
        for poem in poems {
            let mut document = TantivyDocument::default();
            document.add_text(f.id, &poem.id);
            document.add_text(f.title, &poem.title);
            document.add_text(f.author, &poem.author);

            let title_pinyin = compact(&poem.title_pinyin_full);
            if !title_pinyin.is_empty() {
                document.add_text(f.title_pinyin, &title_pinyin);
                document.add_text(f.title_initials, initials(&poem.title_pinyin_full));
            }
            let author_pinyin = compact(&poem.author_pinyin_full);
            if !author_pinyin.is_empty() {
                document.add_text(f.author_pinyin, &author_pinyin);
                document.add_text(f.author_initials, initials(&poem.author_pinyin_full));
            }

            writer.add_document(document)?;
        }

        writer.commit()?;
        writer.wait_merging_threads()?;
        self.reader.reload()?;
        Ok(poems.len())
    }

    /// 按标题 / 作者 / 拼音检索一页。
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

        let parsed = self.build_query(trimmed)?;
        let searcher = self.reader.searcher();

        let total = searcher.search(parsed.as_ref(), &Count)? as u64;
        let top = searcher.search(
            parsed.as_ref(),
            &TopDocs::with_limit(offset + page_size).order_by_score(),
        )?;

        let mut hits = Vec::new();
        for (score, addr) in top.iter().skip(offset) {
            let stored: TantivyDocument = searcher.doc(*addr)?;
            let title = text_of(&stored, self.fields.title);
            let author = text_of(&stored, self.fields.author);
            hits.push(SearchHit {
                poem_id: text_of(&stored, self.fields.id),
                snippet: format!("《{title}》{author}"),
                title,
                author,
                score: *score,
            });
        }

        Ok(SearchResult { hits, total })
    }

    fn build_query(&self, query: &str) -> tantivy::Result<Box<dyn Query>> {
        let is_ascii = query
            .chars()
            .all(|c| c.is_ascii_alphabetic() || c.is_ascii_whitespace());

        if !is_ascii {
            let mut parser = QueryParser::for_index(
                &self.index,
                vec![self.fields.title, self.fields.author],
            );
            parser.set_field_boost(self.fields.title, 2.0);
            return Ok(Box::new(parser.parse_query(query)?));
        }

        let compact_query: String = query
            .chars()
            .filter(|c| !c.is_whitespace())
            .collect::<String>()
            .to_ascii_lowercase();
        let pattern = format!("{compact_query}.*");

        let mut clauses: Vec<(Occur, Box<dyn Query>)> = Vec::new();
        for field in [
            self.fields.title_initials,
            self.fields.author_initials,
            self.fields.title_pinyin,
            self.fields.author_pinyin,
        ] {
            clauses.push((
                Occur::Should,
                Box::new(RegexQuery::from_pattern(&pattern, field)?),
            ));
        }
        Ok(Box::new(BooleanQuery::new(clauses)))
    }

    /// 按 ID 判断是否已在索引中（用于扫描结果去重）。
    pub fn contains_id(&self, id: &str) -> tantivy::Result<bool> {
        let searcher = self.reader.searcher();
        let term = Term::from_field_text(self.fields.id, id);
        let query = TermQuery::new(term, IndexRecordOption::Basic);
        Ok(searcher.search(&query, &Count)? > 0)
    }
}

/// 正文扫描器。
///
/// 全量语料不建正文倒排，改为流式解压扫描。zstd 解压速度远高于磁盘随机读，
/// 一次查询在手机上约百毫秒到一秒，作为「搜诗句」的兜底通道可接受。
pub struct BodyScanner {
    corpus: PathBuf,
    /// 缓存上一次扫描结果，避免同一查询重复解压整个语料。
    cache: Mutex<Option<(String, Vec<SearchHit>)>>,
}

impl BodyScanner {
    pub fn new(corpus: PathBuf) -> Self {
        Self {
            corpus,
            cache: Mutex::new(None),
        }
    }

    /// 在正文中查找包含 `query` 的诗，最多返回 `limit` 条。
    ///
    /// 命中数达到 `limit` 即提前终止——常见词不必扫完 85 万首。
    pub fn search(&self, query: &str, limit: usize) -> Vec<SearchHit> {
        let query = query.trim();
        if query.is_empty() {
            return Vec::new();
        }

        if let Ok(guard) = self.cache.lock() {
            if let Some((cached_query, hits)) = guard.as_ref() {
                if cached_query == query {
                    return hits.iter().take(limit).cloned().collect();
                }
            }
        }

        let hits = self.scan(query, limit);
        if let Ok(mut guard) = self.cache.lock() {
            *guard = Some((query.to_string(), hits.clone()));
        }
        hits
    }

    fn scan(&self, query: &str, limit: usize) -> Vec<SearchHit> {
        use std::io::{BufRead, BufReader};

        let Ok(file) = std::fs::File::open(&self.corpus) else {
            return Vec::new();
        };
        let Ok(decoder) = zstd::stream::read::Decoder::new(BufReader::new(file)) else {
            return Vec::new();
        };

        let mut out = Vec::new();
        for line in BufReader::new(decoder).lines() {
            let Ok(line) = line else { break };
            if !line.contains(query) {
                continue;
            }
            let Ok(poem) = serde_json::from_str::<Poem>(&line) else {
                continue;
            };

            let snippet = poem
                .paragraphs
                .iter()
                .find(|p| p.contains(query))
                .cloned()
                .unwrap_or_else(|| poem.paragraphs.first().cloned().unwrap_or_default());

            out.push(SearchHit {
                poem_id: poem.id,
                title: poem.title,
                author: poem.author,
                snippet,
                score: 1.0,
            });

            if out.len() >= limit {
                break;
            }
        }
        out
    }
}

fn compact(pinyin: &str) -> String {
    pinyin
        .chars()
        .filter(|c| !c.is_whitespace())
        .collect::<String>()
        .to_ascii_lowercase()
}

fn initials(pinyin: &str) -> String {
    pinyin
        .split_whitespace()
        .filter_map(|s| s.chars().next())
        .collect()
}

fn text_of(document: &TantivyDocument, field: Field) -> String {
    document
        .get_first(field)
        .and_then(|v| v.as_str().map(str::to_string))
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn poem(id: &str, title: &str, author: &str, body: &str) -> Poem {
        Poem {
            id: id.to_string(),
            title: title.to_string(),
            author: author.to_string(),
            dynasty: "唐".to_string(),
            paragraphs: vec![body.to_string()],
            pinyin_full: vec![],
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
    fn title_query_hits() {
        let (_d, engine) = engine_with(&[poem("1", "静夜思", "李白", "床前明月光")]);
        let r = engine.search("静夜思", 1, 10).expect("search");
        assert_eq!(r.total, 1);
        assert_eq!(r.hits[0].title, "静夜思");
    }

    #[test]
    fn author_query_hits() {
        let (_d, engine) = engine_with(&[poem("1", "静夜思", "李白", "床前明月光")]);
        let r = engine.search("李白", 1, 10).expect("search");
        assert_eq!(r.total, 1);
    }

    #[test]
    fn title_initials_hit() {
        let (_d, engine) = engine_with(&[poem("1", "静夜思", "李白", "床前明月光")]);
        let r = engine.search("jys", 1, 10).expect("search");
        assert_eq!(r.total, 1, "首字母 jys 应命中《静夜思》");
    }

    #[test]
    fn author_pinyin_hit() {
        let (_d, engine) = engine_with(&[poem("1", "静夜思", "李白", "床前明月光")]);
        let r = engine.search("libai", 1, 10).expect("search");
        assert_eq!(r.total, 1, "作者全拼应命中");
    }

    #[test]
    fn body_is_not_indexed() {
        let (_d, engine) = engine_with(&[poem("1", "静夜思", "李白", "床前明月光")]);
        let r = engine.search("床前明月光", 1, 10).expect("search");
        // 正文不进索引：这条断言锁定架构预期，正文检索由 BodyScanner 负责。
        assert_eq!(r.total, 0, "正文不应命中索引");
    }

    #[test]
    fn total_is_real_count() {
        let poems: Vec<Poem> = (0..25)
            .map(|i| poem(&format!("{i}"), "静夜思", "李白", "床前明月光"))
            .collect();
        let (_d, engine) = engine_with(&poems);
        let r = engine.search("静夜思", 1, 10).expect("search");
        assert_eq!(r.hits.len(), 10);
        assert_eq!(r.total, 25);
    }

    #[test]
    fn empty_query_returns_empty() {
        let (_d, engine) = engine_with(&[poem("1", "静夜思", "李白", "床前明月光")]);
        let r = engine.search("  ", 1, 10).expect("search");
        assert_eq!(r.total, 0);
    }

    #[test]
    fn scanner_finds_body_text() {
        // 构造一个最小 zstd 语料文件验证扫描通道。
        let dir = tempfile::tempdir().expect("tempdir");
        let path = dir.path().join("poems.jsonl.zst");
        let file = std::fs::File::create(&path).expect("create");
        let mut encoder = zstd::stream::write::Encoder::new(file, 3).expect("encoder");
        use std::io::Write;
        for p in [
            poem("1", "静夜思", "李白", "床前明月光，疑是地上霜。"),
            poem("2", "望庐山瀑布", "李白", "日照香炉生紫烟。"),
        ] {
            writeln!(encoder, "{}", serde_json::to_string(&p).unwrap()).expect("write");
        }
        encoder.finish().expect("finish");

        let scanner = BodyScanner::new(path);
        let hits = scanner.search("床前明月光", 10);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].title, "静夜思");
        assert!(hits[0].snippet.contains("床前明月光"));
    }
}

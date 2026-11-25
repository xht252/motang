//! 墨唐 Kotlin↔Rust 绑定层。
//!
//! 导出面保持**粗粒度**：一次调用返回一页检索结果。
//! 所有导出函数返回 `Result<_, MotangError>` 或纯值，禁止 unwrap/expect。
//!
//! 架构（全量 85 万首的必然选择，见 .kilo/memory/DECISIONS.md 第 21 条）：
//! - 索引只覆盖标题 / 作者 / 拼音（体积与建索引耗时可控）
//! - 正文检索与按 ID 取诗走语料文件流式扫描
//!
//! 生命周期：
//!   1. Kotlin 首次启动 `build_index(corpus, indexDir)`；
//!   2. 之后每次启动 `open_index(corpus, indexDir)`；
//!   3. `search` / `get_poem` / `list_authors` 依赖已打开的会话。

uniffi::setup_scaffolding!();

use std::path::Path;
use std::sync::{Mutex, OnceLock};

use motang_search::{BodyScanner, Poem, SearchEngine};

/// 绑定层错误。
///
/// uniffi 会把 `MotangError` 生成为 Kotlin 的 `MotangException`，
/// 且变体是 `kotlin.Exception` 的子类——字段名不能叫 `message`。
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MotangError {
    #[error("内部错误：{reason}")]
    Internal { reason: String },
    #[error("索引未就绪，请先调用 open_index")]
    IndexNotReady,
    #[error("索引操作失败：{reason}")]
    Index { reason: String },
    #[error("语料读取失败：{reason}")]
    Corpus { reason: String },
}

impl From<tantivy::TantivyError> for MotangError {
    fn from(err: tantivy::TantivyError) -> Self {
        MotangError::Index {
            reason: err.to_string(),
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Hit {
    pub poem_id: String,
    pub title: String,
    pub author: String,
    pub snippet: String,
    pub score: f32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Page {
    pub hits: Vec<Hit>,
    pub total: u64,
    /// 是否由正文扫描补全（UI 可提示「含正文匹配」）。
    pub from_body_scan: bool,
}

/// 单首诗（展示用）。语料本身是简体，无 `*_simplified` 冗余字段。
#[derive(Debug, Clone, uniffi::Record)]
pub struct PoemView {
    pub id: String,
    pub title: String,
    pub author: String,
    pub dynasty: String,
    pub paragraphs: Vec<String>,
    pub pinyin_full: Vec<String>,
    pub notes: Option<String>,
    pub translation: Option<String>,
    pub appreciation: Option<String>,
    pub source: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct BuildStats {
    pub poems: u32,
    pub elapsed_ms: u64,
}

/// 一个已打开的会话：索引 + 语料扫描器。
struct Session {
    engine: SearchEngine,
    scanner: BodyScanner,
}

static SESSION: OnceLock<Mutex<Option<Session>>> = OnceLock::new();

fn slot() -> &'static Mutex<Option<Session>> {
    SESSION.get_or_init(|| Mutex::new(None))
}

fn with_session<T>(f: impl FnOnce(&Session) -> Result<T, MotangError>) -> Result<T, MotangError> {
    let guard = slot().lock().map_err(|_| MotangError::Internal {
        reason: "会话锁被污染".to_string(),
    })?;
    let session = guard.as_ref().ok_or(MotangError::IndexNotReady)?;
    f(session)
}

/// 打开已存在的索引，并绑定语料文件用于正文扫描。
#[uniffi::export]
pub fn open_index(corpus_path: String, index_dir: String) -> Result<(), MotangError> {
    let engine = SearchEngine::open_or_create(Path::new(&index_dir))?;
    let scanner = BodyScanner::new(corpus_path.into());
    let mut guard = slot().lock().map_err(|_| MotangError::Internal {
        reason: "会话锁被污染".to_string(),
    })?;
    *guard = Some(Session { engine, scanner });
    Ok(())
}

/// 从语料 JSONL(zstd) 构建标题/作者/拼音索引，并打开会话。
#[uniffi::export]
pub fn build_index(corpus_path: String, index_dir: String) -> Result<BuildStats, MotangError> {
    let started = std::time::Instant::now();
    let poems = load_corpus(Path::new(&corpus_path))?;

    let engine = SearchEngine::open_or_create(Path::new(&index_dir))?;
    let count = engine.rebuild(&poems)?;

    let scanner = BodyScanner::new(corpus_path.into());
    let mut guard = slot().lock().map_err(|_| MotangError::Internal {
        reason: "会话锁被污染".to_string(),
    })?;
    *guard = Some(Session { engine, scanner });

    Ok(BuildStats {
        poems: count as u32,
        elapsed_ms: started.elapsed().as_millis() as u64,
    })
}

/// 检索一页。
///
/// - ASCII 输入（拼音）只查索引（标题/作者拼音、首字母）。
/// - 中文输入先查索引（标题/作者），再用正文扫描补全并去重。
#[uniffi::export]
pub fn search(query: String, page: u32, page_size: u32) -> Result<Page, MotangError> {
    with_session(|session| {
        let page_size_usize = page_size.clamp(1, 100) as usize;
        let index_result = session.engine.search(&query, page, page_size)?;

        let is_ascii = query
            .chars()
            .all(|c| c.is_ascii_alphabetic() || c.is_ascii_whitespace());

        // 拼音输入没有「正文」可扫；中文查询才补扫描。
        // 扫描上限放大到 200：既能把 total 统计得接近真实，
        // 又避免命中过多时把 111MB 语料全扫完。
        let body_hits = if is_ascii || query.trim().is_empty() {
            Vec::new()
        } else {
            session.scanner.search(&query, 200)
        };

        let body_total = body_hits.len() as u64;

        let mut hits: Vec<Hit> = index_result
            .hits
            .into_iter()
            .map(|h| Hit {
                poem_id: h.poem_id,
                title: h.title,
                author: h.author,
                snippet: h.snippet,
                score: h.score,
            })
            .collect();

        let mut added = 0u64;
        for h in body_hits {
            if hits.iter().any(|existing| existing.poem_id == h.poem_id) {
                continue;
            }
            added += 1;
            if hits.len() < page_size_usize {
                hits.push(Hit {
                    poem_id: h.poem_id,
                    title: h.title,
                    author: h.author,
                    snippet: h.snippet,
                    score: h.score,
                });
            }
        }

        Ok(Page {
            hits,
            total: index_result.total + added,
            from_body_scan: body_total > 0,
        })
    })
}

/// 随机抽一首诗（每日打卡用）。
///
/// 蓄水池抽样单遍扫描语料：853k 首约 1s，避免维护十几 MB 的 id 清单。
/// 随机源用时间种子 + xorshift，不引入额外依赖。
#[uniffi::export]
pub fn random_poem(corpus_path: String) -> Result<Option<PoemView>, MotangError> {
    use std::io::{BufRead, BufReader};

    let path = Path::new(&corpus_path);
    let file = std::fs::File::open(path).map_err(|e| MotangError::Corpus {
        reason: format!("打开 {} 失败：{e}", path.display()),
    })?;
    let decoder = zstd::stream::read::Decoder::new(BufReader::new(file)).map_err(|e| {
        MotangError::Corpus {
            reason: format!("zstd 解码初始化失败：{e}"),
        }
    })?;

    let mut state: u64 = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x9E37_79B9_7F4A_7C15)
        | 1;

    let mut seen: u64 = 0;
    let mut chosen: Option<String> = None;
    for line in BufReader::new(decoder).lines() {
        let Ok(line) = line else { break };
        if line.trim().is_empty() {
            continue;
        }
        seen += 1;
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        if state % seen == 0 {
            chosen = Some(line);
        }
    }

    let Some(line) = chosen else { return Ok(None) };
    let poem: Poem = serde_json::from_str(&line).map_err(|e| MotangError::Corpus {
        reason: format!("解析随机诗失败：{e}"),
    })?;
    Ok(Some(to_view(poem)))
}

/// 按 ID 取诗。正文不在索引里，因此从语料文件查找。
#[uniffi::export]
pub fn get_poem(corpus_path: String, id: String) -> Result<Option<PoemView>, MotangError> {
    let needle = format!("\"id\":\"{id}\"");
    let found = scan_for_line(Path::new(&corpus_path), &needle)?;
    Ok(found.map(to_view))
}

/// 列出作者（按前缀过滤），最多 `limit` 条。
#[uniffi::export]
pub fn list_authors(
    meta_dir: String,
    prefix: String,
    limit: u32,
) -> Result<Vec<String>, MotangError> {
    let path = Path::new(&meta_dir).join("poems.authors.json");
    let raw = std::fs::read_to_string(&path).map_err(|e| MotangError::Corpus {
        reason: format!("读取 {} 失败：{e}", path.display()),
    })?;
    let authors: Vec<String> = serde_json::from_str(&raw).map_err(|e| MotangError::Corpus {
        reason: format!("解析作者清单失败：{e}"),
    })?;

    let prefix = prefix.trim();
    let limit = limit.max(1) as usize;
    Ok(authors
        .into_iter()
        .filter(|a| prefix.is_empty() || a.starts_with(prefix))
        .take(limit)
        .collect())
}

#[uniffi::export]
pub fn core_version() -> Result<String, MotangError> {
    Ok(env!("CARGO_PKG_VERSION").to_string())
}

#[uniffi::export]
pub fn ping() -> Result<String, MotangError> {
    Ok(format!(
        "motang-ffi {} / motang-search {} / schema v{} / abi {}",
        env!("CARGO_PKG_VERSION"),
        motang_search::version(),
        motang_search::INDEX_SCHEMA_VERSION,
        std::env::consts::ARCH
    ))
}

fn to_view(p: Poem) -> PoemView {
    PoemView {
        id: p.id,
        title: p.title,
        author: p.author,
        dynasty: p.dynasty,
        paragraphs: p.paragraphs,
        pinyin_full: p.pinyin_full,
        notes: p.notes,
        translation: p.translation,
        appreciation: p.appreciation,
        source: p.source,
    }
}

/// 流式扫描语料，找包含 `needle` 的第一行并解析。
fn scan_for_line(path: &Path, needle: &str) -> Result<Option<Poem>, MotangError> {
    use std::io::{BufRead, BufReader};

    let file = std::fs::File::open(path).map_err(|e| MotangError::Corpus {
        reason: format!("打开 {} 失败：{e}", path.display()),
    })?;
    let decoder = zstd::stream::read::Decoder::new(BufReader::new(file)).map_err(|e| {
        MotangError::Corpus {
            reason: format!("zstd 解码初始化失败：{e}"),
        }
    })?;

    for line in BufReader::new(decoder).lines() {
        let line = line.map_err(|e| MotangError::Corpus {
            reason: format!("读取语料失败：{e}"),
        })?;
        if !line.contains(needle) {
            continue;
        }
        let poem: Poem = serde_json::from_str(&line).map_err(|e| MotangError::Corpus {
            reason: format!("解析语料行失败：{e}"),
        })?;
        return Ok(Some(poem));
    }
    Ok(None)
}

fn load_corpus(path: &Path) -> Result<Vec<Poem>, MotangError> {
    use std::io::{BufRead, BufReader};

    let file = std::fs::File::open(path).map_err(|e| MotangError::Corpus {
        reason: format!("打开 {} 失败：{e}", path.display()),
    })?;
    let decoder = zstd::stream::read::Decoder::new(BufReader::new(file)).map_err(|e| {
        MotangError::Corpus {
            reason: format!("zstd 解码初始化失败：{e}"),
        }
    })?;

    let mut poems = Vec::new();
    for line in BufReader::new(decoder).lines() {
        let line = line.map_err(|e| MotangError::Corpus {
            reason: format!("读取语料失败：{e}"),
        })?;
        if line.trim().is_empty() {
            continue;
        }
        let poem: Poem = serde_json::from_str(&line).map_err(|e| MotangError::Corpus {
            reason: format!("解析语料行失败：{e}"),
        })?;
        poems.push(poem);
    }
    Ok(poems)
}

#[cfg(test)]
fn reset_session() {
    if let Ok(mut guard) = slot().lock() {
        *guard = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ping_contains_versions() {
        let value = ping().expect("ping 不应失败");
        assert!(value.contains("motang-ffi"));
        assert!(value.contains("motang-search"));
    }

    #[test]
    fn search_before_open_is_error() {
        reset_session();
        let err = search("明月".to_string(), 1, 10).expect_err("未打开应报错");
        assert!(matches!(err, MotangError::IndexNotReady));
    }

    #[test]
    fn to_view_keeps_paragraphs() {
        let view = to_view(Poem {
            id: "1".into(),
            title: "静夜思".into(),
            author: "李白".into(),
            dynasty: "唐".into(),
            paragraphs: vec!["床前明月光，疑是地上霜。".into()],
            pinyin_full: vec![],
            title_pinyin_full: String::new(),
            author_pinyin_full: String::new(),
            tags: vec![],
            notes: None,
            translation: None,
            appreciation: None,
            source: "test".into(),
        });
        assert_eq!(view.title, "静夜思");
        assert!(view.notes.is_none());
    }
}

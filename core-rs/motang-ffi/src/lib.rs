//! 墨唐 Kotlin↔Rust 绑定层。
//!
//! 导出面保持**粗粒度**：一次调用返回一页检索结果，不逐条跨语言传对象。
//! 所有导出函数返回 `Result<_, MotangError>` 或纯值，禁止 unwrap/expect。
//!
//! 索引生命周期：
//!   1. Kotlin 首次启动调用 `build_index(corpus, indexDir)` 建索引（IO 线程）。
//!   2. 之后每次进程启动调用 `open_index(indexDir)`。
//!   3. `search` / `get_poem` / `list_authors` 依赖已打开的索引。

uniffi::setup_scaffolding!();

use std::path::Path;
use std::sync::{Mutex, OnceLock};

use motang_search::{Poem, SearchEngine};

/// 绑定层错误。
///
/// 注意：uniffi 会把 `MotangError` 生成为 Kotlin 的 `MotangException`
/// （去掉 Error 后缀 + 追加 Exception），且变体成为 `kotlin.Exception` 的子类。
/// 因此**字段名不能叫 `message`**——会与 `Throwable.message: String?` 冲突。
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

/// 检索命中（跨语言 DTO）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct Hit {
    pub poem_id: String,
    pub title: String,
    pub author: String,
    pub snippet: String,
    pub score: f32,
}

/// 一页检索结果。
#[derive(Debug, Clone, uniffi::Record)]
pub struct Page {
    pub hits: Vec<Hit>,
    pub total: u64,
}

/// 单首诗（展示用）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct PoemView {
    pub id: String,
    pub title: String,
    pub title_simplified: String,
    pub author: String,
    pub author_simplified: String,
    pub dynasty: String,
    pub paragraphs: Vec<String>,
    pub paragraphs_simplified: Vec<String>,
    pub pinyin_full: Vec<String>,
    pub pinyin_initials: Vec<String>,
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

/// 全局索引句柄。
///
/// 用全局 `Mutex<Option<SearchEngine>>` 而非每次都打开：tantivy 的 index/reader
/// 初始化有成本，且 Android 单进程内共享一个读句柄是安全的。
static ENGINE: OnceLock<Mutex<Option<SearchEngine>>> = OnceLock::new();

fn engine_slot() -> &'static Mutex<Option<SearchEngine>> {
    ENGINE.get_or_init(|| Mutex::new(None))
}

fn with_engine<T>(f: impl FnOnce(&SearchEngine) -> Result<T, MotangError>) -> Result<T, MotangError> {
    let guard = engine_slot()
        .lock()
        .map_err(|_| MotangError::Internal {
            reason: "索引锁被污染".to_string(),
        })?;
    let engine = guard.as_ref().ok_or(MotangError::IndexNotReady)?;
    f(engine)
}

/// 打开已存在的索引目录；目录为空时会新建空索引（随后应调用 build_index）。
#[uniffi::export]
pub fn open_index(index_dir: String) -> Result<(), MotangError> {
    let engine = SearchEngine::open_or_create(Path::new(&index_dir))?;
    let mut guard = engine_slot().lock().map_err(|_| MotangError::Internal {
        reason: "索引锁被污染".to_string(),
    })?;
    *guard = Some(engine);
    Ok(())
}

/// 从语料 JSONL(zstd) 构建索引。
///
/// `corpus_path` 指向 `poems.jsonl.zst`。返回写入条数与耗时，
/// 供 Kotlin 侧进度页展示（M1 的进度是「开始/结束」两态，逐条进度留给 M2）。
#[uniffi::export]
pub fn build_index(corpus_path: String, index_dir: String) -> Result<BuildStats, MotangError> {
    let started = std::time::Instant::now();
    let poems = load_corpus(Path::new(&corpus_path))?;

    let engine = SearchEngine::open_or_create(Path::new(&index_dir))?;
    let count = engine.rebuild(&poems)?;

    let mut guard = engine_slot().lock().map_err(|_| MotangError::Internal {
        reason: "索引锁被污染".to_string(),
    })?;
    *guard = Some(engine);

    Ok(BuildStats {
        poems: count as u32,
        elapsed_ms: started.elapsed().as_millis() as u64,
    })
}

/// 检索一页。`page` 从 1 开始。
#[uniffi::export]
pub fn search(query: String, page: u32, page_size: u32) -> Result<Page, MotangError> {
    with_engine(|engine| {
        let result = engine.search(&query, page, page_size)?;
        Ok(Page {
            hits: result
                .hits
                .into_iter()
                .map(|h| Hit {
                    poem_id: h.poem_id,
                    title: h.title,
                    author: h.author,
                    snippet: h.snippet,
                    score: h.score,
                })
                .collect(),
            total: result.total,
        })
    })
}

/// 按 ID 取诗。找不到返回 None。
#[uniffi::export]
pub fn get_poem(id: String) -> Result<Option<PoemView>, MotangError> {
    with_engine(|engine| Ok(engine.get_poem_summary(&id)?.map(to_view)))
}

/// 列出作者（按前缀过滤，空前缀返回全部），最多 `limit` 条。
///
/// 数据源是 ETL 产出的 `poems.authors.json`，由 `meta_dir` 指向其所在目录。
#[uniffi::export]
pub fn list_authors(meta_dir: String, prefix: String, limit: u32) -> Result<Vec<String>, MotangError> {
    let path = Path::new(&meta_dir).join("poems.authors.json");
    let raw = std::fs::read_to_string(&path).map_err(|e| MotangError::Corpus {
        reason: format!("读取 {} 失败：{e}", path.display()),
    })?;
    let authors: Vec<String> = serde_json::from_str(&raw).map_err(|e| MotangError::Corpus {
        reason: format!("解析作者清单失败：{e}"),
    })?;

    let prefix = prefix.trim();
    let limit = limit.max(1) as usize;
    let out = authors
        .into_iter()
        .filter(|a| prefix.is_empty() || a.starts_with(prefix))
        .take(limit)
        .collect();
    Ok(out)
}

/// 绑定层语义版本。
#[uniffi::export]
pub fn core_version() -> Result<String, MotangError> {
    Ok(env!("CARGO_PKG_VERSION").to_string())
}

/// M0 起保留的探针，便于任何时刻确认 native 链路可用。
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
    // 先算派生值：后面会按字段移动 p，移动后再借用 p 会编译失败。
    let initials = p.paragraph_initials();
    PoemView {
        id: p.id,
        title: p.title,
        title_simplified: p.title_simplified,
        author: p.author,
        author_simplified: p.author_simplified,
        dynasty: p.dynasty,
        paragraphs: p.paragraphs,
        paragraphs_simplified: p.paragraphs_simplified,
        pinyin_full: p.pinyin_full,
        pinyin_initials: initials,
        notes: p.notes,
        translation: p.translation,
        appreciation: p.appreciation,
        source: p.source,
    }
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

/// 供内部测试重置全局索引。
#[cfg(test)]
fn reset_engine() {
    if let Ok(mut guard) = engine_slot().lock() {
        *guard = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_poem() -> Poem {
        Poem {
            id: "p1".to_string(),
            title: "靜夜思".to_string(),
            title_simplified: "静夜思".to_string(),
            author: "李白".to_string(),
            author_simplified: "李白".to_string(),
            dynasty: "唐".to_string(),
            paragraphs: vec!["床前看月光".to_string()],
            paragraphs_simplified: vec!["床前看月光".to_string()],
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

    #[test]
    fn ping_contains_versions() {
        let value = ping().expect("ping 不应失败");
        assert!(value.contains("motang-ffi"), "got: {value}");
        assert!(value.contains("motang-search"), "got: {value}");
    }

    #[test]
    fn search_before_open_index_is_error() {
        reset_engine();
        let err = search("明月".to_string(), 1, 10).expect_err("未打开索引应报错");
        assert!(matches!(err, MotangError::IndexNotReady));
    }

    #[test]
    fn to_view_keeps_paragraphs() {
        let view = to_view(sample_poem());
        assert_eq!(view.title, "靜夜思");
        assert_eq!(view.paragraphs.len(), 1);
        assert!(view.notes.is_none(), "M1 注释恒为空");
    }

    #[test]
    fn empty_abi_reject_is_gone() {
        // M0 的 set_runtime_abi 已移除；此处确保 ping 不再依赖外部注入。
        assert!(!ping().unwrap().is_empty());
    }
}

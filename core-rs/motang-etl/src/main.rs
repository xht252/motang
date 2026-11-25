//! 墨唐离线语料 ETL。
//!
//! 数据源：`data/Poetry/唐.csv`（MIT，Copyright 2018 Werner）
//! 字段：`题目, 朝代, 作者, 内容` —— 内容本身即简体。
//!
//! 输出：单文件 JSONL（zstd 压缩）→ `app/src/main/assets/poems.jsonl.zst`
//!       以及 `poems.authors.json`（作者清单，供按作者筛选）
//!
//! 步骤：读 CSV → 拆分段落 → 拼音 → 去重 → 压缩写出。
//!
//! 用法：
//!   cargo run -p motang-etl -- build --input data/Poetry/唐.csv --output out/poems.jsonl.zst

use std::collections::HashSet;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use pinyin::ToPinyin;
use serde::Deserialize;

/// CSV 一行。
#[derive(Debug, Deserialize)]
struct RawRow {
    #[serde(rename = "题目")]
    title: String,
    #[serde(rename = "朝代")]
    dynasty: String,
    #[serde(rename = "作者")]
    author: String,
    #[serde(rename = "内容")]
    content: String,
}

fn main() {
    if let Err(err) = run() {
        eprintln!("motang-etl 失败：{err}");
        std::process::exit(1);
    }
}

/// 拼音生成策略。
///
/// 全量 85 万首时，正文拼音会把产物从 ~100MB 涨到 ~170MB，
/// 而拼音主要用于「按标题/作者快速定位」。因此默认只给标题与作者生成。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum PinyinMode {
    /// 标题 + 作者（默认）
    TitleAuthor,
    /// 标题 + 作者 + 正文
    All,
    /// 不生成
    None,
}

/// 构建参数。
struct BuildOptions {
    input: PathBuf,
    output: PathBuf,
    pinyin: PinyinMode,
}

fn run() -> Result<(), String> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        return Err(usage());
    }

    match args[1].as_str() {
        "build" => {
            let input = arg_value(&args, "--input").ok_or_else(usage)?;
            let output = arg_value(&args, "--output").ok_or_else(usage)?;

            let pinyin = if args.iter().any(|a| a == "--no-pinyin") {
                PinyinMode::None
            } else if args.iter().any(|a| a == "--full-pinyin") {
                PinyinMode::All
            } else {
                PinyinMode::TitleAuthor
            };

            build(BuildOptions {
                input: PathBuf::from(input),
                output: PathBuf::from(output),
                pinyin,
            })
            .map(|s| {
                println!(
                    "完成：{} 首，去重丢弃 {} 首，作者 {} 位，拼音={:?}，输出 {}",
                    s.kept, s.deduped, s.authors, pinyin, s.output_mb
                );
            })
        }
        "--version" | "-V" => {
            println!("motang-etl {}", env!("CARGO_PKG_VERSION"));
            Ok(())
        }
        other => Err(format!("未知子命令：{other}\n\n{}", usage())),
    }
}

fn usage() -> String {
    "用法：motang-etl build --input <csv 文件或目录> --output <poems.jsonl.zst>\n\
     \n\
     --input 指向目录时读取其中全部 *.csv（全部朝代）。\n\
     拼音默认只生成标题与作者；--full-pinyin 连正文一起；--no-pinyin 全不生成。"
        .to_string()
}

fn arg_value(args: &[String], key: &str) -> Option<String> {
    args.iter()
        .position(|a| a == key)
        .and_then(|i| args.get(i + 1))
        .cloned()
}

struct Stats {
    kept: usize,
    deduped: usize,
    authors: usize,
    output_mb: String,
}

fn build(options: BuildOptions) -> Result<Stats, String> {
    let files = collect_csv_files(&options.input)?;
    if files.is_empty() {
        return Err(format!("{} 下没有 CSV", options.input.display()));
    }
    if let Some(parent) = options.output.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建输出目录失败：{e}"))?;
    }

    let file = File::create(&options.output).map_err(|e| format!("创建输出文件失败：{e}"))?;
    let mut encoder =
        zstd::stream::write::Encoder::new(BufWriter::new(file), 19).map_err(|e| e.to_string())?;

    let mut seen: HashSet<String> = HashSet::new();
    let mut authors: HashSet<String> = HashSet::new();
    let mut kept = 0usize;
    let mut deduped = 0usize;

    for path in &files {
        let mut reader = csv::ReaderBuilder::new()
            .has_headers(true)
            .flexible(true)
            .from_path(path)
            .map_err(|e| format!("打开 {} 失败：{e}", path.display()))?;

        for record in reader.deserialize::<RawRow>() {
            let row = match record {
                Ok(row) => row,
                Err(err) => {
                    // 单行损坏不应中断整批；跳过并继续。
                    eprintln!("跳过 {} 的一行：{err}", path.file_name().unwrap_or_default().to_string_lossy());
                    deduped += 1;
                    continue;
                }
            };

            let Some(poem) = normalize(row, options.pinyin) else {
                deduped += 1;
                continue;
            };

            // 去重键：作者 + 标题 + 正文。跨朝代重复收录时只留一份。
            let key = format!(
                "{}\u{1}{}\u{1}{}",
                poem.author,
                poem.title,
                poem.paragraphs.join("\u{1}")
            );
            if !seen.insert(key) {
                deduped += 1;
                continue;
            }

            let line = serde_json::to_string(&poem).map_err(|e| format!("序列化失败：{e}"))?;
            encoder
                .write_all(line.as_bytes())
                .and_then(|_| encoder.write_all(b"\n"))
                .map_err(|e| format!("写入失败：{e}"))?;
            authors.insert(poem.author.clone());
            kept += 1;
        }
    }

    let mut buf = encoder.finish().map_err(|e| format!("压缩收尾失败：{e}"))?;
    buf.flush().map_err(|e| format!("flush 失败：{e}"))?;

    // 作者清单单独产出：用于「按作者筛选」，比从索引词表枚举更简单可靠。
    let mut author_list: Vec<String> = authors.into_iter().collect();
    author_list.sort();
    let authors_path = authors_path_for(&options.output);
    let json = serde_json::to_string(&author_list).map_err(|e| format!("作者序列化失败：{e}"))?;
    std::fs::write(&authors_path, json).map_err(|e| format!("写作者清单失败：{e}"))?;

    let output_mb = std::fs::metadata(&options.output)
        .map(|m| format!("{:.2} MB", m.len() as f64 / 1_048_576.0))
        .unwrap_or_else(|_| "?".to_string());

    Ok(Stats {
        kept,
        deduped,
        authors: author_list.len(),
        output_mb,
    })
}

/// 收集输入：单个文件直接用；目录则取其中全部 `*.csv`，按文件名排序保证可复现。
fn collect_csv_files(input: &Path) -> Result<Vec<PathBuf>, String> {
    if input.is_file() {
        return Ok(vec![input.to_path_buf()]);
    }
    if !input.is_dir() {
        return Err(format!("输入不存在：{}", input.display()));
    }

    let mut files: Vec<PathBuf> = std::fs::read_dir(input)
        .map_err(|e| format!("读取目录失败：{e}"))?
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().map(|x| x == "csv").unwrap_or(false))
        .collect();
    files.sort();
    Ok(files)
}

/// `out/poems.jsonl.zst` → `out/poems.authors.json`
fn authors_path_for(output: &Path) -> PathBuf {
    let stem = output
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("poems")
        .trim_end_matches(".jsonl")
        .to_string();
    output.with_file_name(format!("{stem}.authors.json"))
}

/// 规整单条记录；空标题/空正文返回 None。
fn normalize(row: RawRow, pinyin: PinyinMode) -> Option<motang_search::Poem> {
    let title = clean(&row.title);
    let author = clean(&row.author);
    let dynasty = clean(&row.dynasty);

    if title.is_empty() {
        return None;
    }

    let paragraphs = split_paragraphs(&clean(&row.content));
    if paragraphs.is_empty() {
        return None;
    }

    let (pinyin_full, title_pinyin_full, author_pinyin_full) = match pinyin {
        PinyinMode::None => (Vec::new(), String::new(), String::new()),
        PinyinMode::TitleAuthor => (
            Vec::new(),
            to_full_pinyin(&title),
            to_full_pinyin(&author),
        ),
        PinyinMode::All => (
            paragraphs.iter().map(|p| to_full_pinyin(p)).collect(),
            to_full_pinyin(&title),
            to_full_pinyin(&author),
        ),
    };

    let id = content_id(&author, &title, &paragraphs);

    Some(motang_search::Poem {
        id,
        title,
        author,
        dynasty: if dynasty.is_empty() { "未知".to_string() } else { dynasty },
        paragraphs,
        pinyin_full,
        title_pinyin_full,
        author_pinyin_full,
        tags: Vec::new(),
        // 数据源不含注释/译文/赏析，恒为空（见 DECISIONS 第 4 条）。
        notes: None,
        translation: None,
        appreciation: None,
        source: "Poetry, MIT, Copyright 2018 Werner".to_string(),
    })
}

/// 去掉首尾空白与多余的零宽/BOM 字符。
fn clean(value: &str) -> String {
    value
        .trim_matches(|c: char| c.is_whitespace() || c == '\u{feff}')
        .to_string()
}

/// 按句号切分为段，保留句末标点。
///
/// 源数据把整首诗放在一个字段里（如「床前明月光，疑是地上霜。举头望月…」），
/// 而展示与竖排需要按「联」分段。中文句号是天然的段落边界。
fn split_paragraphs(content: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut current = String::new();

    for ch in content.chars() {
        let is_space = ch == '\n' || ch == '\r';
        if is_space {
            continue;
        }
        current.push(ch);
        if ch == '。' || ch == '！' || ch == '？' {
            let trimmed = current.trim().to_string();
            if !trimmed.is_empty() {
                out.push(trimmed);
            }
            current.clear();
        }
    }

    let tail = current.trim();
    if !tail.is_empty() {
        out.push(tail.to_string());
    }
    out
}

/// 逐字拼音全拼，音节以空格分隔；非汉字（标点）跳过。
fn to_full_pinyin(text: &str) -> String {
    text.to_pinyin()
        .flatten()
        .map(|p| p.plain())
        .collect::<Vec<_>>()
        .join(" ")
}

/// 无上游 id，用内容派生稳定 ID（FNV-1a 64 位）。
fn content_id(author: &str, title: &str, paragraphs: &[String]) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in author
        .bytes()
        .chain(title.bytes())
        .chain(paragraphs.iter().flat_map(|p| p.bytes()))
    {
        hash ^= byte as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("mt-{hash:016x}")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn full_pinyin_is_space_separated() {
        assert_eq!(to_full_pinyin("静夜思"), "jing ye si");
    }

    #[test]
    fn paragraphs_split_on_period() {
        let parts = split_paragraphs("床前明月光，疑是地上霜。举头望月明，低头思故乡。");
        assert_eq!(parts.len(), 2);
        assert_eq!(parts[0], "床前明月光，疑是地上霜。");
        assert_eq!(parts[1], "举头望月明，低头思故乡。");
    }

    #[test]
    fn trailing_text_without_period_is_kept() {
        let parts = split_paragraphs("一句没有句号的话");
        assert_eq!(parts, vec!["一句没有句号的话".to_string()]);
    }

    #[test]
    fn content_id_is_stable_and_distinct() {
        let a = content_id("李白", "静夜思", &["床前明月光".to_string()]);
        let b = content_id("李白", "静夜思", &["床前明月光".to_string()]);
        let c = content_id("李白", "静夜思", &["疑是地上霜".to_string()]);
        assert_eq!(a, b);
        assert_ne!(a, c);
        assert!(a.starts_with("mt-"));
    }

    #[test]
    fn normalize_splits_and_derives_pinyin() {
        let row = RawRow {
            title: " 静夜思 ".into(),
            dynasty: "唐".into(),
            author: " 李白 ".into(),
            content: "床前明月光，疑是地上霜。举头望月明，低头思故乡。".into(),
        };
        let poem = normalize(row, PinyinMode::All).expect("应有效");
        assert_eq!(poem.title, "静夜思");
        assert_eq!(poem.author, "李白");
        assert_eq!(poem.paragraphs.len(), 2);
        assert_eq!(poem.title_pinyin_full, "jing ye si");
        assert_eq!(poem.author_pinyin_full, "li bai");
        assert!(poem.notes.is_none(), "数据源无注释");
    }

    #[test]
    fn invalid_row_returns_none() {
        let row = RawRow {
            title: "".into(),
            dynasty: "唐".into(),
            author: "李白".into(),
            content: "床前明月光".into(),
        };
        assert!(normalize(row, PinyinMode::All).is_none());
    }

    #[test]
    fn empty_content_returns_none() {
        let row = RawRow {
            title: "静夜思".into(),
            dynasty: "唐".into(),
            author: "李白".into(),
            content: "   ".into(),
        };
        assert!(normalize(row, PinyinMode::All).is_none());
    }
}

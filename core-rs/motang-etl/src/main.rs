//! 墨唐离线语料 ETL。
//!
//! 输入：`chinese-poetry` 的 `全唐诗/poet.tang.*.json` 与 `唐诗三百首.json`
//! 输出：单文件 JSONL（zstd 压缩）→ `app/src/main/assets/poems.jsonl.zst`
//!
//! 步骤：读取 → 规整字段 → 繁简对照 → 拼音全拼/首字母 → 去重 → 压缩写出。
//!
//! 用法：
//!   cargo run -p motang-etl -- build --input data/chinese-poetry --output out/poems.jsonl.zst

use std::collections::HashSet;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use pinyin::ToPinyin;
use serde::Deserialize;

/// 上游 `全唐诗` 分片的字段结构。
#[derive(Debug, Deserialize)]
struct RawPoem {
    #[serde(default)]
    id: Option<String>,
    title: String,
    author: String,
    paragraphs: Vec<String>,
    #[serde(default)]
    tags: Vec<String>,
}

fn main() {
    if let Err(err) = run() {
        eprintln!("motang-etl 失败：{err}");
        std::process::exit(1);
    }
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
            build(Path::new(&input), Path::new(&output)).map(|s| {
                println!(
                    "完成：{} 首，去重丢弃 {} 首，输出 {}",
                    s.kept,
                    s.deduped,
                    output
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
    "用法：motang-etl build --input <语料目录> --output <poems.jsonl.zst>\n\
     \n\
     语料目录下应有 全唐诗/ 子目录，内含 poet.tang.<n>.json 与 唐诗三百首.json。"
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
}

fn build(input: &Path, output: &Path) -> Result<Stats, String> {
    let shard_dir = input.join("全唐诗");
    if !shard_dir.is_dir() {
        return Err(format!("找不到语料目录：{}", shard_dir.display()));
    }

    let conv = zhhz::Converter::new(zhhz::Config::T2s);

    // 按文件名排序保证输出稳定（可复现构建）。
    let mut files: Vec<PathBuf> = std::fs::read_dir(&shard_dir)
        .map_err(|e| format!("读取目录失败：{e}"))?
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().map(|e| e == "json").unwrap_or(false))
        .collect();
    files.sort();

    if files.is_empty() {
        return Err(format!("{} 下没有 json 文件", shard_dir.display()));
    }

    if let Some(parent) = output.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建输出目录失败：{e}"))?;
    }

    let file = File::create(output).map_err(|e| format!("创建输出文件失败：{e}"))?;
    let mut encoder =
        zstd::stream::write::Encoder::new(BufWriter::new(file), 19).map_err(|e| e.to_string())?;

    let mut seen: HashSet<String> = HashSet::new();
    let mut authors: HashSet<String> = HashSet::new();
    let mut kept = 0usize;
    let mut deduped = 0usize;

    for path in &files {
        let raw = std::fs::read_to_string(path)
            .map_err(|e| format!("读取 {} 失败：{e}", path.display()))?;
        let poems: Vec<RawPoem> = serde_json::from_str(&raw)
            .map_err(|e| format!("解析 {} 失败：{e}", path.display()))?;

        for rp in poems {
            let Some(poem) = normalize(rp, &conv) else {
                deduped += 1;
                continue;
            };

            // 去重键：作者 + 标题 + 正文。同一首诗在不同分片重复出现时只留一份。
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

    // 作者清单单独产出一个 JSON：用于「按作者筛选」的列表，
    // 比从索引词表动态枚举更简单、更快，且不依赖 tantivy 内部 API。
    let mut author_list: Vec<String> = authors.into_iter().collect();
    author_list.sort();
    let authors_path = authors_path_for(output);
    let json = serde_json::to_string(&author_list).map_err(|e| format!("作者序列化失败：{e}"))?;
    std::fs::write(&authors_path, json).map_err(|e| format!("写作者清单失败：{e}"))?;

    Ok(Stats { kept, deduped })
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

/// 规整单条记录；空标题/空正文返回 None（视为无效数据丢弃）。
fn normalize(raw: RawPoem, conv: &zhhz::Converter) -> Option<motang_search::Poem> {
    let title = raw.title.trim().to_string();
    let author = raw.author.trim().to_string();
    let paragraphs: Vec<String> = raw
        .paragraphs
        .into_iter()
        .map(|p| p.trim().to_string())
        .filter(|p| !p.is_empty())
        .collect();

    if title.is_empty() || paragraphs.is_empty() {
        return None;
    }

    let paragraphs_simplified: Vec<String> =
        paragraphs.iter().map(|p| conv.convert(p)).collect();

    let pinyin_full: Vec<String> = paragraphs.iter().map(|p| to_full_pinyin(p)).collect();

    let title_simplified = conv.convert(&title);
    let author_simplified = conv.convert(&author);

    // 标题拼音必须单独产出：否则「jys」查不到《静夜思》——标题不在 paragraphs 里。
    // 首字母 / 紧凑全拼都由 pinyin_full 在**建索引时**派生，不落盘（省 assets 体积）。
    let title_pinyin_full = to_full_pinyin(&title);
    let author_pinyin_full = to_full_pinyin(&author);

    let id = raw
        .id
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| content_id(&author, &title, &paragraphs));

    Some(motang_search::Poem {
        id,
        title,
        title_simplified,
        author,
        author_simplified,
        dynasty: "唐".to_string(),
        paragraphs,
        paragraphs_simplified,
        pinyin_full,
        title_pinyin_full,
        author_pinyin_full,
        tags: raw.tags,
        // 上游无注释/译文/赏析，M1 恒为空（DECISIONS 第 4 条）。
        notes: None,
        translation: None,
        appreciation: None,
        source: "chinese-poetry".to_string(),
    })
}

/// 逐字拼音全拼，音节以空格分隔；非汉字（标点）原样跳过。
///
/// 例：`床前明月光` → `chuang qian ming yue guang`
fn to_full_pinyin(text: &str) -> String {
    text.to_pinyin()
        .flatten()
        .map(|p| p.plain())
        .collect::<Vec<_>>()
        .join(" ")
}

/// 无上游 id 时，用内容派生稳定 ID（FNV-1a 64 位）。
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
        assert_eq!(to_full_pinyin("靜夜思"), "jing ye si");
    }

    #[test]
    fn content_id_is_stable_and_distinct() {
        let a = content_id("李白", "靜夜思", &["床前明月光".to_string()]);
        let b = content_id("李白", "靜夜思", &["床前明月光".to_string()]);
        let c = content_id("李白", "靜夜思", &["疑是地上霜".to_string()]);
        assert_eq!(a, b);
        assert_ne!(a, c);
        assert!(a.starts_with("mt-"));
    }

    #[test]
    fn normalize_drops_empty_paragraphs() {
        let conv = zhhz::Converter::new(zhhz::Config::T2s);
        let raw = RawPoem {
            id: None,
            title: " 靜夜思 ".into(),
            author: " 李白 ".into(),
            paragraphs: vec!["床前明月光".into(), "   ".into()],
            tags: vec![],
        };
        let poem = normalize(raw, &conv).expect("应有效");
        assert_eq!(poem.title, "靜夜思");
        assert_eq!(poem.author, "李白");
        assert_eq!(poem.paragraphs.len(), 1);
        assert_eq!(poem.title_simplified, "静夜思");
        assert_eq!(poem.title_pinyin_full, "jing ye si");
        assert_eq!(poem.paragraphs_simplified[0], "床前明月光");
    }

    #[test]
    fn invalid_poem_returns_none() {
        let conv = zhhz::Converter::new(zhhz::Config::T2s);
        let raw = RawPoem {
            id: None,
            title: "".into(),
            author: "李白".into(),
            paragraphs: vec!["床前明月光".into()],
            tags: vec![],
        };
        assert!(normalize(raw, &conv).is_none());
    }
}

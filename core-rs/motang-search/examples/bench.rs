//! 真实语料检索基准（新架构：轻索引 + 正文扫描）。
//!
//! 用法：
//!   cargo run --release -p motang-search --example bench -- <poems.jsonl.zst> [索引目录]
//!
//! 输出：建索引耗时、索引体积、索引通道与正文扫描通道的 P95。

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::time::Instant;

use motang_search::{BodyScanner, Poem, SearchEngine};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let corpus = args.next().unwrap_or_else(|| "out/all.jsonl.zst".to_string());
    let index_dir = args
        .next()
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::temp_dir().join("motang-bench-index"));

    let load_start = Instant::now();
    let poems = load_corpus(Path::new(&corpus))?;
    println!("载入 {} 首，耗时 {:?}", poems.len(), load_start.elapsed());

    let _ = std::fs::remove_dir_all(&index_dir);
    let engine = SearchEngine::open_or_create(&index_dir)?;

    let t0 = Instant::now();
    let n = engine.rebuild(&poems)?;
    println!("建索引 {n} 首，耗时 {} ms", t0.elapsed().as_millis());

    let index_mb = dir_size(&index_dir) as f64 / 1_048_576.0;
    println!("索引体积 {index_mb:.1} MB");

    let scanner = BodyScanner::new(PathBuf::from(&corpus));

    // 索引通道（标题/作者/拼音）
    for query in ["静夜思", "李白", "jys", "libai"] {
        let (p95, total) = bench(|| engine.search(query, 1, 10).map(|r| r.total).unwrap_or(0));
        println!("[index] query={query:<12} total={total:<6} p95={:>6} us", p95.as_micros());
    }

    // 正文扫描通道
    for query in ["床前明月光", "明月"] {
        let t = Instant::now();
        let hits = scanner.search(query, 20);
        let first = t.elapsed();
        println!(
            "[scan ] query={query:<12} hits={:<3} first={:>7} ms",
            hits.len(),
            first.as_millis()
        );
    }

    // 诊断：确认语料与拼音字段
    if let Some(p) = poems.iter().find(|p| p.title == "静夜思") {
        println!("--- 诊断 静夜思 ---");
        println!("  dynasty={} author={}", p.dynasty, p.author);
        println!("  title_pinyin={}", p.title_pinyin_full);
        println!("  body={}", p.paragraphs.join(""));
    }

    Ok(())
}

fn bench(mut f: impl FnMut() -> u64) -> (std::time::Duration, u64) {
    let mut samples = Vec::new();
    let mut total = 0;
    for _ in 0..20 {
        let t = Instant::now();
        total = f();
        samples.push(t.elapsed());
    }
    samples.sort();
    let p95 = samples[(samples.len() * 95 / 100).min(samples.len() - 1)];
    (p95, total)
}

fn dir_size(path: &Path) -> u64 {
    let mut total = 0;
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            if meta.is_dir() {
                total += dir_size(&entry.path());
            } else {
                total += meta.len();
            }
        }
    }
    total
}

fn load_corpus(path: &Path) -> Result<Vec<Poem>, Box<dyn std::error::Error>> {
    let file = std::fs::File::open(path)?;
    let decoder = zstd::stream::read::Decoder::new(BufReader::new(file))?;
    let reader = BufReader::new(decoder);
    let mut poems = Vec::new();
    for line in reader.lines() {
        let line = line?;
        if line.trim().is_empty() {
            continue;
        }
        poems.push(serde_json::from_str::<Poem>(&line)?);
    }
    Ok(poems)
}

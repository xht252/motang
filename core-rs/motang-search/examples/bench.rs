//! 真实语料检索基准。
//!
//! 用法：
//!   cargo run --release -p motang-search --example bench -- <poems.jsonl.zst> [索引目录]
//!
//! 作用：把 ETL 产物建索引，跑三类查询（中文 / 全拼 / 首字母），
//! 打印建索引耗时与 P95 查询延迟。这是 M1 验收的证据来源。

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::time::Instant;

use motang_search::{Poem, SearchEngine};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    let corpus = args.next().unwrap_or_else(|| {
        "out/poems.jsonl.zst".to_string()
    });
    let index_dir = args
        .next()
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::temp_dir().join("motang-bench-index"));

    let poems = load_corpus(Path::new(&corpus))?;
    println!("载入 {} 首", poems.len());

    let engine = SearchEngine::open_or_create(&index_dir)?;

    let t0 = Instant::now();
    let n = engine.rebuild(&poems)?;
    let build_ms = t0.elapsed().as_millis();
    println!("建索引 {n} 首，耗时 {build_ms} ms");

    for query in ["明月", "李白", "静夜思", "libai", "jys", "chuangqian", "chuangqiankanyueguang", "chuangqianmingyueguang", "床前明月光", "床前看月光"] {
        let mut samples = Vec::new();
        let mut first: Option<(usize, u64)> = None;
        for _ in 0..20 {
            let t = Instant::now();
            let r = engine.search(query, 1, 10)?;
            samples.push(t.elapsed());
            if first.is_none() {
                first = Some((r.hits.len(), r.total));
            }
        }
        samples.sort();
        let p95 = samples[(samples.len() * 95 / 100).min(samples.len() - 1)];
        let (hits, total) = first.unwrap_or((0, 0));
        println!(
            "query={:<24} hits={:<3} total={:<5} p95={:>6} us",
            query,
            hits,
            total,
            p95.as_micros()
        );
    }

    // 打印首条命中，便于人工核对高亮片段是否合理。
    if let Ok(r) = engine.search("明月", 1, 3) {
        for h in r.hits {
            println!("  命中：《{}》{}  score={:.3}  {}", h.title, h.author, h.score, h.snippet);
        }
    }

    // 诊断：确认《静夜思》是否在语料中，以及它的拼音字段长什么样。
    if let Some(p) = poems.iter().find(|p| p.title_simplified == "静夜思") {
        println!("--- 诊断 静夜思 ---");
        println!("  id={}", p.id);
        println!("  pinyin_compact={}", p.compact_pinyin());
        println!("  pinyin_full[0]={:?}", p.pinyin_full.first());
        println!("  title_initials={}", p.title_initials());
    } else {
        println!("--- 诊断：语料中找不到 title_simplified == 静夜思 ---");
    }

    Ok(())
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

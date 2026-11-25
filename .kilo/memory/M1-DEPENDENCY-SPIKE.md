# M1 · 依赖配对 Spike 结论（已验证）

> 本文件记录 `chinese-search` skill 要求的「先做依赖配对 spike」的实测结果。
> 结论均为 2026-11-25 在 Windows + Rust 1.100.0 上真实编译运行得到，
> 不是查文档推测。

## 锁定的版本组合

| crate | 版本 | 许可证 | 用途 |
|---|---|---|---|
| `tantivy` | 0.26.2 | MIT | 全文索引 |
| `tantivy-jieba` | 0.21.0 | MIT | jieba 分词接入 tantivy |
| `jieba-rs` | 0.11.0 | MIT | 中文分词 |
| `pinyin` | 0.11.0 | MIT | 拼音全拼/首字母 |
| `zhhz` | 0.8.2 | **Apache-2.0** | 繁简转换（纯 Rust、数据内嵌） |

**依赖关系已核对**：`tantivy-jieba 0.21` 依赖 `tantivy-tokenizer-api ^0.7`，
`tantivy 0.26.2` 同样依赖 `^0.7`，因此三者可共存，无重复 tantivy 实例
（`cargo tree -i tantivy` 只输出一个版本）。

## 一票否决的候选

### `zhconv` 0.4.2 —— 不可用
- 许可证 **GPL-2.0-or-later**。
- 打进分发的 APK 会强制整个 App 变成 GPL，与当前 MIT 仓库冲突。
- 功能完全可用（MediaWiki/OpenCC 规则），但授权不可接受。

### `opencc-rust` / `opencc` / `opencc-sys` —— 不可用
- 均为 C++ OpenCC 的 **FFI 绑定**，需要宿主提供 libopencc。
- Android 交叉编译要额外带 C++ 依赖与 `opencc-sys` 的构建脚本，
  与「禁止引入 Python 运行时」同类问题：增加不可控的原生构建面。

**选定 `zhhz`**：纯 Rust 重实现 OpenCC，词典以 `include-flate` 内嵌，
无运行时数据文件、无 C++ 依赖，交叉编译只需 `cargo ndk`。

## 实测 API 与坑（直接照抄，别再猜）

### 1. 中文检索必须显式挂 tokenizer —— 否则 0 命中

```rust
// ❌ 只注册 tokenizer，字段仍用默认空白分词 → 中文整段成一个 term，查询 0 命中
index.tokenizers().register("jieba", tantivy_jieba::JiebaTokenizer::new());
let title = builder.add_text_field("title", TEXT | STORED);

// ✅ 字段的 indexing options 必须指向 "jieba"
let text_indexing = TextFieldIndexing::default()
    .set_tokenizer("jieba")
    .set_index_option(IndexRecordOption::WithFreqsAndPositions);
let text_opts = TextOptions::default()
    .set_indexing_options(text_indexing)
    .set_stored();
let title = builder.add_text_field("title", text_opts.clone());
```

实测：修正前 `hits(明月)=0`，修正后 `hits=1` 且命中《静夜思》。

### 2. `tantivy 0.26` 的 `TopDocs` 不是 `Collector`

```rust
// ❌ error[E0277]: the trait bound `TopDocs: Collector` is not satisfied
searcher.search(&query, &TopDocs::with_limit(10))?;

// ✅ 0.26 起必须显式指定排序
searcher.search(&query, &TopDocs::with_limit(10).order_by_score())?;
```

### 3. `Index::create_in_dir` 要求目录已存在

```rust
std::fs::create_dir_all(dir)?;
let index = Index::create_in_dir(dir, schema)?;
```
否则 `OpenDirectoryError(DoesNotExist(...))`。

### 4. `pinyin 0.11` 的 `Args` / `lazy_pinyin` 已弃用

```rust
// ❌ deprecated: 请使用 ToPinyin 代替
pinyin::lazy_pinyin("静夜思", &pinyin::Args::new());

// ✅
use pinyin::ToPinyin;
let plain: Vec<&'static str> = "静夜思"
    .to_pinyin()
    .filter_map(|p| p.map(|p| p.plain()))
    .collect();
```
实测输出：`pinyin_full=jingyesi`，`initials=jys`。

### 5. `zhhz` 的调用形态

```rust
let conv = zhhz::Converter::new(zhhz::Config::T2s);
let simplified = conv.convert("靜夜思"); // → "静夜思"
```
`Config` 是枚举（`S2t` / `T2s` / `S2tw` / `T2jp` 等 16 种），
**不是** `Conversion` 结构体，也没有 `zhhz::convert()` 自由函数。

## 对 M1 实现的影响

1. `motang-search` 的 schema 必须为 title/author/paragraphs 建 jieba 分词字段，
   并额外建 `pinyin_full` / `pinyin_initials` 两个独立字段（用默认 tokenizer 即可，
   拼音是 ASCII，不需要 jieba）。
2. `motang-etl` 负责产出 `pinyin_full` / `pinyin_initials` 与繁简双字段，
   `motang-search` 只读不转。
3. 索引目录必须先 `create_dir_all`。

## 复现方式

Spike 工程保留在 `%TEMP%\kilo\spike-m1`。若要重建：
```powershell
cargo new spike-m1 && cd spike-m1
cargo add tantivy@0.26 tantivy-jieba@0.21 jieba-rs@0.11 pinyin@0.11 zhhz@0.8.2
cargo run
```
期望输出：
```
hits(MingYue)=1
  title=静夜思
pinyin_full=jingyesi initials=jys
t2s=静夜思
```

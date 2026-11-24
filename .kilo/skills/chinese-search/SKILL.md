---
name: chinese-search
description: 当任务涉及 tantivy schema、jieba 分词、拼音全拼/首字母检索、高亮分页或索引构建时使用。M1 必读。
---

# 约束

- tantivy 索引目录用 `context.filesDir`，`MmapDirectory`；查询在 Rust 侧线程池执行。
- 字段：标题 / 作者 / 正文（jieba 分词）+ 拼音全拼 + 拼音首字母。
- 拼音首字母需支持 `jys` → 《静夜思》；多音字定主读音策略并写单测。
- 首次启动在 IO 线程建索引，UI 显示进度页（进度页同样满足 320dp 可用），失败可重建。

# 待补充（M1 开工前）

**先做依赖配对 spike**：`tantivy` 与 `tantivy-jieba` / `jieba-rs` 的版本组合
（tokenizer API 版本敏感），锁定后再写业务。另需评估 jieba 内嵌词典对 `.so` 体积的影响。

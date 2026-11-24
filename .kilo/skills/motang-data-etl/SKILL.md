---
name: motang-data-etl
description: 当任务涉及 chinese-poetry 语料清洗、字段规整、繁简转换、拼音生成、去重或 assets 产物压缩时使用。M1 必读。
---

# 数据源事实

- `chinese-poetry` 全唐诗 JSON 位于 `全唐诗/poet.tang.<n>.json`（按编号分片）。
- `全唐诗/唐诗三百首.json` 每条只有 `author` / `title` / `paragraphs` / `tags` / `id`，
  **不含**注释/译文/赏析 → schema 中三字段恒为 `null`（见 DECISIONS 第 4 条）。
- 诗总数以实测为准（方案写 5.7 万，上游 README 称 5.5 万）。

# 待补充（M1 开工前）

清洗规则、术语表、繁简双字段策略、多音字主读音策略、去重哈希定义
（作者+标题+正文）、产物格式（JSONL + 压缩）与 assets 放置路径。

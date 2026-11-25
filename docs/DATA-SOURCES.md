# 数据来源与许可

## 语料：data/Poetry

- 来源：用户本地搜集的 `Poetry` 数据集（`data/Poetry/*.csv`，34 个文件）
- 许可：**MIT License**，Copyright (c) 2018 Werner
- 字段：`题目 / 朝代 / 作者 / 内容`，文本为简体
- 规模：**853,349 首**（去重后），28,424 位作者
- 许可原文：`data/Poetry/LICENSE`（该目录整体不入库，需本地保留）

**为什么不入库**：原始 CSV 共 228 MB，派生的 `poems.jsonl.zst` 111 MB。
两者都由构建流程可再生，入库会让仓库体积失控。

**如何重建**（需本机存在 `data/Poetry`）：

```powershell
# Gradle 会自动调用；也可手动执行：
cargo run --release -p motang-etl -- build --input ..\data\Poetry --output out\all.jsonl.zst
```

拼音策略：默认只生成标题与作者拼音（`--full-pinyin` 连正文，`--no-pinyin` 全不生成）。

## 字体：霞鹜文楷

- 许可：SIL Open Font License 1.1（OFL）
- 用途：正文与标题书法体（M2 引入）
- 分发时须保留 OFL 声明

## UI 设计参考：ui-ux-pro-max

- 仓库：https://github.com/nextlevelbuilder/ui-ux-pro-max-skill
- 许可：MIT
- 用途：仅作为设计方法参考（配色、排版、无障碍检查表），其数据与脚本见
  `.kilo/skills/ui-ux-pro-max/`

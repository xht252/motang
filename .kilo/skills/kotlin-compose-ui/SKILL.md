---
name: kotlin-compose-ui
description: 当任务涉及 Compose 页面、水墨设计令牌、竖排诗文、Canvas 背景、导航结构或 Preview/截图测试时使用。M2 及所有 UI 任务必读。
---

# 设计令牌（M0 已建初版，见 `app/.../ui/theme/Theme.kt`）

宣纸 `#F5F0E6`、墨 `#2B2B2B`、黛蓝 `#425066`、赭石 `#955539`、
竹青 `#789262`、印章胭脂 `#9D2933`。字体霞鹜文楷（OFL，放 `app/src/main/assets/fonts/`）。

# 关键约定

- 全部 Compose，禁止 XML 布局；列表必须 `LazyColumn` + 稳定 key。
- 字号/间距随 `widthSizeClass` 三档缩放；正文最大行宽 600dp。
- 背景：Canvas 多层远山剪影 + 宣纸噪点，按窗口宽高比自适应构图；
  窄窗口（分屏）自动减少层数保帧率。
- 竖排诗文用自定义 `Layout`（右起列排），仅在详情 pane ≥ 560dp 时默认启用。
- 深色模式提供"夜读"墨色主题；所有页面提供 Compose Preview（360/420/820/940dp）。

# 待补充（M2 开工前）

设计令牌表、竖排 Layout 实现要点、印章组件绘制方式、导航结构图、截图测试基线。

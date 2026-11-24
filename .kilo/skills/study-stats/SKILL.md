---
name: study-stats
description: 当任务涉及背诵会话计时、active_ms 口径、Room 聚合 SQL、周柱状图、月热力图或悬停背诵模式时使用。M4 必读。
---

# 口径

- 时长 = `active_ms`（不含暂停与后台），全 App 统一。
- 会话落库：`{poem_ids, start, end, active_ms, mastered}`。
- `ON_STOP` 自动暂停、`ON_START` 恢复；可手动暂停/结束。
- 杀进程重进：未结束会话可恢复或安全结算。

# 聚合

Room SQL `GROUP BY`；跨周/月边界与时区必须有单元测试。
月视图日历热力图用 Canvas 自绘；配色遵循水墨令牌。

# 待补充（M4 开工前）

表结构 DDL、聚合查询清单、图表可读性方案（320dp 与 940dp）。

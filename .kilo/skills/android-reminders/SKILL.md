---
name: android-reminders
description: 当任务涉及每日提醒、精确闹钟权限矩阵、WorkManager 降级、BOOT 重注册、通知或 OriginOS 后台白名单引导时使用。M3 必读。
---

# 权限矩阵（直装分发，不上 Play）

| API | 声明 | 行为 |
|---|---|---|
| 26–30 | 无 | 直接 `setExactAndAllowWhileIdle` |
| 31–32 | `SCHEDULE_EXACT_ALARM`（`maxSdkVersion=32`） | 默认授予，保留防御性检查 |
| 33+ | `USE_EXACT_ALARM` | 安装即授予，无需运行时申请 |

13+ 仍需请求 `POST_NOTIFICATIONS`。保留 `canScheduleExactAlarms` 失败时的
WorkManager flex 降级，覆盖 OEM 异常行为。精确闹钟链式注册次日。

# OriginOS 专项（不可裁剪）

检测 `Build.MANUFACTURER == "vivo"` 时展示引导：i管家自启动、后台高耗电白名单、
锁屏通知显示；图文步骤 + 一键跳转 Intent，失败展示路径文案。

# 待补充（M3 开工前）

streak 跨月/时区算法、通知文案（每日一诗名句来源）、跳转 Intent 清单。

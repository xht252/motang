---
name: perf-release
description: 当任务涉及 Baseline Profile、Macrobenchmark、R8、体积、16KB 校验、直装发布或导出/导入迁移时使用。M6 必读。
---

# 验证矩阵

- Baseline Profile 覆盖：启动 / 检索 / 详情 / 统计。
- Macrobenchmark：冷启动、检索延迟、列表 jank，外加窗口 resize/开合帧指标
  （Resizable AVD `device_state` + 真机开合 Perfetto 各一轮）。
- 连续性 Instrumented test：开合/旋转/分屏矩阵下滚动位置、会话计时、流式缓冲零丢失。

# 发布（自签直装）

- release 仅 `arm64-v8a`；`:llm` 直接打进 APK；产物命名 `motang-v{versionName}-{versionCode}.apk`；
  APK（不含模型）≤ 40MB。
- 16KB 对齐校验输出警告但不阻断（`check-16k-pagesize.ps1 -WarnOnly`）。
- 签名指纹与上一版一致；versionCode 递增。
- 换机迁移：Room 全表 + DataStore → zip（含 `manifest.json`：schema 版本 + app 版本 + 校验和），
  SAF 写入；导入做版本校验与事务性恢复。模型文件不打包。

# 待补充（M6 开工前）

R8 full mode 与 JNA/uniffi 兼容验证、Baseline Profile 生成流程、导出格式版本号策略。

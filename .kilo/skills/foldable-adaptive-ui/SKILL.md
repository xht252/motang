---
name: foldable-adaptive-ui
description: 当任务涉及窗口尺寸分级、双栏布局、折叠悬停、铰链避让、分屏小窗、开合连续性或多尺寸预览/截图测试时使用。
---

# 依赖（已在 Version Catalog）

- `androidx.compose.material3.adaptive:adaptive` / `adaptive-layout` / `adaptive-navigation`
- `androidx.window:window`（`WindowInfoTracker`、`FoldingFeature`）

# 布局流程

1. 入口用 `calculateWindowSizeClass(activity)`，以 `widthSizeClass` 选骨架：
   - Compact → 单栏导航；
   - Medium / Expanded → `NavigableListDetailPaneScaffold`
     （列表 pane 建议 360dp，详情占余量；Expanded 下详情正文限宽 600dp 居中留白）。
2. `WindowInfoTracker.windowLayoutInfo(activity)` 收集 `FoldingFeature`：
   - `HALF_OPENED` + `HORIZONTAL` → Tabletop：铰链 bounds 上区放诗文，
     下区放计时器与控制按钮；
   - `HALF_OPENED` + `VERTICAL` → Book：右页诗文（竖排）、左页注释/赏析；
   - `bounds` 为铰链像素区：`isSeparating` / `occlusion` 为真时，
     任何文本与可点击组件不得与之相交（换算 dp 后作为分区约束）。
3. 竖排 Layout 仅在详情 pane 宽 ≥ 560dp 时默认启用，其余横排；用户可切换。
4. 连续性：所有可恢复状态入 ViewModel / `SavedStateHandle`；
   `LazyListState`、计时会话 id、流式文本缓冲在开合/旋转/分屏后原样恢复；
   `:llm` 进程 Service 不受 Activity 重建影响，UI 重绑后继续渲染。

# 验证

- Preview 参数化四档：360×800 / 420×900（外屏）、820×1180（内竖）、940×860（内横），
  另加 Tabletop / Book 模拟。
- 截图测试（Roborazzi / Paparazzi）覆盖上述六态。
- Resizable AVD：
  ```powershell
  adb shell cmd device_state print-states
  adb shell cmd device_state state <id>
  ```
  真机以物理开合为准。

# 禁止

- 以折叠态 / `Build.MODEL` / 屏幕英寸数选布局。
- 锁定方向；跨铰链摆放按钮；320dp 下功能缺失。
- 用 `configChanges` 拦截 `screenSize` 系列配置变更。

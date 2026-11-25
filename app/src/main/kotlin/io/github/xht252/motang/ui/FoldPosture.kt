package io.github.xht252.motang.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Rect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * 折叠姿态。
 *
 * 严格按项目规则：`FoldingFeature` **只**用于悬停姿态与铰链避让，
 * 绝不用于决定单栏/双栏（那是 WindowSizeClass 的职责）。
 */
data class FoldState(
    val posture: Posture = Posture.Flat,
    /** 铰链在窗口坐标系中的位置（px）；Flat 时为 null。 */
    val hinge: Rect? = null,
) {
    val isSeparating: Boolean get() = hinge != null
}

/** android.graphics.Rect → Compose Rect（FoldingFeature.bounds 是前者）。 */
private fun android.graphics.Rect.toComposeRect(): Rect =
    Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

enum class Posture {
    /** 平铺（含普通直板机）。 */
    Flat,

    /** 水平半折：上屏显示、下屏操作。 */
    Tabletop,

    /** 垂直半折：左右两页。 */
    Book,
}

/**
 * 收集窗口布局信息并归约为姿态。
 *
 * 失效安全：任一环节异常都退回 [FoldState] 默认值（Flat），
 * 保证非折叠设备与不支持的机型完全不受影响。
 */
@Composable
fun rememberFoldState(): FoldState {
    val activity = LocalActivity.current
    if (activity == null) return FoldState()

    val state by produceState(initialValue = FoldState(), activity) {
        runCatching {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { info ->
                    val fold = info.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()
                    value = when {
                        fold == null -> FoldState()
                        fold.state == FoldingFeature.State.HALF_OPENED &&
                            fold.orientation == FoldingFeature.Orientation.HORIZONTAL ->
                            FoldState(Posture.Tabletop, fold.bounds.toComposeRect())
                        fold.state == FoldingFeature.State.HALF_OPENED &&
                            fold.orientation == FoldingFeature.Orientation.VERTICAL ->
                            FoldState(Posture.Book, fold.bounds.toComposeRect())
                        else -> FoldState()
                    }
                }
        }
    }
    return state
}

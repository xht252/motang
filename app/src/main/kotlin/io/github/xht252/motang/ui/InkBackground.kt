package io.github.xht252.motang.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import io.github.xht252.motang.ui.theme.InkTokens
import io.github.xht252.motang.ui.theme.LocalInkTokens
import kotlin.random.Random

/**
 * 宣纸底 + 多层远山。
 *
 * 设计依据（ui-ux-pro-max · E-Ink/Paper）：
 * - 纸底高对比，主视觉不用花哨渐变；
 * - 项目硬规则要求「按窗口宽高比自适应构图，窄窗口减少层数保帧率」。
 *
 * 性能：噪点用固定种子一次性生成，避免每帧重算随机数。
 */
@Composable
fun InkBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tokens = LocalInkTokens.current
    val grains = remember(tokens.ink) { generateGrains() }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = tokens.paper)

            // 窄屏（分屏/小窗）只画两层，够用且省帧。
            val layers = if (size.width < 480.dp.toPx()) 2 else 4
            drawMountains(tokens, layers)

            grains.forEach { g ->
                drawCircle(
                    color = tokens.ink.copy(alpha = g.alpha),
                    radius = g.radius,
                    center = Offset(g.x * size.width, g.y * size.height),
                )
            }
        }
        content()
    }
}

private data class Grain(val x: Float, val y: Float, val radius: Float, val alpha: Float)

private fun generateGrains(): List<Grain> {
    val seed = Random(20261125)
    // 1200 个静态噪点足以表现纸纹；再多观感提升有限却增加绘制成本。
    return List(1200) {
        Grain(
            x = seed.nextFloat(),
            y = seed.nextFloat(),
            radius = 0.4f + seed.nextFloat() * 0.9f,
            alpha = 0.02f + seed.nextFloat() * 0.05f,
        )
    }
}

private fun DrawScope.drawMountains(tokens: InkTokens, layers: Int) {
    val h = size.height

    for (layer in 0 until layers) {
        val baseY = h * (0.62f + layer * 0.08f)
        val amplitude = h * (0.10f - layer * 0.015f)
        val alpha = 0.05f + layer * 0.012f
        val path = Path()
        val steps = 6

        path.moveTo(0f, baseY)
        for (i in 0..steps) {
            val x = size.width * i / steps
            // 交替峰谷形成远山剪影；层数越大越靠下、越淡。
            val peak = if (i % 2 == 0) baseY - amplitude else baseY - amplitude * 0.35f
            val ctrlX = x - size.width / (steps * 2f)
            path.quadraticBezierTo(ctrlX, peak, x, baseY - amplitude * 0.2f)
        }
        path.lineTo(size.width, size.height)
        path.lineTo(0f, size.height)
        path.close()

        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(tokens.ink.copy(alpha = alpha), Color.Transparent),
                startY = baseY - amplitude,
                endY = size.height,
            ),
        )
    }
}

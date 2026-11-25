package io.github.xht252.motang.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xht252.motang.ui.theme.LocalInkTokens

/**
 * 竖排诗文（右起列排）。
 *
 * 用逐字纵向堆叠实现，而不是旋转文本：
 * 旋转会让标点、行高与字体度量全部歪掉，中文竖排必须逐字布局。
 *
 * 仅在详情 pane 宽 ≥ 560dp 时默认启用（见 LocalInkTokens.verticalMinWidth），
 * 其余场景横排，符合「320dp 全功能」的硬规则。
 */
@Composable
fun VerticalPoemText(
    paragraphs: List<String>,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 22.sp,
) {
    val tokens = LocalInkTokens.current

    BoxWithConstraints(modifier = modifier) {
        // 列宽 = 字宽 + 字距；按可用高度决定每列字数。
        val lineHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            (fontSize * 1.35f).toPx()
        }
        val maxCharsPerColumn = (constraints.maxHeight / lineHeightPx).toInt().coerceAtLeast(4)

        val chars = paragraphs.joinToString("").filter { !it.isWhitespace() }
        val columns = chars.chunked(maxCharsPerColumn)

        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            verticalAlignment = Alignment.Top,
        ) {
            // 右起：把列顺序反转，第一列贴右。
            columns.asReversed().forEach { column ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    column.forEach { ch ->
                        Text(
                            text = ch.toString(),
                            fontSize = fontSize,
                            lineHeight = fontSize * 1.35f,
                            color = tokens.ink,
                        )
                    }
                }
            }
        }
    }
}

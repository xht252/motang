package io.github.xht252.motang.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xht252.motang.ui.search.PoemDetail
import io.github.xht252.motang.ui.theme.LocalFontScale
import io.github.xht252.motang.ui.theme.LocalInkTokens

/**
 * 悬停姿态下的详情布局。
 *
 * - **Tabletop（水平半折）**：上屏诗文、下屏操作，便于放桌面免手持背诵。
 * - **Book（垂直半折）**：右页诗文竖排、左页注释。
 *
 * 铰链区不摆放任何文本或可点击组件（用铰链 bounds 换算成上下/左右分界）。
 */
@Composable
fun FoldedDetailPane(
    detail: PoemDetail,
    fold: FoldState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (fold.posture) {
        Posture.Tabletop -> TabletopLayout(detail, onClose, modifier)
        Posture.Book -> BookLayout(detail, onClose, modifier)
        Posture.Flat -> Unit
    }
}

@Composable
private fun TabletopLayout(detail: PoemDetail, onClose: () -> Unit, modifier: Modifier) {
    val tokens = LocalInkTokens.current
    val fontScale = LocalFontScale.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 铰链区上下各留 12dp，避免文字贴到折痕。
        val gap = 12.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // 上屏：只放诗文
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "《${detail.title}》",
                    style = MaterialTheme.typography.headlineSmall,
                    color = tokens.ink,
                )
                Text(
                    text = detail.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.pencil,
                )
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    detail.paragraphs.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = MaterialTheme.typography.bodyLarge.fontSize * fontScale,
                                lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * fontScale,
                            ),
                            color = tokens.ink,
                        )
                    }
                }
            }

            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = gap),
                color = tokens.hairline,
            )

            // 下屏：操作区（M4 会放计时器与「已背会」）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "悬停背诵模式",
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.ink,
                )
                Text(
                    "上屏照读，下屏操作（计时器与标记在 M4 接入）",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.pencil,
                )
                TextButton(onClick = onClose) { Text("结束阅读") }
            }
        }
    }
}

@Composable
private fun BookLayout(detail: PoemDetail, onClose: () -> Unit, modifier: Modifier) {
    val tokens = LocalInkTokens.current

    Row(modifier = modifier.fillMaxSize()) {
        // 左页：注释 / 译文
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("注释", style = MaterialTheme.typography.titleMedium, color = tokens.ink)
            Text(
                detail.notes ?: "M5 由端侧模型生成（AI 生成，仅供参考）",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.pencil,
            )
            TextButton(onClick = onClose) { Text("结束阅读") }
        }

        androidx.compose.material3.VerticalDivider(color = tokens.hairline)

        // 右页：竖排诗文
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "《${detail.title}》",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.ink,
            )
            VerticalPoemText(
                paragraphs = detail.paragraphs,
                fontSize = 20.sp * LocalFontScale.current,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

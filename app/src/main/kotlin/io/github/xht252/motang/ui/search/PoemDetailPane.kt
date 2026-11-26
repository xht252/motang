package io.github.xht252.motang.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xht252.motang.ui.VerticalPoemText
import io.github.xht252.motang.ui.theme.LocalFontScale
import io.github.xht252.motang.ui.theme.LocalInkTokens

/**
 * 诗文详情面板。
 *
 * 竖排策略（项目硬规则）：pane 宽 ≥ 560dp 才有资格竖排，且用户可切换。
 * 窄屏（320dp 分屏）一律横排，保证功能完整。
 */
@Composable
fun PoemDetailPane(
    detail: PoemDetail,
    onClose: () -> Unit,
    narrow: Boolean,
    allowVertical: Boolean = false,
    sessionRunning: Boolean = false,
    sessionMs: Long = 0,
    onToggleSession: () -> Unit = {},
    mastered: Boolean = false,
    onMarkMastered: () -> Unit = {},
    onFinishSession: () -> Unit = {},
    aiText: String = "",
    aiBusy: Boolean = false,
    onExplain: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInkTokens.current
    val fontScale = LocalFontScale.current
    var vertical by rememberSaveable { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val canVertical = allowVertical && maxWidth >= tokens.verticalMinWidth
        // 用户没手动切换前，宽屏默认竖排。
        val useVertical = canVertical && (vertical || !narrow)

        // 整页单一滚动容器：AI 结果与注释都在正文下方，必须能滚到。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (narrow) {
                    TextButton(onClick = onClose) { Text("← 返回") }
                }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                if (canVertical) {
                    TextButton(onClick = { vertical = !vertical }) {
                        Text(if (useVertical) "横排" else "竖排")
                    }
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "《${detail.title}》",
                    style = MaterialTheme.typography.headlineSmall,
                    color = tokens.ink,
                )
                Text(
                    text = detail.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.pencil,
                )
            }

            // 背诵会话：计时只在 SessionTracker 中，UI 只做投影。
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = onToggleSession) {
                    Text(if (sessionRunning) "暂停背诵" else "开始背诵")
                }
                TextButton(onClick = onMarkMastered, enabled = !mastered) {
                    Text(if (mastered) "已背会" else "标记已背会")
                }
                if (sessionRunning || sessionMs > 0) {
                    TextButton(onClick = onFinishSession) { Text("结束并记录") }
                }
                if (sessionRunning || sessionMs > 0) {
                    Text(
                        text = io.github.xht252.motang.ui.stats.formatDuration(sessionMs),
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.seal,
                    )
                }
            }

            if (useVertical) {
                VerticalPoemText(
                    paragraphs = detail.paragraphs,
                    fontSize = 22.sp * fontScale,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(520.dp)
                        .padding(vertical = 12.dp),
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 正文限宽 600dp 并居中：大屏上避免一行过长导致回读困难。
                    Column(
                        modifier = Modifier
                            .widthIn(max = tokens.maxReadingWidth)
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
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
            }

            // ---------------- 端侧 AI 解读（M5） ----------------
            androidx.compose.material3.OutlinedButton(
                onClick = onExplain,
                enabled = !aiBusy,
            ) {
                Text(if (aiBusy) "AI 解读中…" else "AI 解读")
            }
            if (aiText.isNotBlank()) {
                Text(
                    text = aiText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.ink,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "AI 生成，仅供参考",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.pencil,
                )
            }

            Text(
                text = detail.notes ?: "注释 / 译文：M5 由端侧模型生成（AI 生成，仅供参考）",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.pencil,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Preview(name = "详情-横排-360dp", widthDp = 360, heightDp = 800)
@Composable
private fun DetailNarrowPreview() {
    io.github.xht252.motang.ui.theme.MotangTheme {
        io.github.xht252.motang.ui.InkBackground(modifier = Modifier.fillMaxSize()) {
            PoemDetailPane(
                detail = PoemDetail(
                    poemId = "1",
                    title = "静夜思",
                    author = "李白",
                    paragraphs = listOf(
                        "床前明月光，疑是地上霜。",
                        "举头望山月，低头思故乡。",
                    ),
                    notes = null,
                ),
                onClose = {},
                narrow = true,
            )
        }
    }
}

@Preview(name = "详情-竖排-940dp", widthDp = 940, heightDp = 860)
@Composable
private fun DetailWidePreview() {
    io.github.xht252.motang.ui.theme.MotangTheme {
        io.github.xht252.motang.ui.InkBackground(modifier = Modifier.fillMaxSize()) {
            PoemDetailPane(
                detail = PoemDetail(
                    poemId = "1",
                    title = "静夜思",
                    author = "李白",
                    paragraphs = listOf(
                        "床前明月光，疑是地上霜。",
                        "举头望山月，低头思故乡。",
                    ),
                    notes = null,
                ),
                onClose = {},
                narrow = false,
                allowVertical = true,
            )
        }
    }
}

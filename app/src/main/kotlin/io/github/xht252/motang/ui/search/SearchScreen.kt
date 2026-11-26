package io.github.xht252.motang.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import io.github.xht252.motang.ui.theme.LocalFontScale
import io.github.xht252.motang.ui.theme.LocalInkTokens
import io.github.xht252.motang.ui.theme.MotangTheme

/**
 * 检索列表面板。
 *
 * 设计依据（ui-ux-pro-max）：
 * - E-Ink/Paper：纸底、高对比、阅读优先、极少装饰；
 * - Jetpack Compose 栈指引：列表必须 `LazyColumn`，不用 `Column + verticalScroll`；
 * - UX 指引：触控目标 ≥ 48dp，对比度 ≥ 4.5:1。
 */
@Composable
fun SearchPane(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenPoem: (String) -> Unit,
    modifier: Modifier = Modifier,
    voiceBusy: Boolean = false,
    onVoice: () -> Unit = {},
) {
    val tokens = LocalInkTokens.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "墨唐",
            style = MaterialTheme.typography.displaySmall,
            color = tokens.ink,
        )

        when (val index = state.index) {
            is IndexState.Preparing, is IndexState.Building -> IndexProgress(index)
            is IndexState.Failed -> Text(
                text = "索引失败：${index.reason}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            is IndexState.Ready -> SearchBody(state, onQueryChange, onSubmit, onOpenPoem)
        }
    }
}

@Composable
private fun IndexProgress(index: IndexState) {
    val message = when (index) {
        is IndexState.Building -> index.message
        else -> "正在准备索引…"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SearchBody(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenPoem: (String) -> Unit,
) {
    val tokens = LocalInkTokens.current
    val fontScale = LocalFontScale.current

    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("搜标题 / 作者 / 诗句 / 拼音") },
    )

    Button(onClick = onSubmit, enabled = !state.loading) {
        Text(if (state.loading) "检索中…" else "检索")
    }

    state.error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }

    if (state.hits.isEmpty()) {
        if (state.query.isBlank()) {
            Text(
                text = "可试：静夜思、李白、床前明月光、jys、libai",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.pencil,
            )
        }
        return
    }

    Text(
        text = "命中 ${state.total} 首" + if (state.fromBodyScan) "（含正文匹配）" else "",
        style = MaterialTheme.typography.labelMedium,
        color = tokens.pencil,
    )

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(state.hits, key = { it.poemId }) { hit ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            ) {
                Text(
                    text = "《${hit.title}》${hit.author}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = MaterialTheme.typography.titleMedium.fontSize * fontScale,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = tokens.ink,
                )
                Text(
                    text = hit.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 14.sp * fontScale,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = tokens.inkSoft,
                )
                TextButton(onClick = { onOpenPoem(hit.poemId) }) { Text("阅读") }
            }
            HorizontalDivider(color = tokens.hairline)
        }
    }
}

@Preview(name = "检索-360dp", widthDp = 360, heightDp = 800)
@Composable
private fun SearchPanePreview() {
    MotangTheme {
        io.github.xht252.motang.ui.InkBackground(modifier = Modifier.fillMaxSize()) {
            SearchPane(
                state = SearchUiState(
                    index = IndexState.Ready(poems = 853349, elapsedMs = 6400),
                    query = "明月",
                    hits = listOf(
                        io.github.xht252.motang.data.SearchHitUi("1", "静夜思", "李白", "床前明月光，疑是地上霜。"),
                        io.github.xht252.motang.data.SearchHitUi("2", "望月怀远", "张九龄", "海上生明月，天涯共此时。"),
                    ),
                    total = 920,
                ),
                onQueryChange = {},
                onSubmit = {},
                onOpenPoem = {},
            )
        }
    }
}

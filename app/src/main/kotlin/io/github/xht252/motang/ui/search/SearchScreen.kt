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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 检索页（M1）。
 *
 * 布局遵循自适应硬规则：不写死宽度，正文区限宽 600dp，320dp 下仍可用。
 * M2 会替换为 NavigationSuiteScaffold + NavigableListDetailPaneScaffold。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenPoem: (String) -> Unit,
    onClosePoem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "墨唐",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )

        when (val index = state.index) {
            is IndexState.Preparing, is IndexState.Building -> IndexProgress(index)
            is IndexState.Failed -> Text(
                text = "索引失败：${index.reason}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            is IndexState.Ready -> {
                val detail = state.selected
                if (detail == null) {
                    SearchBody(state, onQueryChange, onSubmit, onOpenPoem)
                } else {
                    PoemDetailScreen(detail, onClosePoem)
                }
            }
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
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
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
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("搜诗句 / 标题 / 作者 / 拼音（jys、libai）") },
    )

    Button(onClick = onSubmit, enabled = !state.loading) {
        Text(if (state.loading) "检索中…" else "检索")
    }

    state.error?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }

    if (state.hits.isEmpty()) {
        if (state.query.isBlank()) {
            Text(
                text = "共 57444 首唐诗已就绪。可试：明月、李白、静夜思、jys、libai",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        return
    }

    Text(
        text = "命中 ${state.total} 首",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary,
    )

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        items(state.hits, key = { it.poemId }) { hit ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(
                    text = "《${hit.title}》${hit.author}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hit.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onOpenPoem(hit.poemId) }) { Text("查看") }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun PoemDetailScreen(detail: PoemDetail, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← 返回") }
        }
        Text(
            text = "《${detail.title}》",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = detail.author,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(detail.paragraphs) { line ->
                Text(text = line, style = MaterialTheme.typography.bodyLarge)
            }
            item {
                Text(
                    text = detail.notes ?: "注释/译文：M5 由端侧模型生成（AI 生成，仅供参考）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Preview(name = "检索-360dp", widthDp = 360, heightDp = 800)
@Composable
private fun SearchScreenPreview() {
    io.github.xht252.motang.ui.theme.MotangTheme {
        SearchScreen(
            state = SearchUiState(
                index = IndexState.Ready(poems = 57444, elapsedMs = 4200),
                query = "明月",
                hits = listOf(
                    SearchHitPreview("1", "靜夜思", "李白", "床前看月光，疑是地上霜。"),
                    SearchHitPreview("2", "明月夜留別", "李冶", "離人無語月無聲，明月有光人有情。"),
                ),
                total = 942,
            ),
            onQueryChange = {},
            onSubmit = {},
            onOpenPoem = {},
            onClosePoem = {},
        )
    }
}

private fun SearchHitPreview(id: String, title: String, author: String, snippet: String) =
    io.github.xht252.motang.data.SearchHitUi(id, title, author, snippet)

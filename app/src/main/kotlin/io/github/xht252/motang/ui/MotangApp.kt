package io.github.xht252.motang.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xht252.motang.MotangApplication
import io.github.xht252.motang.ui.search.SearchScreen
import io.github.xht252.motang.ui.search.SearchViewModel
import io.github.xht252.motang.ui.theme.MotangTheme

/**
 * 应用根界面（M1）。
 *
 * M2 将替换为 NavigationSuiteScaffold + NavigableListDetailPaneScaffold：
 * Compact 单栏、Medium/Expanded 双栏。当前保持单栏，320dp 下可用。
 */
@Composable
fun MotangApp(initialQuery: String? = null) {
    val application = LocalContext.current.applicationContext as? MotangApplication
    if (application == null) {
        // Preview / 非 Application 环境：给占位，避免预览崩溃。
        SearchScreen(
            state = io.github.xht252.motang.ui.search.SearchUiState(
                index = io.github.xht252.motang.ui.search.IndexState.Failed("预览模式：无 native 库"),
            ),
            onQueryChange = {},
            onSubmit = {},
            onOpenPoem = {},
            onClosePoem = {},
        )
        return
    }

    val viewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.factory(application.repository),
    )
    val state by viewModel.state.collectAsState()

    // 自动化验收入口：索引就绪后自动执行 intent 指定的查询。
    // 用法：adb shell am start -n io.github.xht252.motang/.MainActivity -e query 明月
    var autoRan by remember { mutableStateOf(false) }
    LaunchedEffect(state.index, initialQuery) {
        if (!autoRan && !initialQuery.isNullOrBlank() && state.index is io.github.xht252.motang.ui.search.IndexState.Ready) {
            autoRan = true
            viewModel.onQueryChange(initialQuery)
            viewModel.submitSearch()
        }
    }

    Scaffold { innerPadding ->
        SearchScreen(
            state = state,
            onQueryChange = viewModel::onQueryChange,
            onSubmit = viewModel::submitSearch,
            onOpenPoem = viewModel::openPoem,
            onClosePoem = viewModel::closePoem,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(
    name = "360dp 外屏",
    widthDp = 360,
    heightDp = 800,
)
@androidx.compose.ui.tooling.preview.Preview(
    name = "820dp 内屏竖持",
    widthDp = 820,
    heightDp = 1180,
)
@Composable
private fun MotangAppPreview() {
    MotangTheme { MotangApp() }
}

package io.github.xht252.motang.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.xht252.motang.MotangApplication
import io.github.xht252.motang.MotangApplication.NativeState
import io.github.xht252.motang.ui.theme.MotangTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * M0 骨架首页：只展示 native 探针结果。
 *
 * M2 将替换为 NavigationSuiteScaffold + NavigableListDetailPaneScaffold。
 * 当前刻意保持无固定栏位的纵向布局，以便在 360 / 820 / 940dp 三个宽度下
 * 都不溢出不崩溃（M0 冒烟验收项 B4）。
 */
@Composable
fun MotangApp() {
    val application = LocalContext.current.applicationContext as? MotangApplication
    // 无条件调用 collectAsState：Compose 对条件式 composable 调用的位置记忆很敏感，
    // 用 remember 承载可空 StateFlow，避免分支改变调用点。
    val nativeState = remember(application) {
        application?.nativeState ?: MutableStateFlow<NativeState>(NativeState.Loading)
    }
    val state by nativeState.collectAsState()

    val status = if (application == null) {
        // Preview / 预览环境下没有 MotangApplication，给占位文案。
        "预览模式：无 native 库"
    } else {
        when (val current = state) {
            NativeState.Loading -> "正在加载 native 库…"
            is NativeState.Ready -> "${current.ping}\n检索核心 ${current.coreVersion}"
            is NativeState.Failed -> "native 不可用：${current.reason}"
        }
    }

    Scaffold { innerPadding ->
        // Scaffold 的 innerPadding 已含系统栏安全区（contentWindowInsets 默认
        // safeDrawing），不再叠加 safeDrawingPadding()，否则边距翻倍。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "墨唐",
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = "M0 脚手架 · 检索与释义尚未接入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Preview(name = "360dp 外屏", widthDp = 360, heightDp = 800)
@Preview(name = "820dp 内屏竖持", widthDp = 820, heightDp = 1180)
@Preview(name = "940dp 内屏横持", widthDp = 940, heightDp = 860)
@Composable
private fun MotangAppPreview() {
    MotangTheme { MotangApp() }
}

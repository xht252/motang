package io.github.xht252.motang.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
    import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xht252.motang.data.FontScale
import io.github.xht252.motang.ui.search.PoemDetailPane
import io.github.xht252.motang.ui.search.SearchPane
import io.github.xht252.motang.ui.settings.SettingsPane
import io.github.xht252.motang.ui.search.SearchUiState
import io.github.xht252.motang.ui.theme.LocalInkTokens

/** 顶层目的地。 */
enum class Destination(val label: String) {
    Search("检索"),
    CheckIn("打卡"),
    Stats("统计"),
    Chat("问诗"),
        Mastered("已背会"),
    Settings("设置"),
}

/** 宽度分级（只依赖窗口尺寸，不依赖折叠状态/机型）。 */
enum class WidthClass { Compact, Medium, Expanded }

fun widthClassFor(widthDp: Int): WidthClass = when {
    widthDp >= 840 -> WidthClass.Expanded
    widthDp >= 600 -> WidthClass.Medium
    else -> WidthClass.Compact
}

/**
 * 应用骨架。
 *
 * - 顶层导航用 `NavigationSuiteScaffold`：Compact 底部栏、Medium+ 侧边 Rail。
 * - 检索页在 Medium/Expanded 下用「列表 360dp + 详情」双栏；
 *   详情正文限宽 600dp，避免大屏一行过长。
 *
 * 说明：列表/详情双栏此处用宽度驱动的 Row 实现。选择理由与
 * `NavigableListDetailPaneScaffold` 的取舍见 DECISIONS 第 23 条。
 */
@Composable
fun MotangScaffold(
    widthDp: Int,
    state: SearchUiState,
    fontScale: FontScale,
    onFontScaleChange: (FontScale) -> Unit,
    reminder: io.github.xht252.motang.data.ReminderSettings,
    onReminderChange: (io.github.xht252.motang.data.ReminderSettings) -> Unit,
    streak: Int,
    daily: io.github.xht252.motang.ui.checkin.DailyState,
    dailyActions: io.github.xht252.motang.ui.checkin.DailyActions,
    onExportPdf: () -> Unit,
    mastered: List<io.github.xht252.motang.data.StudySession>,
    stats: io.github.xht252.motang.ui.stats.StatsUi,
    session: io.github.xht252.motang.domain.SessionState,
    onToggleSession: () -> Unit,
    onMarkMastered: () -> Unit,
    onFinishSession: () -> Unit,
    aiText: String,
    aiBusy: Boolean,
    onExplain: () -> Unit,
    llmClient: io.github.xht252.motang.llm.LlmClient,
    modelPath: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenPoem: (String) -> Unit,
    onClosePoem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.Search) }
    val widthClass = widthClassFor(widthDp)

    NavigationSuiteScaffold(
        modifier = modifier.fillMaxSize(),
        navigationSuiteItems = {
            Destination.entries.forEach { entry ->
                item(
                    selected = destination == entry,
                    onClick = { destination = entry },
                    icon = {
                        Icon(
                            imageVector = when (entry) {
                                Destination.Search -> Icons.Filled.Search
                                Destination.CheckIn -> Icons.Filled.DateRange
                                Destination.Stats -> Icons.Filled.List
                                Destination.Chat -> Icons.Filled.Send
                                    Destination.Mastered -> Icons.Filled.Star
                                Destination.Settings -> Icons.Filled.Settings
                            },
                            contentDescription = entry.label,
                        )
                    },
                    label = { Text(entry.label) },
                )
            }
        },
    ) {
        InkBackground(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (destination) {
                    Destination.Search -> SearchDestination(
                        widthClass = widthClass,
                        state = state,
                        onQueryChange = onQueryChange,
                        onSubmit = onSubmit,
                        onOpenPoem = onOpenPoem,
                        onClosePoem = onClosePoem,
                        session = session,
                        onToggleSession = onToggleSession,
                        onMarkMastered = onMarkMastered,
                        onFinishSession = onFinishSession,
                        aiText = aiText,
                        aiBusy = aiBusy,
                        onExplain = onExplain,
                    )
                    Destination.CheckIn -> io.github.xht252.motang.ui.checkin.CheckInPane(
                        state = daily,
                        actions = dailyActions,
                        onExportPdf = onExportPdf,
                        streak = streak,
                    )
                    Destination.Stats -> io.github.xht252.motang.ui.stats.StatsPane(
                        week = stats.week,
                        totalMs = stats.totalMs,
                        activeDays = stats.activeDays,
                        newPoems = stats.newPoems,
                        onExportPdf = onExportPdf,
                    )
                    Destination.Chat -> io.github.xht252.motang.ui.chat.ChatScreen(
                        llmClient = llmClient,
                        modelPath = modelPath,
                    )
                    Destination.Mastered -> io.github.xht252.motang.ui.checkin.MasteredPane(
                        sessions = mastered,
                    )
                    Destination.Settings -> SettingsPane(
                        fontScale = fontScale,
                        onFontScaleChange = onFontScaleChange,
                        reminder = reminder,
                        onReminderChange = onReminderChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchDestination(
    widthClass: WidthClass,
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenPoem: (String) -> Unit,
    onClosePoem: () -> Unit,
    session: io.github.xht252.motang.domain.SessionState,
    onToggleSession: () -> Unit,
    onMarkMastered: () -> Unit,
    onFinishSession: () -> Unit,
    aiText: String,
    aiBusy: Boolean,
    onExplain: () -> Unit,
) {
    val fold = rememberFoldState()
    val detail = state.selected

    // 悬停姿态优先：Tabletop/Book 只在阅读（详情）时有意义。
    if (detail != null && fold.posture != Posture.Flat) {
        FoldedDetailPane(detail = detail, fold = fold, onClose = onClosePoem)
        return
    }

    if (widthClass == WidthClass.Compact) {
        // 单栏：列表 ↔ 详情整页切换
        if (detail == null) {
            SearchPane(state, onQueryChange, onSubmit, onOpenPoem)
        } else {
            PoemDetailPane(
                detail = detail,
                onClose = onClosePoem,
                narrow = true,
                sessionRunning = session.running,
                sessionMs = session.activeMs,
                onToggleSession = onToggleSession,
                onMarkMastered = onMarkMastered,
                onFinishSession = onFinishSession,
                aiText = aiText,
                aiBusy = aiBusy,
                onExplain = onExplain,
            )
        }
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // 列表固定 360dp：低于该值会挤压标题；高于该值浪费横向空间。
        SearchPane(
            state = state,
            onQueryChange = onQueryChange,
            onSubmit = onSubmit,
            onOpenPoem = onOpenPoem,
            modifier = Modifier
                .widthIn(min = 320.dp, max = 380.dp)
                .fillMaxHeight(),
        )
        VerticalDivider(color = LocalInkTokens.current.hairline)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            if (detail == null) {
                EmptyDetailHint()
            } else {
                PoemDetailPane(
                    detail = detail,
                    onClose = onClosePoem,
                    narrow = false,
                    // 双栏下详情空间充足，允许竖排。
                    allowVertical = true,
                    sessionRunning = session.running,
                    sessionMs = session.activeMs,
                    onToggleSession = onToggleSession,
                    onMarkMastered = onMarkMastered,
                    onFinishSession = onFinishSession,
                    aiText = aiText,
                    aiBusy = aiBusy,
                    onExplain = onExplain,
                )
            }
        }
    }
}

@Composable
private fun EmptyDetailHint() {
    Column(
        modifier = Modifier.padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("从左侧选择一首诗", style = MaterialTheme.typography.titleMedium)
        Text(
            "双栏布局：宽屏下左侧检索、右侧阅读",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.fillMaxWidth(0.4f), color = LocalInkTokens.current.hairline)
    }
}


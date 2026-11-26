package io.github.xht252.motang.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xht252.motang.MotangApplication
import io.github.xht252.motang.data.FontScale
import io.github.xht252.motang.ui.search.IndexState
import io.github.xht252.motang.ui.search.SearchUiState
import io.github.xht252.motang.ui.search.SearchViewModel
import io.github.xht252.motang.ui.theme.MotangTheme
import kotlinx.coroutines.launch

/**
 * 应用根界面（M2）。
 *
 * 布局决策唯一依据 = 当前窗口宽度（[LocalConfiguration.screenWidthDp] → [WidthClass]），
 * 不看折叠状态、不看机型。
 */
@Composable
fun MotangApp(initialQuery: String? = null) {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val application = LocalContext.current.applicationContext as? MotangApplication

    if (application == null) {
        MotangTheme {
            InkBackground(modifier = Modifier.fillMaxSize()) {
                PlaceholderPane("墨唐", "预览模式：无 native 库")
            }
        }
        return
    }

    val viewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.factory(application.repository),
    )
    val state by viewModel.state.collectAsState()

    val fontScale by application.settings.fontScale.collectAsState(initial = FontScale.Normal)
    val reminder by application.settings.reminder.collectAsState(
        initial = io.github.xht252.motang.data.ReminderSettings(),
    )
    val streak by application.settings.streak.collectAsState(initial = 0)
    val lastCheckIn by application.settings.lastCheckIn.collectAsState(initial = null)
    val today = java.time.LocalDate.now().toString()


    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ---- M4：会话计时与统计 ----
    val tracker = remember { io.github.xht252.motang.domain.SessionTracker() }
    val session by tracker.state.collectAsState()
    // 每秒刷新一次，仅用于显示；真实时长由 tracker 内部按 wall clock 计算。
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(session.running) {
        while (session.running) {
            kotlinx.coroutines.delay(1000)
            tick++
        }
    }
    val liveMs = remember(session, tick) { tracker.snapshotMs() }

    val study = remember(application) { io.github.xht252.motang.data.StudyRepository(context) }
    val exportPdf = io.github.xht252.motang.ui.checkin.rememberPdfExporter(study, streak)

    // ---- M5：端侧 LLM（独立进程 :llm） ----
    val llmClient = remember(application) { io.github.xht252.motang.llm.LlmClient(context) }
    androidx.compose.runtime.DisposableEffect(llmClient) {
        llmClient.bind()
        onDispose { llmClient.unbind() }
    }
    var aiText by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    val explanations = remember(application) {
        io.github.xht252.motang.data.ExplanationRepository(context)
    }
    val modelPath = remember(context) {
        java.io.File(context.getExternalFilesDir("models"), "MiniCPM5-2B-Q4_K_M.gguf").absolutePath
    }
    val (daily, dailyActions) = io.github.xht252.motang.ui.checkin.rememberDailyStudy(
        search = application.repository,
        study = study,
        onCheckIn = { application.settings.checkInToday() },
        explainLines = { title, author, poemLines ->
            val key = "daily|$title|$author"
            val cached = explanations.get(key, PROMPT_VER, MODEL_ID)
            if (cached != null) {
                parseNumbered(cached, poemLines.size)
            } else {
                llmClient.loadModel(modelPath, 4, 2048)
                val text = llmClient.generate(buildLinesPrompt(title, author, poemLines), 512, 0.3f)
                if (text.isNotBlank()) explanations.put(key, PROMPT_VER, MODEL_ID, text)
                parseNumbered(text, poemLines.size)
            }
        },
    )


    var statsVersion by remember { mutableIntStateOf(0) }
    val masteredList = androidx.compose.runtime.produceState(
        emptyList<io.github.xht252.motang.data.StudySession>(),
        statsVersion,
    ) { value = study.masteredSessionsOnce() }.value
    val stats = androidx.compose.runtime.produceState(
        initialValue = io.github.xht252.motang.ui.stats.StatsUi(),
        statsVersion,
    ) {
        val week = study.last7Days()
        value = io.github.xht252.motang.ui.stats.StatsUi(
            week = week,
            totalMs = week.sumOf { it.activeMs },
            activeDays = week.count { it.activeMs > 0 },
            newPoems = week.sumOf { it.poems },
            month = study.monthDays(),
            authors = study.topAuthorsThisMonth(),
            mastered = study.masteredCount(),
        )
    }.value

    // 启动时结算上次未完成的会话（进程被杀/崩溃），实现「可恢复或安全结算」。
    LaunchedEffect(Unit) {
        application.settings.loadOngoing()?.let { left ->
            study.record(
                poemId = left.poemId,
                title = left.title,
                author = left.author,
                startedAt = left.startedAt,
                endedAt = System.currentTimeMillis(),
                activeMs = left.activeMs,
                mastered = false,
            )
            application.settings.clearOngoing()
            statsVersion++
        }
    }

    val onToggleSession: () -> Unit = {
        val current = tracker.state.value
        if (current.poemId == null) {
            state.selected?.let { tracker.start(it.poemId, it.title, it.author) }
        } else if (current.running) {
            // 暂停时不落库；再次点击继续。长按/离开详情时才结算（见 onClosePoem）。
            tracker.pause()
        } else {
            tracker.resume()
        }
        // 持久化未结算会话：进程被杀后下次启动可安全结算。
        tracker.state.value.poemId?.let { id ->
            val s = tracker.state.value
            scope.launch {
                application.settings.saveOngoing(
                    io.github.xht252.motang.data.OngoingSession(
                        poemId = id,
                        title = s.title,
                        author = s.author,
                        activeMs = tracker.snapshotMs(),
                        startedAt = System.currentTimeMillis() - tracker.snapshotMs(),
                    ),
                )
            }
        }
    }

    val finishSession: () -> Unit = {
        tracker.finish()?.let { done ->
            scope.launch {
                study.record(
                    poemId = done.poemId,
                    title = done.title,
                    author = done.author,
                    startedAt = done.startedAt,
                    endedAt = done.endedAt,
                    activeMs = done.activeMs,
                    mastered = false,
                    day = done.day,
                )
                // 有会话即视为当日打卡。
                application.settings.checkInToday()
                application.settings.clearOngoing()
                statsVersion++
            }
        }
    }

    // 提醒设置变化即重排闹钟（精确优先，失败降级 WorkManager）。
    LaunchedEffect(reminder) {
        io.github.xht252.motang.reminder.ReminderScheduler.apply(
            context,
            reminder.enabled,
            reminder.hour,
            reminder.minute,
        )
    }

    AutoQuery(initialQuery, state, viewModel)

    MotangTheme(fontScale = fontScale.multiplier) {
        MotangScaffold(
            widthDp = widthDp,
            state = state,
            fontScale = fontScale,
            onFontScaleChange = { scale ->
                scope.launch { application.settings.setFontScale(scale) }
            },
            reminder = reminder,
            onReminderChange = { next ->
                scope.launch {
                    application.settings.setReminder(next)
                    // 开启时顺带申请通知权限（13+ 必需）。
                    if (next.enabled) ensureNotificationPermission(context)
                }
            },
            streak = streak,
            daily = daily,
            dailyActions = dailyActions,
            onExportPdf = exportPdf,
            mastered = masteredList,
            stats = stats,
            session = session.copy(activeMs = liveMs),
            onToggleSession = onToggleSession,
            onMarkMastered = {
                state.selected?.let { sel ->
                    scope.launch {
                        study.markMastered(sel.poemId)
                        statsVersion++
                    }
                }
            },
            onFinishSession = finishSession,
            aiText = aiText,
            aiBusy = aiBusy,

            onExplain = {
                val sel = state.selected
                if (sel != null && !aiBusy) {
                    scope.launch {
                        aiBusy = true
                        aiText = ""
                        // 1) 先查缓存：命中即 0 等待返回。
                        val cached = explanations.get(sel.poemId, PROMPT_VER, MODEL_ID)
                        if (cached != null) {
                            aiText = cached
                            aiBusy = false
                            return@launch
                        }
                        val error = runCatching {
                            llmClient.loadModel(modelPath, 4, 2048)
                        }.getOrElse { it.message ?: "加载失败" }
                        aiText = if (error.isNotEmpty()) {
                            "模型加载失败：$error"
                        } else {
                            val generated = runCatching {
                                llmClient.generateStream(
                                    prompt = buildPoemPrompt(sel.title, sel.author, sel.paragraphs),
                                    maxTokens = 512,
                                    temperature = 0.3f,
                                ) { delta ->
                                    // 流式追加：首个 token 即可见，避免 25s 空白。
                                    aiText += delta
                                }
                            }.getOrElse { "生成失败：${it.message}" }
                            // 2) 成功后写缓存，二次进入零等待。
                            if (generated.isNotBlank() && !generated.startsWith("生成失败")) {
                                explanations.put(sel.poemId, PROMPT_VER, MODEL_ID, generated)
                            }
                            generated
                        }
                        aiBusy = false
                    }
                }
            },
            onQueryChange = viewModel::onQueryChange,
            onSubmit = viewModel::submitSearch,
            onOpenPoem = viewModel::openPoem,
            onClosePoem = {
                finishSession()
                viewModel.closePoem()
            },
        )
    }
}

/**
 * 13+ 需要 POST_NOTIFICATIONS 才能显示提醒。
 *
 * 这里只做「检查 + 引导」，不强制申请：权限申请需要 Activity 上下文，
 * 实际弹窗由 MainActivity 在启动时统一处理，避免在 Composable 里持有 Activity。
 */
private fun ensureNotificationPermission(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.POST_NOTIFICATIONS,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) {
        android.util.Log.i("MotangReminder", "通知权限未授予，提醒可能不显示")
    }
}

/** 顶部/占位用的小块（也用于预览兜底）。 */
@Composable
internal fun PlaceholderPane(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AutoQuery(
    initialQuery: String?,
    state: SearchUiState,
    viewModel: SearchViewModel,
) {
    var autoRan by remember { mutableStateOf(false) }
    LaunchedEffect(state.index, initialQuery) {
        if (!autoRan && !initialQuery.isNullOrBlank() && state.index is IndexState.Ready) {
            autoRan = true
            viewModel.onQueryChange(initialQuery)
            viewModel.submitSearch()
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "360dp", widthDp = 360, heightDp = 800)
@androidx.compose.ui.tooling.preview.Preview(name = "820dp", widthDp = 820, heightDp = 1180)
@androidx.compose.ui.tooling.preview.Preview(name = "940dp", widthDp = 940, heightDp = 860)
@Composable
private fun MotangAppPreview() {
    MotangTheme {
        MotangScaffold(
            widthDp = 940,
            state = SearchUiState(index = IndexState.Ready(853349, 6400)),
            fontScale = FontScale.Normal,
            onFontScaleChange = {},
            reminder = io.github.xht252.motang.data.ReminderSettings(),
            onReminderChange = {},
            streak = 0,
            daily = io.github.xht252.motang.ui.checkin.DailyState(),
            dailyActions = io.github.xht252.motang.ui.checkin.DailyActions({}, {}, {}, {}),
            onExportPdf = {},
            mastered = emptyList(),
            stats = io.github.xht252.motang.ui.stats.StatsUi(),
            session = io.github.xht252.motang.domain.SessionState(),
            onToggleSession = {},
            onMarkMastered = {},
            onFinishSession = {},
            aiText = "",
            aiBusy = false,
            onExplain = {},
            onQueryChange = {},
            onSubmit = {},
            onOpenPoem = {},
            onClosePoem = {},
        )
    }
}
/** 构造逐句解析 prompt（v1）。 */
private fun buildPoemPrompt(title: String, author: String, lines: List<String>): String =
    buildString {
        append("你是古典文学助教。仅依据下列诗文作答，不要编造典故出处，不确定处写「存疑」。")
        append('\n')
        append("《").append(title).append("》").append(author).append('\n')
        lines.forEachIndexed { index, line ->
            append(index + 1).append(". ").append(line).append('\n')
        }
        append("请逐句给出字词注释与白话译文，每句一行，简洁。")
    }
/** prompt 版本：改 Prompt 结构时自增，缓存自动失效。 */
private const val PROMPT_VER = 3

/** 模型标识：换模型时修改，缓存自动失效。 */
private const val MODEL_ID = "minicpm5-2b-q4km"
/** 逐句解析 prompt：要求按编号逐行输出，不要思考过程。 */
private fun buildLinesPrompt(title: String, author: String, lines: List<String>): String =
    buildString {
        append("《").append(title).append("》").append(author).append('\n')
        lines.forEachIndexed { index, line ->
            append(index + 1).append(". ").append(line).append('\n')
        }
        append("请逐句解释：每句先给字词注释，再给白话译文，合并为一句，不超过 40 字。")
        append("按编号逐行输出，不要输出思考过程。")
    }

/** 把「1. xxx」形式的输出解析成与行数等长的列表。 */
private fun parseNumbered(text: String, count: Int): List<String> {
    val out = MutableList(count) { "" }
    val pattern = Regex("""^(\d+)[.、)]\s*(.+)$""")
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        val match = pattern.find(line) ?: return@forEach
        val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return@forEach
        if (index in 0 until count) {
            out[index] = match.groupValues[2].trim()
        }
    }
    return out
}
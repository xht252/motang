package io.github.xht252.motang.ui.checkin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.xht252.motang.data.SearchRepository
import io.github.xht252.motang.data.StudyRepository
import io.github.xht252.motang.ui.search.PoemDetail
import kotlinx.coroutines.launch

/** 每日学习阶段。 */
enum class DailyPhase {
    /** 未开始：显示「开始今日背诵」。 */
    Idle,

    /** 背诵中：只显示诗文与出处。 */
    Reciting,

    /** 背诵完成：逐句解析。 */
    Analyzing,

    /** 今日已学完。 */
    Done,
}

/** 逐句解析条目。M5 前 note 为空，由 UI 显示占位。 */
data class LineAnalysis(val line: String, val note: String? = null)

data class DailyState(
    val phase: DailyPhase = DailyPhase.Idle,
    val poem: PoemDetail? = null,
    val lines: List<LineAnalysis> = emptyList(),
    val todayPoems: Int = 0,
    val todayMs: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

class DailyActions(
    private val draw: () -> Unit,
    private val reciteDone: () -> Unit,
    private val learnDone: (Boolean) -> Unit,
    private val next: () -> Unit,
) {
    fun draw() = draw.invoke()
    fun finishReciting() = reciteDone.invoke()
    fun finishLearning(mastered: Boolean) = learnDone.invoke(mastered)
    fun continueLearning() = next.invoke()
}

/**
 * 每日打卡流程（对齐背单词类应用）。
 *
 * Idle → 抽诗 → Reciting →「我背完了」→ Analyzing（逐句解析）
 *      →「学习完毕」→ Done（今日打卡完成）
 * Done 可「继续学习」再来一首。
 *
 * 口径：只有走完 Analyzing 才打卡；中途退出不计。
 */
@Composable
fun rememberDailyStudy(
    search: SearchRepository,
    study: StudyRepository,
    onCheckIn: suspend () -> Unit,
): Pair<DailyState, DailyActions> {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(DailyState()) }
    var version by remember { mutableIntStateOf(0) }

    LaunchedEffect(version) {
        state = state.copy(
            todayPoems = study.todayPoems(),
            todayMs = study.todayMs(),
        )
    }

    fun pick() {
        scope.launch {
            state = state.copy(loading = true, error = null, phase = DailyPhase.Idle)
            runCatching { search.randomPoem() }
                .onSuccess { poem ->
                    state = if (poem == null) {
                        state.copy(loading = false, error = "抽诗失败：语料为空")
                    } else {
                        state.copy(
                            loading = false,
                            phase = DailyPhase.Reciting,
                            poem = PoemDetail(
                                poemId = poem.id,
                                title = poem.title,
                                author = poem.author,
                                paragraphs = poem.paragraphs,
                                notes = poem.notes,
                            ),
                            lines = emptyList(),
                        )
                    }
                }
                .onFailure { e ->
                    state = state.copy(loading = false, error = e.message ?: "抽诗失败")
                }
        }
    }

    val actions = DailyActions(
        draw = { pick() },
        reciteDone = {
            val poem = state.poem
            if (poem != null) {
                state = state.copy(
                    phase = DailyPhase.Analyzing,
                    lines = poem.paragraphs.map { LineAnalysis(it) },
                )
            }
        },
        learnDone = { mastered ->
            val poem = state.poem
            if (poem != null) {
                scope.launch {
                    val now = System.currentTimeMillis()
                    study.record(
                        poemId = poem.poemId,
                        title = poem.title,
                        author = poem.author,
                        startedAt = now,
                        endedAt = now,
                        activeMs = 0,
                        mastered = mastered,
                        )
                    if (mastered) study.markMastered(poem.poemId)
                    onCheckIn()
                    version++
                    state = state.copy(phase = DailyPhase.Done)
                }
            }
        },
        next = {
            state = state.copy(phase = DailyPhase.Idle, poem = null, lines = emptyList())
            pick()
        },
    )

    return state to actions
}

package io.github.xht252.motang.ui.checkin

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xht252.motang.data.StudyRepository
import io.github.xht252.motang.ui.search.PoemDetail
import io.github.xht252.motang.ui.stats.formatDuration
import io.github.xht252.motang.ui.theme.LocalInkTokens
import kotlinx.coroutines.runBlocking

/**
 * 每日打卡（背单词式流程）。
 *
 * 抽诗 → 背诵（仅诗文+出处）→ 完成 → 逐句解析 → 学习完毕 → 打卡完成。
 */
@Composable
fun CheckInPane(
    state: DailyState,
    actions: DailyActions,
    onExportPdf: () -> Unit,
    streak: Int,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInkTokens.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("每日打卡", style = MaterialTheme.typography.displaySmall, color = tokens.ink)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Text("连续 $streak 天", style = MaterialTheme.typography.labelMedium, color = tokens.seal)
        }

        Text(
            text = "今日 ${state.todayPoems} 首 · ${formatDuration(state.todayMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.pencil,
        )

        HorizontalDivider(color = tokens.hairline)

        when (state.phase) {
            DailyPhase.Idle -> IdleBlock(state, actions)
            DailyPhase.Reciting -> RecitingBlock(state, actions)
            DailyPhase.Analyzing -> AnalyzingBlock(state, actions)
            DailyPhase.Done -> DoneBlock(state, actions, onExportPdf)
        }
    }
}

@Composable
private fun IdleBlock(state: DailyState, actions: DailyActions) {
    val tokens = LocalInkTokens.current
    Text(
        "今天还没开始。点下面的按钮，随机抽一首唐诗。",
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.ink,
    )
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = actions::draw, enabled = !state.loading) {
        Text(if (state.loading) "抽诗中…" else "开始今日背诵")
    }
}

/** 背诵阶段：**只**显示诗文与出处，避免分心（背单词应用的核心设计）。 */
@Composable
private fun RecitingBlock(state: DailyState, actions: DailyActions) {
    val tokens = LocalInkTokens.current
    val poem = state.poem ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = tokens.maxReadingWidth)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(
                text = "《${poem.title}》",
                style = MaterialTheme.typography.headlineSmall,
                color = tokens.ink,
            )
            // 出处：作者 + 朝代（数据源含朝代）
            Text(
                text = "${poem.author} · 唐",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.pencil,
            )
        }

        poem.paragraphs.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.ink,
            )
        }

        Button(onClick = actions::finishReciting, modifier = Modifier.fillMaxWidth()) {
            Text("我背完了，看解析")
        }
    }
}

/** 解析阶段：逐句解析。M5 前显示占位，并明确标注未接入 AI。 */
@Composable
private fun AnalyzingBlock(state: DailyState, actions: DailyActions) {
    val tokens = LocalInkTokens.current
    val poem = state.poem ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = tokens.maxReadingWidth)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "《${poem.title}》逐句解析",
            style = MaterialTheme.typography.titleMedium,
            color = tokens.ink,
        )

        state.lines.forEach { line ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.line, style = MaterialTheme.typography.bodyLarge, color = tokens.ink)
                Text(
                    text = line.note ?: "（解析待 M5 端侧模型生成）",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.pencil,
                )
            }
            HorizontalDivider(color = tokens.hairline)
        }

        Text(
            "解析由 AI 生成时，仅供参考。",
            style = MaterialTheme.typography.labelMedium,
            color = tokens.pencil,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { actions.finishLearning(true) }) { Text("学习完毕 · 已背会") }
            OutlinedButton(onClick = { actions.finishLearning(false) }) { Text("学习完毕") }
        }
    }
}

@Composable
private fun DoneBlock(state: DailyState, actions: DailyActions, onExportPdf: () -> Unit) {
    val tokens = LocalInkTokens.current
    Text("今日打卡完成", style = MaterialTheme.typography.headlineSmall, color = tokens.seal)
    Text(
        "今日 ${state.todayPoems} 首 · ${formatDuration(state.todayMs)}",
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.ink,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = actions::continueLearning) { Text("继续学习") }
        OutlinedButton(onClick = onExportPdf) { Text("导出 PDF") }
    }
}

// ---------------------------------------------------------------------------
// PDF 导出
// ---------------------------------------------------------------------------

/**
 * 生成学习情况 PDF。
 *
 * 用平台自带的 `android.graphics.pdf.PdfDocument`，不引入第三方库。
 * 通过 SAF 让用户选择保存位置（自签直装下无需存储权限）。
 */
@Composable
fun rememberPdfExporter(study: StudyRepository, streak: Int): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = io.github.xht252.motang.ui.export.StudyPdf.build(context, study, streak)
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        }
    }
    return { launcher.launch("motang-study-${java.time.LocalDate.now()}.pdf") }
}

package io.github.xht252.motang.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xht252.motang.data.DayStat
import io.github.xht252.motang.ui.theme.LocalInkTokens

/** 统计页所需的整页数据。 */
data class StatsUi(
    val week: List<DayStat> = emptyList(),
    val totalMs: Long = 0,
    val activeDays: Int = 0,
    val newPoems: Int = 0,
    /** 本月每天的聚合，用于热力图。 */
    val month: List<DayStat> = emptyList(),
    /** 本月作者分布（Top 10）。 */
    val authors: List<io.github.xht252.motang.data.AuthorStat> = emptyList(),
    val mastered: Int = 0,
)

/**
 * 统计页（M4）：本周柱状图 + 汇总。
 *
 * 图表用 Canvas 自绘，避免引入第三方图表库；配色遵循水墨令牌。
 * 320dp 下仍可读：柱子按可用宽度均分，标签只显示「周几」。
 */
@Composable
fun StatsPane(
    week: List<DayStat>,
    totalMs: Long,
    activeDays: Int,
    newPoems: Int,
    month: List<DayStat> = emptyList(),
    authors: List<io.github.xht252.motang.data.AuthorStat> = emptyList(),
    mastered: Int = 0,
    onExportPdf: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tokens = LocalInkTokens.current
    val maxMs = week.maxOfOrNull { it.activeMs }?.coerceAtLeast(1) ?: 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("背书统计", style = MaterialTheme.typography.displaySmall, color = tokens.ink)
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            androidx.compose.material3.OutlinedButton(onClick = onExportPdf) {
                Text("导出 PDF")
            }
        }

        Row(
            modifier = Modifier.widthIn(max = tokens.maxReadingWidth),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StatBlock("本周时长", formatDuration(totalMs), tokens.seal)
            StatBlock("打卡天数", "$activeDays", tokens.ink)
            StatBlock("新背篇数", "$newPoems", tokens.ink)
        }

        HorizontalDivider(color = tokens.hairline)

        Text("近 7 天", style = MaterialTheme.typography.titleMedium, color = tokens.ink)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = tokens.maxReadingWidth)
                .height(160.dp),
        ) {
            if (week.isEmpty()) return@Canvas
            val slot = size.width / week.size
            val barWidth = slot * 0.5f
            // 柱顶直接标数值（分钟）：图表可访问性要求不能只靠颜色/高度传达数据。
            val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#6B6B6B")
                textSize = 10.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
            }
            week.forEachIndexed { index, day ->
                val ratio = day.activeMs.toFloat() / maxMs
                val barHeight = (size.height - 34.dp.toPx()) * ratio
                val left = slot * index + (slot - barWidth) / 2
                val top = size.height - 20.dp.toPx() - barHeight
                drawRoundRect(
                    color = if (day.activeMs > 0) tokens.seal.copy(alpha = 0.75f) else tokens.hairline,
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight.coerceAtLeast(2f)),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                )
                val mins = day.activeMs / 60_000
                val label = if (day.activeMs <= 0) "0" else if (mins < 1) "<1" else mins.toString()
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    left + barWidth / 2f,
                    top - 4.dp.toPx(),
                    labelPaint,
                )
            }
        }

        // 轴标签：只显示星期，避免窄屏溢出。
        Row(modifier = Modifier.fillMaxWidth().widthIn(max = tokens.maxReadingWidth)) {
            week.forEach { day ->
                Text(
                    text = weekdayLabel(day.day),
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 11.sp,
                    color = tokens.pencil,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        HorizontalDivider(color = tokens.hairline)
        Text(
            "时长口径：active_ms（不含暂停与后台）",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.pencil,
        )

        // ---------------- 本月热力图 ----------------
        HorizontalDivider(color = tokens.hairline)
        Text("本月热力图", style = MaterialTheme.typography.titleMedium, color = tokens.ink)
        MonthHeatmap(month = month)

        // ---------------- 作者分布 ----------------
        if (authors.isNotEmpty()) {
            HorizontalDivider(color = tokens.hairline)
            Text("本月常背作者", style = MaterialTheme.typography.titleMedium, color = tokens.ink)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                authors.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().widthIn(max = tokens.maxReadingWidth),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.author.ifBlank { "佚名" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${row.count} 次",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.pencil,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = tokens.hairline)
        Text(
            "累计已背会：${mastered} 篇",
            style = MaterialTheme.typography.bodySmall,
            color = tokens.pencil,
        )
    }
}

/**
 * 月热力图。
 *
 * 7 列网格（周一列首），颜色深浅按当日 activeMs 归一。
 * 用 Canvas 自绘，避免引入图表库；320dp 下每格约 32dp，仍可辨认。
 */
@Composable
private fun MonthHeatmap(month: List<DayStat>) {
    val tokens = LocalInkTokens.current
    val byDay = month.associateBy { it.day }
    val maxMs = month.maxOfOrNull { it.activeMs }?.coerceAtLeast(1) ?: 1

    // 本月第一天是周几（1=周一），决定首行空几格。
    val firstDay = month.firstOrNull()?.day
    val leading = firstDay?.let {
        runCatching { java.time.LocalDate.parse(it).dayOfWeek.value - 1 }.getOrDefault(0)
    } ?: 0
    val cells = leading + month.size

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 10.sp,
                    color = tokens.pencil,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = tokens.maxReadingWidth)
                .height((((cells + 6) / 7) * 34).dp),
        ) {
            val cols = 7
            val gap = 4.dp.toPx()
            val cell = (size.width - gap * (cols - 1)) / cols
            month.forEachIndexed { index, day ->
                val pos = leading + index
                val row = pos / cols
                val col = pos % cols
                val intensity = day.activeMs.toFloat() / maxMs
                val color = if (day.activeMs > 0) {
                    tokens.seal.copy(alpha = 0.15f + 0.75f * intensity)
                } else {
                    tokens.hairline
                }
                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        col * (cell + gap),
                        row * (cell + gap),
                    ),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalInkTokens.current.pencil)
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color)
    }
}

/** 180000 → "3 分"；3720000 → "1 小时 2 分"。 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0 分"
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "$hours 小时 $minutes 分"
        totalMinutes > 0 -> "$minutes 分"
        else -> "不足 1 分"
    }
}

/** "2026-11-25" → "周二"。 */
fun weekdayLabel(isoDay: String): String = runCatching {
    val date = java.time.LocalDate.parse(isoDay)
    when (date.dayOfWeek.value) {
        1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
    }
}.getOrDefault("·")

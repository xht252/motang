package io.github.xht252.motang.ui.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import io.github.xht252.motang.data.AuthorStat
import io.github.xht252.motang.data.DayStat
import io.github.xht252.motang.data.StudyRepository
import io.github.xht252.motang.data.StudySession
import io.github.xht252.motang.ui.stats.formatDuration
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlinx.coroutines.runBlocking

/**
 * 学习报告 PDF。
 *
 * 设计取舍（结合 ui-ux-pro-max 的 E-Ink/Paper 与图表可访问性建议）：
 * - 纸白底 + 墨黑字 + 印章红强调，不依赖彩色区分信息（黑白打印也可读）；
 * - 每个图表都**直接标注数值**，不靠颜色深浅传意；
 * - 衬线字体、大留白、细分割线，接近书籍内页而非仪表盘。
 *
 * 用平台自带的 `PdfDocument` 绘制，不引入第三方 PDF 库。
 */
object StudyPdf {

    // A4 @ 72dpi
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 44f

    // 水墨令牌（与 App 内的 Ink 保持一致，PDF 无法读 Compose 主题，故此处复刻）
    private val PAPER = Color.parseColor("#F7F3EA")
    private val PAPER_DEEP = Color.parseColor("#EDE6D8")
    private val INK = Color.parseColor("#2B2B2B")
    private val INK_SOFT = Color.parseColor("#4A4A4A")
    private val PENCIL = Color.parseColor("#6B6B6B")
    private val SEAL = Color.parseColor("#9D2933")
    private val HAIRLINE = Color.parseColor("#D8D2C4")
    private val BAMBOO = Color.parseColor("#789262")

    private val serif: Typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    fun build(context: Context, study: StudyRepository, streak: Int): ByteArray {
        val data = runBlocking {
            ReportData(
                week = study.last7Days(),
                month = study.monthDays(),
                authors = study.topAuthorsThisMonth(),
                mastered = study.masteredSessionsOnce(),
                totalMs = study.totalMsAll(),
                totalPoems = study.totalPoemsAll(),
                masteredCount = study.masteredCount(),
                streak = streak,
            )
        }

        val document = PdfDocument()
        val writer = Writer(document)
        writer.newPage()
        writer.cover(data)
        writer.totals(data)
        writer.weekChart(data)
        writer.monthHeatmap(data)
        writer.authors(data)
        writer.masteredList(data)
        writer.finish()

        return ByteArrayOutputStream().use { out ->
            document.writeTo(out)
            document.close()
            out.toByteArray()
        }
    }

    private data class ReportData(
        val week: List<DayStat>,
        val month: List<DayStat>,
        val authors: List<AuthorStat>,
        val mastered: List<StudySession>,
        val totalMs: Long,
        val totalPoems: Int,
        val masteredCount: Int,
        val streak: Int,
    )

    /** 分页与绘制工具。 */
    private class Writer(private val document: PdfDocument) {
        private var page: PdfDocument.Page? = null
        private var canvas: Canvas? = null
        var y = 0f
        private var pageNo = 0

        private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = 10.5f
            typeface = serif
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PENCIL
            textSize = 8.5f
            typeface = serif
        }
        private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = 11.5f
            typeface = serifBold
        }
        private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HAIRLINE
            strokeWidth = 0.7f
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun newPage() {
            page?.let { p ->
                drawFooter()
                document.finishPage(p)
            }
            pageNo++
            val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()
            val p = document.startPage(info)
            page = p
            canvas = p.canvas
            // 纸底
            canvas!!.drawRect(0f, 0f, PAGE_W.toFloat(), PAGE_H.toFloat(), fill.apply { color = PAPER })
            y = MARGIN
        }

        private fun ensure(space: Float) {
            if (y + space > PAGE_H - MARGIN - 16f) newPage()
        }

        private fun drawFooter() {
            val c = canvas ?: return
            c.drawLine(MARGIN, PAGE_H - 40f, PAGE_W - MARGIN, PAGE_H - 40f, rule)
            c.drawText("墨唐 · 学习报告", MARGIN, PAGE_H - 26f, small)
            val txt = "第 $pageNo 页"
            c.drawText(txt, PAGE_W - MARGIN - small.measureText(txt), PAGE_H - 26f, small)
        }

        /** 页眉：竖排式印章 + 标题 + 日期。 */
        fun cover(data: ReportData) {
            val c = canvas!!
            // 印章方块
            fill.color = SEAL
            c.drawRoundRect(RectF(MARGIN, y, MARGIN + 40f, y + 40f), 4f, 4f, fill)
            val sealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = PAPER
                textSize = 13f
                typeface = serifBold
                textAlign = Paint.Align.CENTER
            }
            c.drawText("墨", MARGIN + 20f, y + 18f, sealPaint)
            c.drawText("唐", MARGIN + 20f, y + 34f, sealPaint)

            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK
                textSize = 26f
                typeface = serifBold
            }
            c.drawText("学习报告", MARGIN + 56f, y + 24f, title)
            c.drawText(
                "生成于 ${LocalDate.now()}",
                MARGIN + 56f,
                y + 40f,
                small,
            )
            y += 58f
            c.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            y += 20f
        }

        /** 三张 KPI 卡：数值直接写在卡上，不靠颜色。 */
        fun totals(data: ReportData) {
            val cards = listOf(
                "连续打卡" to "${data.streak} 天",
                "累计时长" to formatDuration(data.totalMs),
                "已背会" to "${data.masteredCount} 篇",
            )
            val gap = 12f
            val w = (PAGE_W - MARGIN * 2 - gap * 2) / 3f
            val h = 64f
            cards.forEachIndexed { i, (label, value) ->
                val left = MARGIN + i * (w + gap)
                fill.color = PAPER_DEEP
                canvas!!.drawRoundRect(RectF(left, y, left + w, y + h), 8f, 8f, fill)
                canvas!!.drawText(label, left + 12f, y + 20f, small)
                val vp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (i == 2) SEAL else INK
                    textSize = 17f
                    typeface = serifBold
                }
                canvas!!.drawText(value, left + 12f, y + 46f, vp)
            }
            y += h + 22f
        }

        fun sectionTitle(title: String, hint: String? = null) {
            ensure(48f)
            canvas!!.drawText(title, MARGIN, y, bold)
            if (hint != null) {
                canvas!!.drawText(hint, MARGIN + bold.measureText(title) + 10f, y, small)
            }
            y += 8f
            canvas!!.drawLine(MARGIN, y, PAGE_W - MARGIN, y, rule)
            y += 14f
        }

        /** 周柱状图：柱顶直接标时长，柱下标星期。 */
        fun weekChart(data: ReportData) {
            sectionTitle("近 7 天", "单位：分钟")
            val chartH = 130f
            val baseY = y + chartH
            val left = MARGIN + 8f
            val right = PAGE_W - MARGIN
            val slot = (right - left) / data.week.size.coerceAtLeast(1)
            val barW = slot * 0.42f
            val maxMs = data.week.maxOfOrNull { it.activeMs }?.coerceAtLeast(1) ?: 1

            // 基线
            canvas!!.drawLine(left, baseY, right, baseY, rule)

            val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK_SOFT
                textSize = 8f
                typeface = serif
                textAlign = Paint.Align.CENTER
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = PENCIL
                textSize = 8f
                typeface = serif
                textAlign = Paint.Align.CENTER
            }

            data.week.forEachIndexed { i, day ->
                val cx = left + slot * i + slot / 2f
                val ratio = day.activeMs.toFloat() / maxMs
                val h = (chartH - 18f) * ratio
                fill.color = if (day.activeMs > 0) BAMBOO else HAIRLINE
                val rect = RectF(cx - barW / 2f, baseY - h, cx + barW / 2f, baseY)
                canvas!!.drawRoundRect(rect, 3f, 3f, fill)
                // 数值（分钟），无数据时不标
                if (day.activeMs > 0) {
                    val mins = (day.activeMs / 60_000f)
                    canvas!!.drawText(
                        if (mins < 1) "<1" else mins.toInt().toString(),
                        cx,
                        baseY - h - 4f,
                        valuePaint,
                    )
                }
                canvas!!.drawText(weekday(day.day), cx, baseY + 12f, labelPaint)
            }
            y = baseY + 26f
        }

        /** 月热力图：只做深浅，但每格中心标日期数字，黑白可读。 */
        fun monthHeatmap(data: ReportData) {
            if (data.month.isEmpty()) return
            sectionTitle("本月热力图", "颜色越深表示当日学习越久")
            val cols = 7
            val gap = 5f
            val cell = (PAGE_W - MARGIN * 2 - gap * (cols - 1)) / cols
            val leading = runCatching {
                LocalDate.parse(data.month.first().day).dayOfWeek.value - 1
            }.getOrDefault(0)
            val maxMs = data.month.maxOfOrNull { it.activeMs }?.coerceAtLeast(1) ?: 1
            val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 7.5f
                typeface = serif
                textAlign = Paint.Align.CENTER
            }

            // 星期表头
            val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = PENCIL
                textSize = 8.5f
                typeface = serif
                textAlign = Paint.Align.CENTER
            }
            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, s ->
                canvas!!.drawText(s, MARGIN + cell / 2f + i * (cell + gap), y, headPaint)
            }
            y += 8f

            data.month.forEachIndexed { index, day ->
                val pos = leading + index
                val row = pos / cols
                val col = pos % cols
                val top = y + row * (cell + gap)
                ensure(cell + gap)
                val intensity = day.activeMs.toFloat() / maxMs
                fill.color = if (day.activeMs > 0) {
                    blend(PAPER_DEEP, SEAL, 0.15f + 0.65f * intensity)
                } else {
                    PAPER_DEEP
                }
                val left = MARGIN + col * (cell + gap)
                canvas!!.drawRoundRect(RectF(left, top, left + cell, top + cell), 4f, 4f, fill)
                numPaint.color = if (day.activeMs > 0) Color.WHITE else PENCIL
                canvas!!.drawText(
                    day.day.takeLast(2),
                    left + cell / 2f,
                    top + cell / 2f + 3f,
                    numPaint,
                )
            }
            y += ((data.month.size + leading + cols - 1) / cols) * (cell + gap) + 14f
        }

        /** 作者分布：横向条 + 次数，条长按最大值归一。 */
        fun authors(data: ReportData) {
            if (data.authors.isEmpty()) return
            sectionTitle("常背作者", "本月 Top ${data.authors.size}")
            val max = data.authors.maxOf { it.count }.coerceAtLeast(1)
            val labelW = 90f
            val barMaxW = PAGE_W - MARGIN * 2 - labelW - 46f

            data.authors.forEach { row ->
                ensure(18f)
                canvas!!.drawText(
                    row.author.ifBlank { "佚名" },
                    MARGIN,
                    y + 9f,
                    body,
                )
                val barW = barMaxW * (row.count.toFloat() / max)
                fill.color = Color.argb(140, 157, 41, 51)
                canvas!!.drawRoundRect(
                    RectF(MARGIN + labelW, y + 1f, MARGIN + labelW + barW, y + 11f),
                    3f,
                    3f,
                    fill,
                )
                canvas!!.drawText(
                    "${row.count} 次",
                    MARGIN + labelW + barW + 6f,
                    y + 9f,
                    small,
                )
                y += 17f
            }
            y += 10f
        }

        /** 已背会清单：两列排布，超出换页并续印。 */
        fun masteredList(data: ReportData) {
            sectionTitle("已背会清单", "共 ${data.mastered.size} 篇")
            if (data.mastered.isEmpty()) {
                canvas!!.drawText("（暂无）", MARGIN, y + 10f, small)
                y += 20f
                return
            }

            val colGap = 18f
            val colW = (PAGE_W - MARGIN * 2 - colGap) / 2f
            // 两列：每行两条，行高 15f；换行时才推进 y。
            data.mastered.forEachIndexed { index, s ->
                val col = index % 2
                if (col == 0) {
                    ensure(16f)
                }
                val left = MARGIN + col * (colW + colGap)
                val line = "《${s.title.ifBlank { s.poemId }}》${s.author.ifBlank { "佚名" }}"
                canvas!!.drawText(ellipsize(line, colW - 6f, body), left, y + 10f, body)
                if (col == 1 || index == data.mastered.lastIndex) {
                    y += 15f
                }
            }
            y += 8f
        }

        fun finish() {
            page?.let { p ->
                drawFooter()
                document.finishPage(p)
            }
            page = null
            canvas = null
        }

        private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
            if (paint.measureText(text) <= maxWidth) return text
            var end = text.length
            while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
                end--
            }
            return text.substring(0, end) + "…"
        }

        private fun weekday(iso: String): String = runCatching {
            when (LocalDate.parse(iso).dayOfWeek.value) {
                1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; else -> "日"
            }
        }.getOrDefault("·")

        private fun blend(a: Int, b: Int, t: Float): Int {
            val tt = t.coerceIn(0f, 1f)
            val ar = Color.red(a); val ag = Color.green(a); val ab = Color.blue(a)
            val br = Color.red(b); val bg = Color.green(b); val bb = Color.blue(b)
            return Color.rgb(
                (ar + (br - ar) * tt).toInt(),
                (ag + (bg - ag) * tt).toInt(),
                (ab + (bb - ab) * tt).toInt(),
            )
        }
    }
}

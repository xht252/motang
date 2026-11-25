package io.github.xht252.motang.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统计展示口径单测（纯函数，无需设备）。
 */
class StatsFormatTest {

    @Test
    fun durationFormatting() {
        assertEquals("0 分", formatDuration(0))
        assertEquals("不足 1 分", formatDuration(1_000))
        assertEquals("3 分", formatDuration(180_000))
        assertEquals("1 小时 2 分", formatDuration(3_720_000))
    }

    @Test
    fun weekdayLabels() {
        assertEquals("二", weekdayLabel("2026-11-24"))
        assertEquals("三", weekdayLabel("2026-11-25"))
    }

    @Test
    fun invalidDayFallsBack() {
        assertEquals("·", weekdayLabel("not-a-date"))
    }
}

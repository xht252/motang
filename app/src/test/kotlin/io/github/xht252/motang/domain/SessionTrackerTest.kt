package io.github.xht252.motang.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话计时口径单测。
 *
 * 核心断言：**activeMs 不含暂停时间**。这是全 App 统一的时长口径，
 * 任何改动都必须让这组测试通过。
 */
class SessionTrackerTest {

    @Test
    fun activeMsIncludesOnlyRunningSegments() {
        val tracker = SessionTracker()
        tracker.start("p1", "静夜思", "李白", nowMillis = 1_000)

        // 运行 10s
        assertEquals(10_000, tracker.snapshotMs(nowMillis = 11_000))

        // 暂停 30s：不应计入
        tracker.pause(nowMillis = 11_000)
        assertEquals(10_000, tracker.snapshotMs(nowMillis = 41_000))

        // 继续 5s
        tracker.resume(nowMillis = 41_000)
        assertEquals(15_000, tracker.snapshotMs(nowMillis = 46_000))
    }

    @Test
    fun finishReturnsAccumulatedActiveAndKeepsMetadata() {
        val tracker = SessionTracker()
        tracker.start("p1", "静夜思", "李白", nowMillis = 0)
        tracker.pause(nowMillis = 3_000)
        tracker.resume(nowMillis = 5_000)

        val done = tracker.finish(nowMillis = 8_000)
        assertNotNull(done)
        assertEquals(6_000, done!!.activeMs) // 3s + 3s
        assertEquals("p1", done.poemId)
        assertEquals("静夜思", done.title)
        assertEquals("李白", done.author)

        // finish 后状态清空
        assertNull(tracker.state.value.poemId)
        assertFalse(tracker.state.value.running)
    }

    @Test
    fun finishWithoutSessionReturnsNull() {
        assertNull(SessionTracker().finish())
    }

    @Test
    fun pauseIsIdempotent() {
        val tracker = SessionTracker()
        tracker.start("p1", "t", "a", nowMillis = 0)
        tracker.pause(nowMillis = 1_000)
        tracker.pause(nowMillis = 9_000) // 重复暂停不应重复累加
        assertEquals(1_000, tracker.snapshotMs(nowMillis = 20_000))
    }

    @Test
    fun restoreStoppedPausesButKeepsAccumulated() {
        val tracker = SessionTracker()
        tracker.start("p1", "t", "a", nowMillis = 0)
        tracker.restoreStopped()
        assertFalse(tracker.state.value.running)
        assertTrue(tracker.state.value.activeMs >= 0)
    }
}

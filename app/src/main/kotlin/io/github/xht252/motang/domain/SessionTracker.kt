package io.github.xht252.motang.domain

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 背诵会话计时。
 *
 * 口径（全 App 统一）：**activeMs 不含暂停与后台时间**。
 * 前台/后台切换由 Lifecycle 驱动 [onForeground]/[onBackground]，
 * 因此旋转、开合、分屏都不会让计时跳变——状态只在这里。
 */
data class SessionState(
    val poemId: String? = null,
    val title: String = "",
    val author: String = "",
    val running: Boolean = false,
    val activeMs: Long = 0,
    /** 当前这一段开始计时的 Wall clock；null 表示未在计时。 */
    val segmentStartedAt: Long? = null,
)

class SessionTracker {

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** 开始（或切换）背诵某首诗，计时立刻开始。 */
    fun start(
        poemId: String,
        title: String,
        author: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        _state.value = SessionState(
            poemId = poemId,
            title = title,
            author = author,
            running = true,
            activeMs = 0,
            segmentStartedAt = nowMillis,
        )
    }

    fun pause(nowMillis: Long = System.currentTimeMillis()) {
        _state.update { current ->
            if (!current.running) return@update current
            current.copy(
                running = false,
                activeMs = current.activeMs + (nowMillis - (current.segmentStartedAt ?: nowMillis)),
                segmentStartedAt = null,
            )
        }
    }

    fun resume(nowMillis: Long = System.currentTimeMillis()) {
        _state.update { current ->
            if (current.running || current.poemId == null) return@update current
            current.copy(running = true, segmentStartedAt = nowMillis)
        }
    }

    /** UI 时钟用：不改变状态，只计算「到目前为止」的 activeMs。 */
    fun snapshotMs(nowMillis: Long = System.currentTimeMillis()): Long {
        val s = _state.value
        return if (s.running) {
            s.activeMs + (nowMillis - (s.segmentStartedAt ?: nowMillis))
        } else {
            s.activeMs
        }
    }

    /**
     * 结束会话并返回落库所需数据；无进行中的会话返回 null。
     * 调用方负责写入 Room 并在成功后触发打卡。
     */
    fun finish(nowMillis: Long = System.currentTimeMillis()): FinishedSession? {
        val s = _state.value
        val poemId = s.poemId ?: return null
        val total = snapshotMs(nowMillis)
        _state.value = SessionState()
        return FinishedSession(
            poemId = poemId,
            title = s.title,
            author = s.author,
            activeMs = total,
            startedAt = nowMillis - total,
            endedAt = nowMillis,
            day = LocalDate.now(),
        )
    }

    /** 进程恢复后清理：不自动续计时，避免把后台时间算进去。 */
    fun restoreStopped() {
        _state.update { current ->
            if (current.running) current.copy(running = false, segmentStartedAt = null) else current
        }
    }
}

data class FinishedSession(
    val poemId: String,
    val title: String,
    val author: String,
    val activeMs: Long,
    val startedAt: Long,
    val endedAt: Long,
    val day: LocalDate,
)

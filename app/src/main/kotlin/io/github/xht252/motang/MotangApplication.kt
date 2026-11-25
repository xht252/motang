package io.github.xht252.motang

import android.app.Application
import android.util.Log
import io.github.xht252.motang.core.MotangException
import io.github.xht252.motang.core.ping
import io.github.xht252.motang.data.SearchRepository

/**
 * 进程级初始化。
 *
 * 只做「便宜」的事：创建仓库、打一条 native 探针日志。
 * 索引构建是重活，交给 SearchViewModel 在 IO 线程按状态机推进——
 * 放这里会拖慢冷启动，也拿不到进度 UI。
 *
 * `:llm` 独立进程（M5）也会复用该 Application。
 */
class MotangApplication : Application() {

    lateinit var repository: SearchRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = SearchRepository(this)

        // 记录 native 链路，便于 adb logcat 快速定位「库没打进去」这类问题。
        runCatching { ping() }
            .onSuccess { Log.i(TAG, "native ok: $it") }
            .onFailure { error: Throwable ->
                val reason = (error as? MotangException)?.message ?: error.message
                Log.e(TAG, "native failed: $reason")
            }
    }

    private companion object {
        const val TAG = "MotangM0"
    }
}

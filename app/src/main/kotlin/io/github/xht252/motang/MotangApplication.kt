package io.github.xht252.motang

import android.app.Application
import android.util.Log
import io.github.xht252.motang.core.MotangException
import io.github.xht252.motang.core.ping
import io.github.xht252.motang.data.SearchRepository
import io.github.xht252.motang.data.SettingsRepository
import io.github.xht252.motang.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = SearchRepository(this)
        settings = SettingsRepository(this)

        // 进程启动即校正提醒：覆盖 BOOT_COMPLETED 未送达、闹钟被系统清理、
        // 以及应用被强停后重新打开等情况。读取 DataStore 放到后台线程。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val reminder = settings.reminderNow()
                ReminderScheduler.apply(
                    this@MotangApplication,
                    reminder.enabled,
                    reminder.hour,
                    reminder.minute,
                )
            }
        }

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

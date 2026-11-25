package io.github.xht252.motang.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.xht252.motang.MainActivity
import io.github.xht252.motang.MotangApplication
import io.github.xht252.motang.R
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 每日提醒调度。
 *
 * 权限矩阵（自签直装，不上 Play）：
 * - API 26–30：无需权限，直接 `setExactAndAllowWhileIdle`
 * - API 31–32：`SCHEDULE_EXACT_ALARM`，需检查 `canScheduleExactAlarms`
 * - API 33+：`USE_EXACT_ALARM`，安装即授予
 *
 * 任何一步失败（SecurityException / 未授权）→ 降级 WorkManager flex 窗口，
 * 保证「提醒仍在，只是可能不准时」，而不是静默丢失。
 */
object ReminderScheduler {

    private const val TAG = "MotangReminder"
    private const val REQUEST_CODE = 4201
    private const val WORK_NAME = "motang-daily-reminder"

    /** 按设置安排下一次提醒；未启用则取消。 */
    fun apply(context: Context, enabled: Boolean, hour: Int, minute: Int) {
        if (!enabled) {
            cancel(context)
            return
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextTriggerMillis(hour, minute)

        val pending = alarmIntent(context)
        val exactAllowed = canScheduleExact(alarmManager)

        if (exactAllowed) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pending,
                )
                Log.i(TAG, "精确闹钟已安排：$triggerAt")
                return
            } catch (error: SecurityException) {
                Log.w(TAG, "精确闹钟被拒，降级 WorkManager", error)
            }
        } else {
            Log.i(TAG, "未获精确闹钟授权，降级 WorkManager")
        }

        scheduleWorkManager(context)
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager?.cancel(alarmIntent(context))
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        Log.i(TAG, "已取消提醒")
    }

    /** 下一天同一时刻（若今天已过则顺延到明天）。 */
    fun nextTriggerMillis(hour: Int, minute: Int, now: LocalDateTime = LocalDateTime.now()): Long {
        var target = now.toLocalDate().atTime(LocalTime.of(hour, minute))
        if (!target.isAfter(now)) {
            target = target.plusDays(1)
        }
        return target.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager.canScheduleExactAlarms()
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleWorkManager(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    const val ACTION_REMIND = "io.github.xht252.motang.action.REMIND"
}

/** 降级路径：WorkManager 周期任务。 */
class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        ReminderNotifier.notifyDaily(applicationContext)
        return Result.success()
    }
}

/** 精确闹钟触发点：发通知并链式安排次日。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        ReminderNotifier.notifyDaily(context)

        // 链式注册次日，避免只响一次。
        val settings = runBlocking {
            (context.applicationContext as? MotangApplication)?.settings?.reminderNow()
        } ?: return
        if (settings.enabled) {
            ReminderScheduler.apply(context, true, settings.hour, settings.minute)
        }
    }
}

/** 开机 / 时区变化 / 应用更新后重注册。 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val settings = runBlocking {
            (context.applicationContext as? MotangApplication)?.settings?.reminderNow()
        } ?: return
        if (settings.enabled) {
            ReminderScheduler.apply(context, true, settings.hour, settings.minute)
        }
    }
}

/** 通知渠道与内容。 */
object ReminderNotifier {

    // v2：老渠道一旦创建，importance/声音由系统记住，改代码不生效，必须换 ID。
    private const val CHANNEL_ID = "daily_reminder_v2"
    private const val NOTIFICATION_ID = 4201

    /** 名句池：通知栏只放短句，避免在 Receiver 里做检索 IO。 */
    private val QUOTES = listOf(
        "海上生明月，天涯共此时。",
        "床前明月光，疑是地上霜。",
        "春眠不觉晓，处处闻啼鸟。",
        "大漠孤烟直，长河落日圆。",
        "会当凌绝顶，一览众山小。",
        "长风破浪会有时，直挂云帆济沧海。",
        "山重水复疑无路，柳暗花明又一村。",
        "无边落木萧萧下，不尽长江滚滚来。",
    )

    fun notifyDaily(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("MotangReminder", "缺少通知权限，跳过发送")
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)

        val content = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPending = PendingIntent.getActivity(
            context,
            0,
            content,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val quote = QUOTES.random()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("今日一诗")
            .setContentText(quote)
            .setStyle(NotificationCompat.BigTextStyle().bigText(quote))
            .setContentIntent(contentPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // 双保险：即使渠道被用户改过，也强制静默（不发声、不震动）。
            .setSilent(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "每日提醒",
            // LOW：只出通知栏消息，不发声、不震动（用户要求「不要像闹钟那样响」）。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "每天提醒背诵一首唐诗（静默通知）"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }
}

package io.github.xht252.motang

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.github.xht252.motang.ui.MotangApp
import io.github.xht252.motang.ui.theme.MotangTheme

/**
 * 单 Activity 宿主。
 *
 * 注意：不得在此处锁定方向或拦截 screenSize 类配置变更——布局决策全部交给
 * 运行时窗口宽度（见 .kilo/skills/foldable-adaptive-ui/SKILL.md）。
 */
class MainActivity : ComponentActivity() {

    /**
     * 13+ 通知权限。
     *
     * 在 Activity 里申请而不是 Composable 里：权限弹窗需要 Activity 上下文，
     * 且只能申请一次（系统会记住拒绝），避免反复打扰。
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            android.util.Log.i("MotangReminder", "通知权限申请结果：$granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        maybeRequestNotificationPermission()

        setContent {
            MotangTheme {
                // 支持 adb 驱动的自动化验收：
                //   adb shell am start -n io.github.xht252.motang/.MainActivity -e query 明月
                MotangApp(initialQuery = intent?.getStringExtra("query"))
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

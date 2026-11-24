package io.github.xht252.motang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.xht252.motang.ui.MotangApp
import io.github.xht252.motang.ui.theme.MotangTheme

/**
 * 单 Activity 宿主。
 *
 * 注意：不得在此处锁定方向或拦截 screenSize 类配置变更——布局决策全部交给
 * 运行时 WindowSizeClass（见 .kilo/skills/foldable-adaptive-ui/SKILL.md）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MotangTheme {
                MotangApp()
            }
        }
    }
}

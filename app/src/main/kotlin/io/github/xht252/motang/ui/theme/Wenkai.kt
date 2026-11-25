package io.github.xht252.motang.ui.theme

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/** 期望的字体资源路径（相对 assets）。 */
const val WENKAI_ASSET = "fonts/LXGWWenKai-Regular.ttf"

/**
 * 霞鹜文楷（OFL）。
 *
 * 字体文件 ~19MB，不入库（见 .gitignore）。放置到
 * `app/src/main/assets/fonts/LXGWWenKai-Regular.ttf` 后自动生效；
 * 缺失时静默回退系统衬线体，保证任何环境下都能构建与运行。
 *
 * 为什么先拷贝到 cache 再加载：Compose 的 `Font(File)` 需要真实文件路径，
 * 而 assets 里的资源不能直接作为路径打开（且 APK 内资源可能是压缩的）。
 */
@Composable
fun rememberWenkaiFontFamily(): FontFamily {
    val context = LocalContext.current
    return remember(context) { loadWenkai(context) ?: FontFamily.Serif }
}

private fun loadWenkai(context: Context): FontFamily? = runCatching {
    val dir = File(context.cacheDir, "fonts").apply { mkdirs() }
    val target = File(dir, "lxgw.ttf")
    val marker = File(dir, ".version")

    // 不能用 openFd 取长度：assets 默认压缩，会抛 "probably compressed"（M1 实测）。
    // 用 versionCode 做标记：升级后重拷一次，其余冷启动零拷贝。
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    }.getOrDefault(0L).toString()

    if (!target.isFile || marker.takeIf { it.isFile }?.readText() != version) {
        context.assets.open(WENKAI_ASSET).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        marker.writeText(version)
    }
    FontFamily(Font(target)).also {
        Log.i("MotangFont", "霞鹜文楷已加载：${target.length()} bytes")
    }
}.onFailure {
    // 未放字体是正常路径（回退系统衬线），用 info 不用 error 避免刷日志。
    Log.i("MotangFont", "未找到内置字体，回退系统衬线：${it.message}")
}.getOrNull()

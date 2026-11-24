package io.github.xht252.motang.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 水墨设计令牌（M2 会扩成完整 Typography / Shape 体系）。
val XuanPaper = Color(0xFFF5F0E6)
val InkBlack = Color(0xFF2B2B2B)
val DaiBlue = Color(0xFF425066)
val ZheStone = Color(0xFF955539)
val BambooGreen = Color(0xFF789262)
val SealCrimson = Color(0xFF9D2933)

private val LightColors = lightColorScheme(
    primary = DaiBlue,
    onPrimary = XuanPaper,
    secondary = ZheStone,
    tertiary = BambooGreen,
    background = XuanPaper,
    onBackground = InkBlack,
    surface = XuanPaper,
    onSurface = InkBlack,
    error = SealCrimson,
)

private val DarkColors = darkColorScheme(
    primary = BambooGreen,
    onPrimary = InkBlack,
    secondary = ZheStone,
    tertiary = DaiBlue,
    background = InkBlack,
    onBackground = XuanPaper,
    surface = Color(0xFF1E1E1E),
    onSurface = XuanPaper,
    error = SealCrimson,
)

@Composable
fun MotangTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

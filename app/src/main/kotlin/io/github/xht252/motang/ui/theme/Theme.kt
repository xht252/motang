package io.github.xht252.motang.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 水墨设计令牌。
 *
 * 取值依据两条来源：
 * 1. 项目既有约定（宣纸/墨/黛蓝/赭石/竹青/胭脂）。
 * 2. `ui-ux-pro-max` 的 E-Ink / Paper 风格建议：
 *    纸白高对比、无渐变、衬线阅读字体、克制动效。
 *
 * E-Ink 建议「不推荐深色模式」，但项目硬规则要求「夜读」主题，
 * 因此这里以**降低对比与亮度**的方式实现夜读，而不是纯黑反白。
 */
object Ink {
    // 纸与墨
    val Paper = Color(0xFFF5F0E6)
    val PaperDeep = Color(0xFFEDE6D8)
    val Ink = Color(0xFF2B2B2B)
    val InkSoft = Color(0xFF4A4A4A)
    val Pencil = Color(0xFF6B6B6B)

    // 传统色
    val DaiBlue = Color(0xFF425066)
    val ZheStone = Color(0xFF955539)
    val Bamboo = Color(0xFF789262)
    val Seal = Color(0xFF9D2933)

    // 夜读
    val NightPaper = Color(0xFF17181A)
    val NightSurface = Color(0xFF1F2124)
    val NightInk = Color(0xFFD8D2C4)
    val NightInkSoft = Color(0xFF9C978C)
}

/**
 * 与 Material 语义色无关的额外令牌。
 *
 * 用 CompositionLocal 暴露，避免把「宣纸/印章」这类项目语义塞进 ColorScheme。
 */
data class InkTokens(
    val paper: Color,
    val paperDeep: Color,
    val ink: Color,
    val inkSoft: Color,
    val pencil: Color,
    val seal: Color,
    val hairline: Color,
    /** 正文最大行宽，防止大屏一行过长。 */
    val maxReadingWidth: androidx.compose.ui.unit.Dp = 600.dp,
    /** 竖排仅在详情 pane 达到该宽度时默认启用。 */
    val verticalMinWidth: androidx.compose.ui.unit.Dp = 560.dp,
)

/** 阅读字号倍率；由设置页写入，正文按此缩放。 */
val LocalFontScale = staticCompositionLocalOf { 1f }

val LocalInkTokens = staticCompositionLocalOf {
    InkTokens(
        paper = Ink.Paper,
        paperDeep = Ink.PaperDeep,
        ink = Ink.Ink,
        inkSoft = Ink.InkSoft,
        pencil = Ink.Pencil,
        seal = Ink.Seal,
        hairline = Color(0x1F2B2B2B),
    )
}

private val LightColors = lightColorScheme(
    primary = Ink.DaiBlue,
    onPrimary = Ink.Paper,
    secondary = Ink.ZheStone,
    tertiary = Ink.Bamboo,
    background = Ink.Paper,
    onBackground = Ink.Ink,
    surface = Ink.Paper,
    onSurface = Ink.Ink,
    surfaceVariant = Ink.PaperDeep,
    onSurfaceVariant = Ink.InkSoft,
    outline = Color(0x332B2B2B),
    error = Ink.Seal,
)

private val DarkColors = darkColorScheme(
    primary = Ink.Bamboo,
    onPrimary = Ink.NightPaper,
    secondary = Ink.ZheStone,
    tertiary = Ink.DaiBlue,
    background = Ink.NightPaper,
    onBackground = Ink.NightInk,
    surface = Ink.NightSurface,
    onSurface = Ink.NightInk,
    surfaceVariant = Color(0xFF26282C),
    onSurfaceVariant = Ink.NightInkSoft,
    outline = Color(0x33D8D2C4),
    error = Color(0xFFE06B72),
)

/**
 * 排版：阅读类应用以「正文可读性」为第一优先。
 *
 * 字体族由 [rememberWenkaiFontFamily] 提供：内置霞鹜文楷时用它，
 * 否则回退系统衬线体（见 Wenkai.kt）。
 */
private fun inkTypography(family: FontFamily): Typography {
    val base = Typography()
    return Typography(
        displaySmall = base.displaySmall.copy(
            fontFamily = family,
            fontWeight = FontWeight.Medium,
            fontSize = 34.sp,
            lineHeight = 44.sp,
            letterSpacing = 2.sp,
        ),
        headlineSmall = base.headlineSmall.copy(
            fontFamily = family,
            fontWeight = FontWeight.Medium,
            fontSize = 24.sp,
            lineHeight = 34.sp,
            letterSpacing = 1.sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = family,
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            lineHeight = 26.sp,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontFamily = family,
            fontSize = 20.sp,
            lineHeight = 34.sp,
            letterSpacing = 1.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontFamily = family,
            fontSize = 15.sp,
            lineHeight = 24.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontFamily = family,
            fontSize = 13.sp,
            lineHeight = 20.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = family,
            fontSize = 12.sp,
            letterSpacing = 0.5.sp,
        ),
    )
}

@Composable
fun MotangTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) {
        InkTokens(
            paper = Ink.NightPaper,
            paperDeep = Ink.NightSurface,
            ink = Ink.NightInk,
            inkSoft = Ink.NightInkSoft,
            pencil = Ink.NightInkSoft,
            seal = Color(0xFFC05A62),
            hairline = Color(0x33D8D2C4),
        )
    } else {
        InkTokens(
            paper = Ink.Paper,
            paperDeep = Ink.PaperDeep,
            ink = Ink.Ink,
            inkSoft = Ink.InkSoft,
            pencil = Ink.Pencil,
            seal = Ink.Seal,
            hairline = Color(0x1F2B2B2B),
        )
    }

    val family = rememberWenkaiFontFamily()

    CompositionLocalProvider(
        LocalInkTokens provides tokens,
        LocalFontScale provides fontScale,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = inkTypography(family),
            content = content,
        )
    }
}

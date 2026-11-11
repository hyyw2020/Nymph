package dev.nymph.auto.ui.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nymph.auto.core.design.NymphDesignTokens as T

/**
 * 深色优先的视觉体系。
 *
 * 自动化工具的使用场景多在「全屏应用之上」，深色底不会在切换时刺眼；
 * 主色用青绿（teal）表示"可执行/正常"，错误用暖红，警告用琥珀，
 * 三色在深底上对比度均 > 4.5:1（WCAG AA）。
 *
 * 不启用动态取色：品牌色若随壁纸变化，"录制中"的红色状态提示可能
 * 变成与背景相近的色相，状态语义就不稳了。
 *
 * **v2.9.0：色板不再本地硬编码**。全部取自 core 纯 JVM 唯一真源
 * [dev.nymph.auto.core.design.NymphDesignTokens]，与悬浮层
 * `FloatingEditorTheme` 同源；改一处两边同时生效，并由 core 单测锁死
 * 对比度与语义色可区分性。这里只保留「M3 ColorScheme 槽位 → 语义 token」
 * 的映射（两套 UI 的排版尺度本就不同，字号/圆角不强行统一）。
 * [c] 把 0xAARRGGBB 的 token 投影为 packed Int，与旧 `Color(0xFF...)` 视觉等价。
 */
private fun c(token: Long): Color = Color(T.asArgb(token))

private val Ink = c(T.INK)
private val Surface0 = c(T.SURFACE_0)
private val Surface1 = c(T.SURFACE_1)
private val Surface2 = c(T.SURFACE_2)
private val Outline = c(T.OUTLINE)
private val Teal = c(T.ACCENT_BRIGHT)
private val TealDeep = c(T.ACCENT_DEEP)
private val Amber = c(T.WARNING)
private val Red = c(T.DANGER_SOFT)

private val NymphColors = darkColorScheme(
    primary = Teal,
    onPrimary = c(T.ON_PRIMARY),
    primaryContainer = TealDeep,
    onPrimaryContainer = c(T.ON_PRIMARY_CONTAINER),
    secondary = c(T.SECONDARY),
    onSecondary = c(T.ON_SECONDARY),
    secondaryContainer = c(T.SECONDARY_CONTAINER),
    onSecondaryContainer = c(T.ON_SECONDARY_CONTAINER),
    tertiary = Amber,
    onTertiary = c(T.ON_TERTIARY),
    tertiaryContainer = c(T.TERTIARY_CONTAINER),
    onTertiaryContainer = c(T.ON_TERTIARY_CONTAINER),
    background = Ink,
    onBackground = c(T.TEXT_PRIMARY),
    surface = Surface0,
    onSurface = c(T.TEXT_PRIMARY),
    surfaceVariant = Surface1,
    onSurfaceVariant = c(T.TEXT_ON_SURFACE_DIM),
    surfaceContainer = Surface0,
    surfaceContainerHigh = Surface1,
    surfaceContainerHighest = Surface2,
    surfaceContainerLow = c(T.SURFACE_CONTAINER_LOW),
    surfaceContainerLowest = c(T.SURFACE_CONTAINER_LOWEST),
    error = Red,
    onError = c(T.ON_ERROR),
    errorContainer = c(T.ERROR_CONTAINER),
    onErrorContainer = c(T.ON_ERROR_CONTAINER),
    outline = Outline,
    outlineVariant = c(T.OUTLINE_VARIANT),
    inverseSurface = c(T.INVERSE_SURFACE),
    inverseOnSurface = c(T.INVERSE_ON_SURFACE),
)

private val NymphTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp),
)

private val NymphShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NymphTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NymphColors,
        typography = NymphTypography,
        shapes = NymphShapes,
        content = content,
    )
}

/** 等宽字体：日志与诊断信息用，避免数字跳动。 */
internal val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 10.5.sp,
    lineHeight = 15.sp,
)

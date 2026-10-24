package cn.imgozi.planetport.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.imgozi.planetport.ui.StatusColor

// 色值来源：iOS systemGroupedBackground 等语义色等价 + Assets 资产色
// （AccentColor light #D44037 / dark #FF695B；SuccessGreen 见 ThemeManager.successFor）。
private val PageLight = Color(0xFFF2F2F7)
private val PageDark = Color(0xFF1C1C1E)
private val CardLight = Color(0xFFFFFFFF)
private val CardDark = Color(0xFF2C2C2E)

/** 发丝分隔线（iOS .separator：浅 3C3C43@29% / 深 545458@60%），outlineVariant 共用。 */
private val HairlineLight = Color(0x4A3C3C43)
private val HairlineDark = Color(0x99545458)

/** 次要文字（iOS .secondaryLabel：浅 3C3C43@60% / 深 EBEBF5@60%）。 */
private val SecondaryTextLight = Color(0x993C3C43)
private val SecondaryTextDark = Color(0x99EBEBF5)

/** 状态色：好 = successFor（主题派生）；注意橙 / 危险红取 iOS 系统语义色深浅变体。 */
private val WarnLight = Color(0xFFFF9500)
private val WarnDark = Color(0xFFFF9F0A)
private val DangerLight = Color(0xFFFF3B30)
private val DangerDark = Color(0xFFFF453A)

/** 三级文字（iOS .tertiaryLabel，等宽 ID/IP/hex 专用）；M3 ColorScheme 无对应槽位。 */
fun tertiaryTextColor(dark: Boolean): Color =
    if (dark) Color(0x4DEBEBF5) else Color(0x4D3C3C43)

/** 语义状态色板（好/注意/中性/危险），由 [PlanetTheme] 按 ThemeManager 当前值装配。 */
@Immutable
data class PlanetStatusColors(
    val good: Color,
    val warn: Color,
    val neutral: Color,
    val danger: Color,
) {
    operator fun get(status: StatusColor): Color = when (status) {
        StatusColor.Good -> good
        StatusColor.Warn -> warn
        StatusColor.Neutral -> neutral
        StatusColor.Danger -> danger
    }
}

val LocalPlanetStatusColors = staticCompositionLocalOf<PlanetStatusColors> {
    error("PlanetStatusColors 必须由 PlanetTheme 提供")
}

/**
 * 全 App 根主题：primary = ThemeManager.accentFor（深色自动提亮），
 * 页面底/卡片底对齐 iOS systemGroupedBackground 体系，圆角刻度 10/14/20。
 * 不含 Surface 脚手架——页面自组 Scaffold/Surface 并取 colorScheme.background。
 * MainActivity 换挂由 T3 完成。
 */
@Composable
fun PlanetTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 订阅 ThemeManager 流（读取值作 remember key）：换主色/切换派生开关即时重组生效
    val accentHex = ThemeManager.accent.collectAsState().value
    val derived = ThemeManager.successDerived.collectAsState().value
    val accent = remember(accentHex, derived, dark) { ThemeManager.accentFor(dark) }
    val success = remember(accentHex, derived, dark) { ThemeManager.successFor(dark) }
    val secondary = if (dark) SecondaryTextDark else SecondaryTextLight
    val danger = if (dark) DangerDark else DangerLight

    val colorScheme = if (dark) {
        planetColorScheme(
            dark = true,
            accent = accent,
            page = PageDark,
            card = CardDark,
            onSurface = Color.White,
            secondaryText = secondary,
            hairline = HairlineDark,
            error = danger,
        )
    } else {
        planetColorScheme(
            dark = false,
            accent = accent,
            page = PageLight,
            card = CardLight,
            onSurface = Color.Black,
            secondaryText = secondary,
            hairline = HairlineLight,
            error = danger,
        )
    }

    val statusColors = PlanetStatusColors(
        good = success,
        warn = if (dark) WarnDark else WarnLight,
        neutral = secondary,
        danger = danger,
    )

    CompositionLocalProvider(LocalPlanetStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PlanetTypography,
            shapes = PlanetShapes,
            content = content,
        )
    }
}

/**
 * iOS 语义色 → M3 ColorScheme 映射：background/surface = 页面底，
 * surfaceContainer 全系 = 卡片底（secondarySystemGroupedBackground 等价），
 * outline/outlineVariant = 发丝线；secondary/primaryContainer = accent 0.12 底
 * （对齐徽标 0.12 同色底纪律）；surfaceTint 透明（无阴影/无海拔染色纪律）。
 */
private fun planetColorScheme(
    dark: Boolean,
    accent: Color,
    page: Color,
    card: Color,
    onSurface: Color,
    secondaryText: Color,
    hairline: Color,
    error: Color,
): ColorScheme {
    val tintedContainer = accent.copy(alpha = 0.12f)
    return if (dark) {
        darkColorScheme(
            primary = accent, onPrimary = Color.White,
            primaryContainer = tintedContainer, onPrimaryContainer = accent,
            secondaryContainer = tintedContainer, onSecondaryContainer = accent,
            background = page, onBackground = onSurface,
            surface = page, onSurface = onSurface,
            surfaceVariant = page, onSurfaceVariant = secondaryText,
            surfaceContainerLowest = card, surfaceContainerLow = card,
            surfaceContainer = card, surfaceContainerHigh = card,
            surfaceContainerHighest = card,
            surfaceTint = Color.Transparent,
            outline = hairline, outlineVariant = hairline,
            error = error, onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = accent, onPrimary = Color.White,
            primaryContainer = tintedContainer, onPrimaryContainer = accent,
            secondaryContainer = tintedContainer, onSecondaryContainer = accent,
            background = page, onBackground = onSurface,
            surface = page, onSurface = onSurface,
            surfaceVariant = page, onSurfaceVariant = secondaryText,
            surfaceContainerLowest = card, surfaceContainerLow = card,
            surfaceContainer = card, surfaceContainerHigh = card,
            surfaceContainerHighest = card,
            surfaceTint = Color.Transparent,
            outline = hairline, outlineVariant = hairline,
            error = error, onError = Color.White,
        )
    }
}

/**
 * 字阶（iOS 侦查 §3）：字重只用 regular/medium/semibold；
 * 标题 22 bold / 行星名 20 semibold / 卡片行标题 15 semibold / 正文 15 / caption 12 / 徽标 11 semibold。
 * 等宽（ID/IP/hex 专用）见 [PlanetFonts]，不占用 Typography 槽位。
 */
private val PlanetTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp),
)

/** 圆角刻度（iOS §3：小控件 10 / 卡片 14 / 大表单卡与图标磁贴 20 / 预览占位 16）。 */
private val PlanetShapes = Shapes(
    extraSmall = RoundedCornerShape(HomeMetrics.radiusSmall),
    small = RoundedCornerShape(HomeMetrics.radiusSmall),
    medium = RoundedCornerShape(HomeMetrics.radiusCard),
    large = RoundedCornerShape(HomeMetrics.radiusLarge),
    extraLarge = RoundedCornerShape(HomeMetrics.radiusLarge),
)

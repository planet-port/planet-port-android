package cn.imgozi.planetport.ui.theme

import androidx.compose.ui.graphics.Color
import cn.imgozi.planetport.ui.planet.PlanetIcon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * 全局主题（对齐 iOS ThemeManager.swift）：用户只设一个主色，
 * 明暗变体、状态色派生、行星装饰盘全部运行时 HSL 计算。
 *
 * 内存态单例：[accent] / [successDerived] 为运行值 StateFlow；
 * 持久化字段（AppPrefs 主题三项）由 Task 4 PrefsView 双写接线，
 * 写入口统一走 [applyPrefs]，本类不读写磁盘。
 */
object ThemeManager {

    /** 预设主色板：默认红（三角配色定稿 #D44037）+ 7 个国画经典色（iOS ThemeManager.swift:10-19）。 */
    val presets: List<Long> = listOf(
        0xFFD44037, // 默认红
        0xFFFFB61E, // 藤黄
        0xFF789262, // 竹青
        0xFF57C3C2, // 石绿
        0xFF4C8DAE, // 群青
        0xFF003472, // 花青
        0xFF4A4266, // 黛
        0xFF9D2933, // 胭脂
    )

    /** 当前主色（ARGB，预设或拾色器自定义值统一存这里）。 */
    val accent = MutableStateFlow(0xFFD44037)

    /** 状态色是否随主色三角派生（iOS deriveStatusColor，默认关：状态色保持绿/橙/红语义）。 */
    val successDerived = MutableStateFlow(false)

    /**
     * 偏好灌入口（T4 PrefsView 双写接线用）：仅赋内存值，不落盘。
     * [custom]（拾色器）优先于 [color]（预设）；两者皆 null 时主色保持不变，
     * 便于「仅切换派生开关」的调用形态。
     */
    fun applyPrefs(color: Long?, custom: Long?, derived: Boolean) {
        val resolved = custom ?: color
        if (resolved != null) accent.value = resolved and 0xFFFFFFFFL
        successDerived.value = derived
    }

    /** 全局主色：浅色用原色，深色提亮增饱和 s+0.2、l=min(0.72, l+0.14)（iOS :60-65）。 */
    fun accentFor(dark: Boolean): Color {
        val base = hsl(accent.value)
        return if (!dark) {
            hslToColor(base)
        } else {
            hslToColor(Hsl(base.h, min(1.0, base.s + 0.2), min(0.72, base.l + 0.14)))
        }
    }

    /**
     * 成功/在线状态色：开关关闭时用固定双色资产（浅 rgb(0,152,48) / 深 rgb(39,184,80)，
     * 对齐 iOS SuccessGreen）；打开时随主色 +120° 三角派生（iOS :68-75，
     * 由基准色而非提亮后的 accent 计算）。
     */
    fun successFor(dark: Boolean): Color {
        if (!successDerived.value) {
            return if (dark) Color(0xFF27B850) else Color(0xFF009830)
        }
        val base = hsl(accent.value)
        val h = (base.h + 120.0).mod(360.0)
        return if (!dark) {
            hslToColor(Hsl(h, max(0.6, base.s), 0.32))
        } else {
            hslToColor(Hsl(h, max(0.5, base.s * 0.8), 0.45))
        }
    }

    /**
     * 行星装饰盘 8 色（iOS :78-91）：主色 ±120° 三角的 500/200 两档明度 + 两档深色。
     * 浅深模式共用（iOS 未做 dynamic 双色）。
     */
    fun planetTints(): List<Color> {
        val base = hsl(accent.value)
        val hues = listOf(
            base.h,
            (base.h + 120.0).mod(360.0),
            (base.h + 240.0).mod(360.0),
        )
        val colors = mutableListOf<Color>()
        for (h in hues) colors += hslToColor(Hsl(h, 0.65, 0.52))
        for (h in hues) colors += hslToColor(Hsl(h, 0.55, 0.68))
        colors += hslToColor(Hsl(hues[1], 0.70, 0.32))
        colors += hslToColor(Hsl(hues[2], 0.70, 0.32))
        return colors
    }

    /**
     * 行星磁贴装饰色：seed（planet id）djb2 哈希进 8 色装饰盘，
     * 同一 Planet 永远同色。哈希链路 = 先 % 1_000_003 再 % 表长
     * （iOS 963a96f 统一链路，与 PlanetIcon.djb2 共用——取模链路不同
     * 会导致同一行星两端颜色不同）。
     */
    fun planetTint(seed: String): Color {
        val tints = planetTints()
        val index = PlanetIcon.djb2(seed).mod(tints.size.toLong()).toInt()
        return tints[index]
    }

    // MARK: - 色彩数学（HSL，Compose 无现成 API，手写对齐 iOS 实现；
    // 设置页取色器复用故开放，iOS fe0db0a 同款 private→internal）

    /** h: 0..<360，s/l: 0..1。 */
    data class Hsl(val h: Double, val s: Double, val l: Double)

    fun hsl(argb: Long): Hsl {
        val r = ((argb shr 16) and 0xFF).toDouble() / 255.0
        val g = ((argb shr 8) and 0xFF).toDouble() / 255.0
        val b = (argb and 0xFF).toDouble() / 255.0
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val l = (maxC + minC) / 2
        if (maxC == minC) return Hsl(h = 0.0, s = 0.0, l = l)
        val d = maxC - minC
        val s = if (l > 0.5) d / (2 - maxC - minC) else d / (maxC + minC)
        val h = when (maxC) {
            r -> ((g - b) / d + (if (g < b) 6.0 else 0.0)) * 60
            g -> ((b - r) / d + 2) * 60
            else -> ((r - g) / d + 4) * 60
        }
        return Hsl(h = h, s = s, l = l)
    }

    fun hslToColor(c: Hsl): Color {
        val h = c.h / 60
        val chroma = (1 - kotlin.math.abs(2 * c.l - 1)) * c.s
        val x = chroma * (1 - kotlin.math.abs(h.mod(2.0) - 1))
        val m = c.l - chroma / 2
        val (r, g, b) = when {
            h < 1 -> Triple(chroma, x, 0.0)
            h < 2 -> Triple(x, chroma, 0.0)
            h < 3 -> Triple(0.0, chroma, x)
            h < 4 -> Triple(0.0, x, chroma)
            h < 5 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        return Color(red = (r + m).toFloat(), green = (g + m).toFloat(), blue = (b + m).toFloat())
    }
}

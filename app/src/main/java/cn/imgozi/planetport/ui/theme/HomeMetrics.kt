package cn.imgozi.planetport.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 首页/全 App 布局常量（iOS 侦查 §3 + HomeMetrics.swift:8）：
 * 8pt 网格；区块间距 28（身份区↔网络区，层级靠间距对比不靠阴影）。
 */
object HomeMetrics {
    /** 页面水平边距 20。 */
    val pageHMargin = 20.dp

    /** 卡片内边距 14。 */
    val cardPadding = 14.dp

    /** 卡片间距 12。 */
    val cardSpacing = 12.dp

    /** 区块间距 28（身份区 ↔ 网络区）。 */
    val sectionSpacing = 28.dp

    /** 首页顶部固定留白 88（覆盖状态栏的自绘头区）。 */
    val pageTopPadding = 88.dp

    /** 首页列表底部预留 148（避开 FAB）。 */
    val pageBottomReserve = 148.dp

    /** 添加页表单卡内边距 18。 */
    val formCardPadding = 18.dp

    /** 圆角：小控件 10。 */
    val radiusSmall = 10.dp

    /** 圆角：卡片 14。 */
    val radiusCard = 14.dp

    /** 圆角：大表单卡 / 图标磁贴 20。 */
    val radiusLarge = 20.dp

    /** 圆角：添加页预览占位 16。 */
    val radiusPreview = 16.dp

    /** 圆角：App logo 9。 */
    val radiusLogo = 9.dp

    /** 发丝分隔线 0.5pt。 */
    val hairline = 0.5.dp

    /** 状态圆点 6。 */
    val statusDotSize = 6.dp

    /** 图标磁贴 72。 */
    val iconTileSize = 72.dp

    /** moon/行星 badge 底 32。 */
    val badgeIconSize = 32.dp

    /** 通用触控热区下限 44。 */
    val minTouchTarget = 44.dp
}

/**
 * 等宽字阶（iOS §3：ID/IP/hex 全部 monospaced + tertiary，字号按场景三档）。
 * 不占用 Typography 槽位，由调用方与颜色自由组合。
 */
object PlanetFonts {
    /** 节点 ID 16pt 等宽（行星身份区）。 */
    val MonoId = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp)

    /** 正文级等宽 15（网络 ID、World ID）。 */
    val MonoBody = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp)

    /** caption 等宽 12（网络行 ID、分配地址）。 */
    val MonoCaption = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)

    /** caption2 等宽 11（紧凑标识）。 */
    val MonoCaption2 = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
}

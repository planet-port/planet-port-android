package cn.imgozi.planetport.ui.home

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.ui.planet.planetIconSymbol
import cn.imgozi.planetport.ui.planet.planetIconVector
import cn.imgozi.planetport.ui.theme.ThemeManager

/**
 * 首页磁贴图标入口。目录与哈希统一走 ui.planet.PlanetIconCatalog——其持久化值
 * 沿用 SF Symbol 名（与 iOS 数据兼容），是全仓唯一键空间权威（首页与设置页/
 * IconPickerSheet 共用，避免跨页查表失败静默回退）；本文件只补磁贴装饰色。
 * 官方行星不走此体系（整块磁贴见 ui.components.OfficialPlanetIcon）。
 */
object PlanetIcons {

    /** 自定义图标优先，否则按 id djb2 哈希确定性选取（对齐 iOS PlanetIcon.symbol）。 */
    fun symbol(planet: PlanetProfile): ImageVector = planetIconVector(planetIconSymbol(planet))

    /** 磁贴装饰色（8 色盘哈希分配，ThemeManager.planetTint 与 iOS tint(for:) 同构）。 */
    fun tint(planet: PlanetProfile): Color = ThemeManager.planetTint(planet.id)
}

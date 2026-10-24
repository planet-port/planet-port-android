package cn.imgozi.planetport.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Public
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.ui.theme.ThemeManager

/**
 * 行星图标目录（对齐 iOS PlanetIcon.swift：12 图标 + djb2 哈希确定性分配，
 * 同一行星永远同一图标与装饰色）。目录键持久化在 PlanetProfile.icon，
 * IconPickerSheet（T4）从本目录取值；官方行星不走此体系（整块磁贴见
 * PlanetCardView 的 OfficialPlanetIcon）。
 */
object PlanetIcons {

    /** 目录键（语义对齐 iOS catalog 顺序；icon 字段存键名而非符号名）。 */
    val catalog: List<String> = listOf(
        "globe", "network", "globe_asia", "sphere",
        "hexagon", "hexgrid", "cloud", "server",
        "antenna", "locator", "shareplay", "seal",
    )

    private val symbols: Map<String, ImageVector> = mapOf(
        "globe" to Icons.Outlined.Public,          // globe
        "network" to Icons.Outlined.Hub,           // network（节点网络）
        "globe_asia" to Icons.Filled.Public,       // globe.asia.australia.fill
        "sphere" to Icons.Filled.Circle,           // sphere
        "hexagon" to Icons.Filled.Hexagon,         // hexagon.fill
        "hexgrid" to Icons.Filled.GridView,        // circle.hexagongrid.fill
        "cloud" to Icons.Filled.Cloud,             // cloud.fill
        "server" to Icons.Filled.Dns,              // server.rack
        "antenna" to Icons.Filled.SettingsInputAntenna, // antenna.radiowaves.left.and.right
        "locator" to Icons.Outlined.Adjust,        // dot.circle.and.cursorarrow
        "shareplay" to Icons.Filled.ScreenShare,   // shareplay
        "seal" to Icons.Filled.Verified,           // seal.fill
    )

    /** 自定义图标键优先，否则按 id djb2 哈希确定性选取（对齐 iOS symbol(for:)）。 */
    fun symbol(planet: PlanetProfile): ImageVector =
        planet.icon?.let { symbols[it] } ?: symbols.getValue(catalog[hashIndex(planet)])

    /** 磁贴装饰色（8 色盘哈希分配，ThemeManager.planetTint 与 iOS tint(for:) 同构）。 */
    fun tint(planet: PlanetProfile): Color = ThemeManager.planetTint(planet.id)

    /** djb2（与 ThemeManager.planetTint 同一实现，保证图标与底色取同一索引）。 */
    private fun hashIndex(planet: PlanetProfile): Int {
        var hash = 5381L
        for (byte in planet.id.toByteArray(Charsets.UTF_8)) {
            hash = hash * 33L + (byte.toInt() and 0xFF)
        }
        return Math.floorMod(hash, catalog.size)
    }
}

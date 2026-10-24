package cn.imgozi.planetport.ui.planet

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PanoramaFishEye
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Verified
import cn.imgozi.planetport.store.PlanetProfile

/**
 * 行星图标目录（iOS PlanetIcon.swift:8-13 精选 12 符号）。
 *
 * 持久化值（PlanetProfile.icon）沿用 SF Symbol 名，与 iOS 数据完全兼容；
 * 显示侧映射为 Material 图标（视觉近似等价，iOS 侦查 §6 的映射纪律）。
 *
 * 注：T3 首页磁贴（ui/home/PlanetIcons.kt，计划内文件）需要同一目录与哈希——
 * 本文件是公开等价物，T3 合流时应复用此处而不是再写一份（见 task-4-report）。
 */
val PlanetIconCatalog: List<String> = listOf(
    "globe", "network", "globe.asia.australia.fill", "sphere",
    "hexagon.fill", "circle.hexagongrid.fill", "cloud.fill", "server.rack",
    "antenna.radiowaves.left.and.right", "dot.circle.and.cursorarrow",
    "shareplay", "seal.fill",
)

/** SF Symbol 名 → Material 图标（找不到的名字兜底 Public）。 */
fun planetIconVector(symbol: String): ImageVector = when (symbol) {
    "globe" -> Icons.Filled.Public
    "network" -> Icons.Filled.Lan
    "globe.asia.australia.fill" -> Icons.Filled.Language
    "sphere" -> Icons.Filled.PanoramaFishEye
    "hexagon.fill" -> Icons.Filled.Hexagon
    "circle.hexagongrid.fill" -> Icons.Filled.GridView
    "cloud.fill" -> Icons.Filled.Cloud
    "server.rack" -> Icons.Filled.Dns
    "antenna.radiowaves.left.and.right" -> Icons.Filled.SettingsInputAntenna
    "dot.circle.and.cursorarrow" -> Icons.Filled.MyLocation
    "shareplay" -> Icons.Filled.ScreenShare
    "seal.fill" -> Icons.Filled.Verified
    else -> Icons.Filled.Public
}

/** 该行星实际生效的图标符号（自定义优先，否则 djb2 哈希确定性分配——iOS PlanetIcon.symbol）。 */
fun planetIconSymbol(planet: PlanetProfile): String {
    planet.icon?.let { return it }
    var hash = 5381L
    for (byte in planet.id.toByteArray(Charsets.UTF_8)) {
        hash = hash * 33L + (byte.toInt() and 0xFF)
    }
    val index = Math.floorMod(hash, PlanetIconCatalog.size.toLong()).toInt()
    return PlanetIconCatalog[index]
}

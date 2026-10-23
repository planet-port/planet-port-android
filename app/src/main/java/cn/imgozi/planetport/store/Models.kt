package cn.imgozi.planetport.store

import kotlinx.serialization.Serializable

/** DNS 处理模式（对齐 iOS DNSMode：network = 跟随网络下发，custom = 用户自定义）。 */
@Serializable
enum class DnsMode {
    none, network, custom,
}

/** Planet 来源（对齐 iOS PlanetKind）。 */
@Serializable
enum class PlanetKind {
    official, custom,
}

/** 一套可独立切换的 Planet 配置。Network 与 Moon 均通过 planetId 归属于它。 */
@Serializable
data class PlanetProfile(
    val id: String,
    val name: String,
    val kind: PlanetKind,
    /** 私有 Planet 在 planets/ 下的文件名；官方 Planet 使用核心内置默认。 */
    val fileName: String? = null,
    /** 自定义图标标识；null 时按 id 哈希自动生成（P4）。 */
    val icon: String? = null,
    /** 该行星下本机节点 ID 缓存（隧道运行时回写，离线可显示；P2）。 */
    val lastNodeId: String? = null,
) {
    companion object {
        const val OFFICIAL_ID = "official"
        val OFFICIAL = PlanetProfile(
            id = OFFICIAL_ID,
            name = "ZeroTier 官方",
            kind = PlanetKind.official,
            fileName = null,
        )
    }
}

/** 隶属于某个 Planet 的网络条目。 */
@Serializable
data class NetworkEntry(
    /** 16 位十六进制网络 ID。 */
    val networkId: String,
    val networkName: String,
    /** 上次会话是否处于开启状态（应用启动 + 隧道未连接时整体复位）。 */
    val lastActivated: Boolean,
    /** 是否把该网段路由交给 ZeroTier（对齐 iOS routeViaZeroTier）。 */
    val routeViaZeroTier: Boolean,
    val dnsMode: DnsMode,
    val customDns: List<String>,
    /** 归属 Planet；null 仅为 JSON 兼容保留，写入时总会被盖戳。 */
    val planetId: String? = null,
    /** 最近一次冲突原因码（"address"/"subnet"），再次开启时清除；null = 无记录。 */
    val lastConflict: String? = null,
) {
    val id: String get() = networkId
}

/** Moon 节点条目。 */
@Serializable
data class MoonEntry(
    val worldId: ULong,
    val seed: ULong,
    /** 是否来自 .moon 文件导入（对齐 iOS fromFile）。 */
    val fromFile: Boolean,
    val planetId: String? = null,
) {
    val id: String get() = formatWorldId(worldId)

    companion object {
        /** 16 位小写十六进制（对齐 iOS %016llx；负 Long 的补码十六进制恰好是 64 位无符号表示）。 */
        fun formatWorldId(worldId: ULong): String = "%016x".format(worldId.toLong())
    }
}

/** prefs.json 模型。activePlanetId = null 表示从未激活过任何 Planet。 */
@Serializable
data class AppPrefs(
    val selectedPlanetId: String = PlanetProfile.OFFICIAL_ID,
    val activePlanetId: String? = null,
    /** 数据面禁用 IPv6（对齐 iOS network_disable_ipv6；默认 false） */
    val disableIpv6: Boolean = false,
)

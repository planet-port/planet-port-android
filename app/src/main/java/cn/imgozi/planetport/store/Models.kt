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
    /** 自定义动物图标 key（如 "cat"；有效值永久生效，null/旧值按名称哈希回退）。 */
    val icon: String? = null,
    /** 自定义底色 "#RRGGBB"；null/解析失败按 id 哈希回退主题装饰盘。 */
    val iconColor: String? = null,
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
    // ---- P4 偏好页字段（对齐 iOS PrefsView）。全部带默认值：旧 prefs.json 缺字段
    // 按 kotlinx.serialization 默认值语义反序列化，旧文件无需迁移 ----
    /** 主题主色（ARGB）。null = 预设第 0 色（官方红）；自定义色存 [themeColorCustom]。 */
    val themeColor: Long? = null,
    /** 拾色器自定义主色（ARGB）；非 null 时优先于 [themeColor]。 */
    val themeColorCustom: Long? = null,
    /** 状态成功色是否随主色派生（对齐 iOS successDerived；默认 false，状态色保持语义色）。 */
    val successDerived: Boolean = false,
    /** 界面语言（"zh"/"zh-rTW"/"en"）。null = 跟随系统。 */
    val language: String? = null,
    /** 首页背景图是否已设置（图本体存应用私有目录，P4 落地）。 */
    val backgroundImage: Boolean = false,
    /** 背景图重力视差动画开关（对齐 iOS parallax；默认 true，无背景图时无效）。 */
    val parallax: Boolean = true,
    /** 同时连接网络软上限（对齐 iOS maxNetworks，2..6；默认 2）。 */
    val maxNetworks: Int = 2,
    /** 连接超时（秒，Android 独有偏好，10..600；默认 30）。到点仍未 online 进入
     *  复核重试链路（见 ui/StatusText.kt）。 */
    val connectTimeoutSec: Int = 30,
    /** 按需连接（对齐 iOS prefConnectOnDemand/NEOnDemandRuleConnect；默认 false）。
     *  iOS 语义=系统维持隧道持久在线；Android 无系统级钩子，落地为 app 启动时
     *  隧道未跑且有已连接标记则自动拉起（HomeViewModel.init 消费），开启时不做
     *  启动复位（resetAllNetworkActivation 跳过）。 */
    val connectOnDemand: Boolean = false,
)

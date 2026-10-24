package cn.imgozi.planetport.ui.planet

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.ui.theme.ThemeManager
import kotlin.random.Random

/**
 * 行星图标体系（iOS PlanetIcon.swift 1:1）：动物矢量图标
 * （drawable/animal_<key>，白色 VectorDrawable）。
 * 图标 key 用户设置过则永久生效；否则按名称 djb2 哈希确定性选动物
 * （名称不变则图标不变，同名行星同图标）。底色自定义 hex 优先，
 * 否则按 id 哈希取主题 8 色装饰盘。官方行星与私有行星同规则，无特判。
 *
 * catalog 顺序与 iOS 逐字一致；djb2 必须逐字节 UTF-8、溢出回绕、
 * 先 % 1_000_003 再 % 表长（iOS 963a96f 统一链路）——两端任何一环
 * 不同都会导致同一行星在两端显示不同动物/颜色。
 */
object PlanetIcon {

    /** 全部动物 key，与 drawable/animal_<key>.xml 一一对应（92 个，iOS 逐字拷贝）。 */
    val catalog: List<String> = listOf(
        "ant", "anteater", "armadillo", "badger", "bat", "beaver", "bee", "bird",
        "bison", "boar", "bug", "bull", "butterfly", "camel", "cat", "cobra",
        "cow", "crab", "crow", "deer", "dinosaur-rex", "dodo", "dog", "dolphin",
        "donkey", "dove", "dragon", "dragonfly", "duck", "eel", "elephant", "fish",
        "flamingo", "fox", "frog", "gecko", "goat", "goose", "gorilla", "hedgehog",
        "heron", "hippo", "horse", "hummingbird", "ibis", "jellyfish", "kangaroo",
        "kiwi-bird", "koala", "ladybug", "lion", "locust", "mammoth", "mole", "monkey",
        "mosquito", "octopus", "ostrich", "otter", "owl", "panda", "penguin", "pig",
        "piranha", "polar-bear", "rabbit", "rattlesnake", "raven", "rodent", "rooster",
        "scorpion", "seahorse", "seal", "shark", "sheep", "shrimp", "sloth", "snail",
        "snake", "sperm-whale", "spider", "squid", "squirrel", "swallow", "swan", "tiger",
        "tortoise", "turkey", "turtle", "unicorn", "vulture", "worm",
    )

    /** 动物 key：有效自定义值永久生效；null/旧值无效（SF Symbol 名等历史
     * 持久化值）按名称哈希回退。 */
    fun animalKey(planet: PlanetProfile): String {
        planet.icon?.let { if (it in catalog) return it }
        return catalog[djb2(planet.name).mod(catalog.size.toLong()).toInt()]
    }

    /** 图标 drawable 资源 id（animal-<key> 下划线化；0 时兜底首个防崩溃）。 */
    fun drawableId(context: Context, key: String): Int = drawableCache.getOrPut(key) {
        val id = context.resources.getIdentifier(
            "animal_" + key.replace('-', '_'), "drawable", context.packageName,
        )
        if (id != 0) id else android.R.drawable.ic_menu_help
    }

    private val drawableCache = HashMap<String, Int>()

    /** 底色：自定义 hex 优先（解析失败视为未设置），否则按 id 哈希回退装饰盘。 */
    fun tint(planet: PlanetProfile): Color {
        planet.iconColor?.let { hex -> parseHex(hex)?.let { return it } }
        return ThemeManager.planetTint(planet.id)
    }

    /** 算法回退底色 hex（按 id 哈希取当前色表，与 tint 同链路）。 */
    fun fallbackColorHex(planet: PlanetProfile): String {
        val tints = ThemeManager.planetTints()
        val color = tints[djb2(planet.id).mod(tints.size.toLong()).toInt()]
        return "#%06x".format(color.toArgb() and 0xFFFFFF)
    }

    /** 有效底色 hex：自定义优先（解析成功），否则算法回退色；planet 为 null 时 null。 */
    fun effectiveColorHex(planet: PlanetProfile?): String? {
        planet ?: return null
        planet.iconColor?.let { if (parseHex(it) != null) return it }
        return fallbackColorHex(planet)
    }

    /** 随机底色 hex：随机色相 + 中低亮度域（饱和度 0.45–0.85、亮度 0.32–0.55），
     * 白色图形对比度有保证。 */
    fun randomColorHex(): String = hex(
        hue = Random.nextDouble(0.0, 360.0),
        saturation = Random.nextDouble(0.45, 0.85),
        lightness = Random.nextDouble(0.32, 0.55),
    )

    /** 随机动物：优先排除本地化名与现有行星名相同的 key（名称建议与图标同源）。 */
    fun randomAnimal(context: Context, excludingNames: Set<String> = emptySet()): String {
        val candidates = catalog.filter { localizedName(context, it) !in excludingNames }
        return (candidates.ifEmpty { catalog }).random(Random.Default)
    }

    /** 动物本地化名（animal_<key> 查表）；查表失败回显 "animal.<key>" 原始 key。 */
    fun localizedName(context: Context, key: String): String {
        val id = context.resources.getIdentifier(
            "animal_" + key.replace('-', '_'), "string", context.packageName,
        )
        return if (id != 0) context.getString(id) else "animal.$key"
    }

    // MARK: - 底层工具

    /** HSL → "#rrggbb"（小写，iOS 同格式）。h∈[0,360) s,l∈[0,1]。 */
    private fun hex(hue: Double, saturation: Double, lightness: Double): String {
        val c = (1 - kotlin.math.abs(2 * lightness - 1)) * saturation
        val hp = hue / 60
        val x = c * (1 - kotlin.math.abs(hp.mod(2.0) - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = lightness - c / 2
        fun channel(v: Double): Int = (((v + m) * 255).toInt()).coerceIn(0, 255)
        return "#%02x%02x%02x".format(channel(r1), channel(g1), channel(b1))
    }

    /** "#RRGGBB" → Color；非法输入 null（iOS Color(hex:) 同语义）。 */
    fun parseHex(hex: String): Color? {
        val s = hex.removePrefix("#")
        if (s.length != 6) return null
        val v = s.toLongOrNull(16) ?: return null
        return Color((0xFF000000L or v).toInt())
    }

    /** djb2 哈希（UTF-8 逐字节、溢出回绕），返回 hash % 1_000_003。
     * ThemeManager.planetTint 同链路共用。 */
    internal fun djb2(string: String): Long {
        var hash = 5381L
        for (byte in string.toByteArray(Charsets.UTF_8)) {
            hash = hash * 33L + (byte.toInt() and 0xFF)
        }
        return hash % 1_000_003L
    }
}

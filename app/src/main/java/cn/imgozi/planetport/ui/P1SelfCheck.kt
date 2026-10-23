package cn.imgozi.planetport.ui

import android.content.Context
import cn.imgozi.planetport.store.AppConfigStore
import cn.imgozi.planetport.store.MoonEntry
import cn.imgozi.planetport.store.MoonImporter
import cn.imgozi.planetport.store.PlanetImporter
import cn.imgozi.planetport.store.PlanetKind
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StorePaths
import java.io.File

/**
 * P1 数据层真机自检：在 cacheDir 隔离目录跑「引导 → 导入 → 切换 → 持久化 → 级联删除」
 * 全链路，不污染真实配置。P2 起随正式 UI 落地移除。
 */
internal object P1SelfCheck {

    /** 合成 planet：0x01 + worldId 0x0123456789abcdef（大端）+ 时间戳 + 填充，共 17 字节。 */
    private val syntheticPlanet = byteArrayOf(0x01) + byteArrayOf(
        0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xab.toByte(), 0xcd.toByte(), 0xef.toByte(),
        0x00, 0x00, 0x17, 0x6e, 0x55, 0x68, 0x4b, 0x00, // 时间戳常量即可
    )

    /** 合成 moon：0x7f + worldId 0xfedcba9876543210（大端），共 9 字节。 */
    private val syntheticMoon = byteArrayOf(0x7f) + byteArrayOf(
        0xfe.toByte(), 0xdc.toByte(), 0xba.toByte(), 0x98.toByte(), 0x76.toByte(), 0x54.toByte(), 0x32.toByte(), 0x10.toByte(),
    )

    suspend fun run(context: Context): List<String> {
        val lines = mutableListOf<String>()
        fun line(ok: Boolean, label: String) {
            lines += (if (ok) "✓ " else "✗ ") + label
        }

        val base = File(context.cacheDir, "p1-selfcheck").apply { deleteRecursively(); mkdirs() }
        val store = AppConfigStore(StorePaths(base))
        val planetImporter = PlanetImporter(store)
        val moonImporter = MoonImporter(store)

        // 1. 首启引导：只有官方 Planet
        val boot = store.readPlanets()
        line(boot.size == 1 && boot[0].id == PlanetProfile.OFFICIAL_ID, "planets 首启引导（${boot.size} 个，官方保底）")

        // 2. planet 头解析（纯函数）
        val worldId = PlanetImporter.worldIdOf(syntheticPlanet)
        line(worldId == 0x0123456789abcdefu, "planet 头解析 worldId=${worldId?.let { MoonEntry.formatWorldId(it) }}")

        // 3. planet 导入 + 落盘回读 + 持久化
        val profile = planetImporter.importPlanetData(syntheticPlanet, "自检行星")
        val info = planetImporter.inspectPlanetFile(profile)
        val afterImport = store.readPlanets()
        line(
            afterImport.size == 2 && afterImport[0].id == PlanetProfile.OFFICIAL_ID &&
                info != null && info.worldId == worldId && info.fileSize == syntheticPlanet.size,
            "planet 导入与回读（official 排最前，文件 ${info?.fileSize} 字节）",
        )

        // 4. moon 导入（落盘所选 Planet 的 moons.d/）
        val moon = moonImporter.importMoonData(syntheticMoon)
        val moonFile = File(store.paths.planetStateDir(PlanetProfile.OFFICIAL_ID), "moons.d/${moon.id}.moon")
        line(moon.worldId == 0xfedcba9876543210u && moonFile.exists(), "moon 导入落盘 id=${moon.id}")

        // 5. 指针切换：select 静默、activate 生效
        store.selectPlanet(profile.id)
        val selected = store.selectedPlanetId()
        val changed = store.activatePlanet(profile.id)
        val active = store.activePlanetId()
        line(selected == profile.id && changed && active == profile.id && store.hasActivatedPlanet(), "select/activate 切换（$selected / $active）")

        // 6. 级联删除与回退
        store.deletePlanet(profile)
        val afterDelete = store.readPlanets()
        line(
            afterDelete.size == 1 && store.selectedPlanetId() == PlanetProfile.OFFICIAL_ID &&
                store.activePlanetId() == PlanetProfile.OFFICIAL_ID &&
                !File(store.paths.planetsDir, profile.fileName!!).exists(),
            "删除级联回退 official",
        )

        return lines
    }
}

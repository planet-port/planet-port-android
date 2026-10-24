package cn.imgozi.planetport.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 多 Planet 文件导入。每份私有 Planet 独立保存，不覆盖其它配置。 */
class PlanetImporter(private val store: AppConfigStore) {

    /**
     * 导入失败异常。本类无 Context 无法本地化，message 为稳定英文标识串，
     * 用户可见文案由消费侧（HomeViewModel/PlanetSettingsViewModel）按子类类型
     * 映射字符串资源，标识串仅作兜底，正常不会直显用户。
     */
    sealed class ImportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class BadHeader : ImportError("planet_bad_header")
        class EmptyName : ImportError("planet_empty_name")
        class DownloadFailed(cause: Throwable? = null) : ImportError("planet_download_failed", cause)

        /** 下载体超过大小上限（Content-Length 超限或流读越界），供 UI 单独提示 */
        class TooLarge : ImportError("planet_too_large")

        /** 落盘失败（临时文件 rename 失败），文案由消费侧映射。 */
        class WriteFailed(cause: Throwable? = null) : ImportError("planet_write_failed", cause)
    }

    /** 校验并落盘一份 planet 文件，注册为新的自定义 Planet。 */
    suspend fun importPlanetData(
        data: ByteArray,
        name: String,
        icon: String? = null,
        iconColor: String? = null,
    ): PlanetProfile {
        if (data.isEmpty() || data[0] != PLANET_TYPE_BYTE) throw ImportError.BadHeader()
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) throw ImportError.EmptyName()

        val id = java.util.UUID.randomUUID().toString().lowercase()
        val fileName = "$id.planet"
        withContext(Dispatchers.IO) {
            val dir = store.paths.planetsDir.apply { mkdirs() }
            val target = File(dir, fileName)
            val tmp = File(dir, "$fileName.tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw ImportError.WriteFailed()
            }
        }

        val profile = PlanetProfile(
            id = id, name = normalizedName, kind = PlanetKind.custom,
            fileName = fileName, icon = icon, iconColor = iconColor,
        )
        // 注册表读-追加-写整段持域锁，防止并发写丢更新
        store.mutate {
            store.writePlanetsLocked(store.readPlanets() + profile)
        }
        return profile
    }

    suspend fun importPlanetFromURL(urlString: String, name: String): PlanetProfile =
        importPlanetData(downloadPlanetFile(urlString), name)

    /**
     * 仅下载文件内容，不校验、不落库（供“先就绪、后添加”交互使用，P4）。
     *
     * 下载加固：Content-Length 缺失或超过 [MAX_PLANET_BYTES] 直接拒绝；并做有界
     * 流复制（读至上限即断）——官方 planet 仅数 KB，上限防误指大文件与恶意超大
     * 响应，有界复制防服务器谎报声明长度。
     */
    suspend fun downloadPlanetFile(urlString: String): ByteArray = withContext(Dispatchers.IO) {
        val url = try {
            URL(urlString)
        } catch (e: Exception) {
            throw ImportError.DownloadFailed(e)
        }
        try {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            try {
                if (conn.responseCode != 200) throw ImportError.DownloadFailed()
                val declared = conn.contentLengthLong
                if (declared < 0 || declared > MAX_PLANET_BYTES) throw ImportError.TooLarge()
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(declared.toInt())
                    val buf = ByteArray(64 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PLANET_BYTES) throw ImportError.TooLarge()
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: ImportError) {
            throw e
        } catch (e: Exception) {
            throw ImportError.DownloadFailed(e)
        }
    }

    suspend fun rename(profile: PlanetProfile, name: String) {
        val normalizedName = name.trim()
        if (normalizedName.isEmpty()) throw ImportError.EmptyName()
        store.mutate {
            val profiles = store.readPlanets()
            val index = profiles.indexOfFirst { it.id == profile.id }
            if (index < 0) return@mutate
            store.writePlanetsLocked(
                profiles.toMutableList().apply { this[index] = this[index].copy(name = normalizedName) }
            )
        }
    }

    /** 更新自定义图标与底色（iOS updateIcon(icon:color:) 同签名语义）。 */
    suspend fun updateIcon(profile: PlanetProfile, icon: String?, color: String?) {
        store.mutate {
            val profiles = store.readPlanets()
            val index = profiles.indexOfFirst { it.id == profile.id }
            if (index < 0) return@mutate
            store.writePlanetsLocked(
                profiles.toMutableList().apply {
                    this[index] = this[index].copy(icon = icon, iconColor = color)
                }
            )
        }
    }

    /** 已存在的 World ID 集合（含官方内置），供添加时查重。 */
    suspend fun existingWorldIds(): Set<ULong> {
        val ids = mutableSetOf(ZT_WORLD_ID_EARTH)
        for (profile in store.readPlanets()) {
            inspectPlanetFile(profile)?.let { ids.add(it.worldId) }
        }
        return ids
    }

    /** 从 .planet 文件尽力解读只读信息：byte0 类型 + 8B 大端 worldId + 8B 大端时间戳（需 ≥17B）。 */
    suspend fun inspectPlanetFile(profile: PlanetProfile): PlanetFileInfo? = withContext(Dispatchers.IO) {
        val fileName = profile.fileName ?: return@withContext null
        val data = runCatching { File(store.paths.planetsDir, fileName).readBytes() }
            .getOrNull() ?: return@withContext null
        if (data.size < 17 || data[0] != PLANET_TYPE_BYTE) return@withContext null
        PlanetFileInfo(
            worldId = readBigEndianUInt64(data, 1),
            timestamp = readBigEndianUInt64(data, 9),
            fileSize = data.size,
        )
    }

    data class PlanetFileInfo(val worldId: ULong, val timestamp: ULong, val fileSize: Int)

    companion object {
        /** planet 文件类型标记（ZT world 二进制头 byte0）。 */
        const val PLANET_TYPE_BYTE: Byte = 0x01

        /** 下载体大小上限（8 MiB）：官方 planet 数 KB 量级，裕量充足。 */
        const val MAX_PLANET_BYTES: Int = 8 * 1024 * 1024

        /** ZT_WORLD_ID_EARTH：官方 Planet 的内置 World ID。 */
        const val ZT_WORLD_ID_EARTH: ULong = 149604618u

        /** 从 planet 文件数据提取 World ID（byte0 类型 + 8 字节大端）。 */
        fun worldIdOf(data: ByteArray): ULong? {
            if (data.size < 9 || data[0] != PLANET_TYPE_BYTE) return null
            return readBigEndianUInt64(data, 1)
        }

        internal fun readBigEndianUInt64(data: ByteArray, offset: Int): ULong {
            var value = 0uL
            for (i in offset until offset + 8) {
                value = (value shl 8) or data[i].toUByte().toULong()
            }
            return value
        }
    }
}

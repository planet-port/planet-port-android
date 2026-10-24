package cn.imgozi.planetport.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Moon 文件导入。校验 .moon 数据并落盘到当前选中 Planet 独立的 moons.d/ 目录。 */
class MoonImporter(private val store: AppConfigStore) {

    /**
     * 导入失败异常。本类无 Context 无法本地化，message 为稳定英文标识串，
     * 用户可见文案由消费侧（MoonViewModel）按子类类型映射字符串资源，
     * 标识串仅作兜底，正常不会直显用户。
     */
    sealed class ImportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class BadHeader : ImportError(BAD_HEADER_MESSAGE)
        class ReadFailed(cause: Throwable? = null) : ImportError(READ_FAILED_MESSAGE, cause)
        class TooLarge : ImportError(TOO_LARGE_MESSAGE)

        /** 落盘失败（临时文件 rename 失败），文案由消费侧映射。 */
        class WriteFailed(cause: Throwable? = null) : ImportError(WRITE_FAILED_MESSAGE, cause)

        companion object {
            const val BAD_HEADER_MESSAGE = "moon_bad_header"
            const val READ_FAILED_MESSAGE = "moon_read_failed"
            const val TOO_LARGE_MESSAGE = "moon_too_large"
            const val WRITE_FAILED_MESSAGE = "moon_write_failed"
        }
    }

    /**
     * 校验并落盘。文件格式：首字节 0x7f + 8 字节大端 worldId。
     * 返回的 MoonEntry 由调用方经 AppConfigStore.writeMoons 持久化（对齐 iOS 职责划分）。
     */
    suspend fun importMoonData(data: ByteArray): MoonEntry {
        if (data.size > MAX_MOON_BYTES) throw ImportError.TooLarge()
        if (data.size < 9) throw ImportError.ReadFailed()
        if (data[0] != MOON_TYPE_BYTE) throw ImportError.BadHeader()
        val worldId = PlanetImporter.readBigEndianUInt64(data, 1)

        val planetId = store.selectedPlanetId()
        withContext(Dispatchers.IO) {
            val dir = File(store.paths.planetStateDir(planetId), "moons.d")
            dir.mkdirs()
            val target = File(dir, MoonEntry.formatWorldId(worldId) + ".moon")
            val tmp = File(dir, target.name + ".tmp")
            tmp.writeBytes(data)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw ImportError.WriteFailed()
            }
        }
        return MoonEntry(worldId = worldId, seed = 0u, fromFile = true, planetId = planetId)
    }

    companion object {
        /** moon 文件类型标记。 */
        const val MOON_TYPE_BYTE: Byte = 0x7f

        /** 导入体大小上限（8 MiB）：与 planet 下载上限一致，官方 moon 数 KB 量级，裕量充足。 */
        const val MAX_MOON_BYTES: Int = 8 * 1024 * 1024
    }
}

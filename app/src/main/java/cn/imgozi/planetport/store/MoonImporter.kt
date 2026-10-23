package cn.imgozi.planetport.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Moon 文件导入。校验 .moon 数据并落盘到当前选中 Planet 独立的 moons.d/ 目录。 */
class MoonImporter(private val store: AppConfigStore) {

    sealed class ImportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class BadHeader : ImportError("moon 文件头无效（首字节应为 0x7f）")
        class ReadFailed(cause: Throwable? = null) : ImportError("moon 数据读取失败", cause)
    }

    /**
     * 校验并落盘。文件格式：首字节 0x7f + 8 字节大端 worldId。
     * 返回的 MoonEntry 由调用方经 AppConfigStore.writeMoons 持久化（对齐 iOS 职责划分）。
     */
    suspend fun importMoonData(data: ByteArray): MoonEntry {
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
                throw IllegalStateException("moon 文件写入失败: ${target.name}")
            }
        }
        return MoonEntry(worldId = worldId, seed = 0u, fromFile = true, planetId = planetId)
    }

    companion object {
        /** moon 文件类型标记。 */
        const val MOON_TYPE_BYTE: Byte = 0x7f
    }
}

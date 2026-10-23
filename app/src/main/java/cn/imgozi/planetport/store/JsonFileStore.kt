package cn.imgozi.planetport.store

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * 四份 JSON 配置共用的文件读写设施：
 * - 单一 Mutex 串行化全部配置 IO（写频低，全局锁最简单且足够）；
 * - 写 = 序列化到 <name>.tmp 后 rename 原子替换（序列化失败直接抛，不产生半截文件）；
 * - 读损坏 = 隔离为 <name>.corrupt（覆盖旧隔离）并返回 null——保留现场供排查，
 *   不自动重建；调用方决定内存默认值，文件由下一次真实写回重建。
 * 读/写 try 块内不得引入挂起点（catch(Exception) 会吞 CancellationException 的前提）。
 */
internal class JsonFileStore(private val json: Json) {

    private val mutex = Mutex()

    suspend fun <T> read(file: File, serializer: KSerializer<T>): T? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            try {
                json.decodeFromString(serializer, file.readText())
            } catch (e: Exception) {
                quarantine(file, e)
                null
            }
        }
    }

    suspend fun <T> write(file: File, value: T, serializer: KSerializer<T>): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val text = json.encodeToString(serializer, value)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("配置写入失败（rename）: ${file.name}")
            }
        }
    }

    /** 损坏隔离：改名保留现场；隔离文件已存在时覆盖（只保留最近一次现场）。 */
    private fun quarantine(file: File, cause: Exception) {
        val target = File(file.parentFile, file.name + ".corrupt")
        runCatching {
            if (target.exists()) target.delete()
            file.renameTo(target)
        }
        Log.w(TAG, "配置损坏已隔离为 ${target.name}", cause)
    }

    private companion object {
        const val TAG = "PlanetPortStore"
    }
}

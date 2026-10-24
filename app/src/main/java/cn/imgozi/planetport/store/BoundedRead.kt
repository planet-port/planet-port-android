package cn.imgozi.planetport.store

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 有界读取超限异常：[readBounded] 读入字节数超过上限时抛出。
 * 本类型不承载用户文案（与导入器异常同范式），消费侧按业务映射既有
 * error_planet_tooLarge / error_moon_tooLarge / error_bg_tooLarge 等资源。
 */
class StreamTooLargeException(maxBytes: Int) : IOException("stream exceeds $maxBytes bytes limit")

/**
 * 流式限量整读：每次 64KiB 分块累计，读入量超过 [maxBytes] 立即抛
 * [StreamTooLargeException] 终止——先于整读封顶内存，防不可信来源
 * （用户所选文件 / 相册资源 / 远程下载体）谎报或无界长度撑爆堆。
 */
fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val out = ByteArrayOutputStream(minOf(maxBytes, READ_CHUNK))
    val chunk = ByteArray(READ_CHUNK)
    var read: Int
    while (read(chunk).also { read = it } != -1) {
        if (out.size() + read > maxBytes) throw StreamTooLargeException(maxBytes)
        out.write(chunk, 0, read)
    }
    return out.toByteArray()
}

private const val READ_CHUNK = 64 * 1024

package cn.imgozi.planetport.qrtransfer

import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 压缩容器：4 字节小端原始长度 + raw deflate 流（iOS COMPRESSION_ZLIB 同为
 * 无 zlib 头的 raw deflate，跨端字节互解）。自带长度使得解压侧可以直接分配
 * 目标缓冲，不依赖外部传尺寸。
 */
internal object Deflate {
    fun compress(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            // 不可压缩数据的膨胀上界（块开销）给足余量
            val buf = ByteArray(data.size + data.size / 64 + 64)
            val n = deflater.deflate(buf)
            if (n <= 0) return null
            val len = data.size
            return ByteArray(4 + n).also {
                it[0] = (len and 0xFF).toByte()
                it[1] = ((len shr 8) and 0xFF).toByte()
                it[2] = ((len shr 16) and 0xFF).toByte()
                it[3] = ((len shr 24) and 0xFF).toByte()
                System.arraycopy(buf, 0, it, 4, n)
            }
        } finally {
            deflater.end()
        }
    }

    fun decompress(container: ByteArray): ByteArray? {
        if (container.size <= 4) return null
        val len = (container[0].toInt() and 0xFF) or
            ((container[1].toInt() and 0xFF) shl 8) or
            ((container[2].toInt() and 0xFF) shl 16) or
            ((container[3].toInt() and 0xFF) shl 24)
        if (len <= 0 || len >= 16 * 1024 * 1024) return null
        val inflater = Inflater(true)
        return try {
            inflater.setInput(container, 4, container.size - 4)
            val out = ByteArray(len)
            val n = inflater.inflate(out)
            if (n != len || !inflater.finished()) null else out
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}

package cn.imgozi.planetport.qrtransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS QRTransferFrameTests 1:1 移植：帧协议编解码与容量探测。 */
class FrameTest {

    private fun sampleData(size: Int): ByteArray =
        ByteArray(size) { ((it * 31 + 7) % 256).toByte() }

    /** xorshift 伪随机字节，近不可压缩。sampleData 以 256 字节为周期，
     * deflate 可压到几百字节，无法触发 tooLarge。 */
    private fun noisyData(size: Int): ByteArray {
        var state = 0x9E3779B97F4A7C15uL
        return ByteArray(size) {
            state = state xor (state shl 13)
            state = state xor (state shr 7)
            state = state xor (state shl 17)
            state.toByte()
        }
    }

    @Test
    fun probePrefixMatchesHeaderLen() {
        assertEquals(QRTransfer.headerLen, ChunkPlanner.probePrefix.length)
    }

    @Test
    fun singleFrameRoundTrip() {
        val data = sampleData(300)
        val frames = QRTransfer.encode(data, maxModules = 117)
        assertEquals(1, frames.size)
        assertTrue(QRRender.fits(frames[0], maxModules = 117))
        assertTrue(data.contentEquals(QRTransfer.decode(frames.mapNotNull { QRTransfer.parse(it) })))
    }

    @Test
    fun multiFrameUniformPaddedAndRestored() {
        val data = sampleData(2000)
        // 53 模块（v9）小容量强制多帧
        val frames = QRTransfer.encode(data, maxModules = 53)
        assertTrue(frames.size > 1)
        // 每帧数据段字符数严格相等（无法整除时末帧补 '0' 对齐）
        val chunkLens = frames.map { it.split("|", limit = 6)[5].length }.toSet()
        assertEquals(1, chunkLens.size)
        // 每一帧真实形态都不得超过容量上限（探测失真回归防护）
        frames.forEach {
            assertTrue("帧超出 53 模块：长度 ${it.length} 字符", QRRender.fits(it, maxModules = 53))
        }
        // 剥补位后完整还原
        assertTrue(data.contentEquals(QRTransfer.decode(frames.mapNotNull { QRTransfer.parse(it) })))
    }

    @Test
    fun tooLargeThrows() {
        var thrown: QRTransferException? = null
        try {
            QRTransfer.encode(noisyData(200_000), maxModules = 53)
        } catch (e: QRTransferException) {
            thrown = e
        }
        assertTrue(thrown is QRTransferException.TooLarge)
    }

    @Test
    fun parseRejectsBadInput() {
        assertNull(QRTransfer.parse("hello world"))
        assertNull(QRTransfer.parse("ZTQR1|02|03|deadbeef|00123|xx")) // index > total
        assertNull(QRTransfer.parse("ZTQR1|2|01|deadbeef|00123|xx"))  // 位数不足
        assertNull(QRTransfer.parse("ZTQR1|02|01|deadbeef|0123|xx"))  // len 位数不足
    }

    @Test
    fun decodeDetectsMissingAndCorrupt() {
        val data = sampleData(500)
        val frames = QRTransfer.encode(data, maxModules = 53)
        val parsed = frames.mapNotNull { QRTransfer.parse(it) }
        // 缺帧 → incomplete
        var thrown: QRTransferException? = null
        try {
            QRTransfer.decode(parsed.dropLast(1))
        } catch (e: QRTransferException) {
            thrown = e
        }
        assertTrue(thrown is QRTransferException.Incomplete)
        // 篡改校验和 → corrupt（CRC 兜底拦截）
        val tampered = QRFrame(parsed[0].total, parsed[0].index, 0u, parsed[0].length, parsed[0].payload)
        thrown = null
        try {
            QRTransfer.decode(parsed.drop(1) + tampered)
        } catch (e: QRTransferException) {
            thrown = e
        }
        assertTrue(thrown is QRTransferException.Corrupt)
    }
}

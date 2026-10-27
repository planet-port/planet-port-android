package cn.imgozi.planetport.qrtransfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS QRTransferCodecTests 1:1 移植：编解码往返与已知向量。 */
class CodecTest {

    @Test
    fun base45KnownVectors() {
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69", Base45.encode("He".toByteArray()))
    }

    @Test
    fun base45RoundTrip() {
        // 覆盖奇偶长度与较长数据
        for (size in intArrayOf(1, 2, 3, 31, 32, 100, 1024)) {
            val data = ByteArray(size) { ((it * 7 + 13) % 256).toByte() }
            assertArrayEquals(data, Base45.decode(Base45.encode(data)))
        }
    }

    @Test
    fun base45RejectsBadInput() {
        assertNull(Base45.decode("abc|")) // '|' 不在字母表
        assertNull(Base45.decode("A"))    // 落单 1 字符不合法
        assertNull(Base45.decode("::::")) // 解出值 > 0xFFFF
    }

    @Test
    fun crc32KnownVectors() {
        assertEquals(0u, CRC32.checksum(ByteArray(0)))
        assertEquals(0xCBF43926u, CRC32.checksum("123456789".toByteArray()))
    }

    @Test
    fun deflateRoundTripAndContainer() {
        val data = "planet".repeat(200).toByteArray()
        val container = Deflate.compress(data)!!
        // 容器头 4 字节 = 原始长度小端
        val len = (container[0].toInt() and 0xFF) or
            ((container[1].toInt() and 0xFF) shl 8) or
            ((container[2].toInt() and 0xFF) shl 16) or
            ((container[3].toInt() and 0xFF) shl 24)
        assertEquals(data.size, len)
        assertArrayEquals(data, Deflate.decompress(container))
        assertNull(Deflate.decompress(ByteArray(4)))
    }
}

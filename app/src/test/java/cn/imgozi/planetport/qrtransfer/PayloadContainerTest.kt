package cn.imgozi.planetport.qrtransfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PayloadContainer 四类核心用例:未知 key 跳过、重复 key 覆盖、
 * 截断/magic 不符返回 null、字段往返。 */
class PayloadContainerTest {

    @Test
    fun roundTripMultipleFields() {
        val planet = ByteArray(64) { ((it * 13 + 5) % 256).toByte() }
        val fields = listOf(
            1u.toUByte() to planet,
            2u.toUByte() to "测试 Planet".toByteArray(),
            4u.toUByte() to byteArrayOf(0x0A, 0x0B),
        )
        val decoded = PayloadContainer.decode(PayloadContainer.encode(fields))
        assertNotNull(decoded)
        assertEquals(3, decoded!!.size)
        assertArrayEquals(planet, decoded[1u])
        assertArrayEquals("测试 Planet".toByteArray(), decoded[2u])
        assertArrayEquals(byteArrayOf(0x0A, 0x0B), decoded[4u])
    }

    @Test
    fun unknownKeyPreservedAndSkippable() {
        // 组件对 key 含义零假设:未知 key(5)照样解出,由应用层自行忽略
        val container = PayloadContainer.encode(listOf(1u.toUByte() to byteArrayOf(1), 5u.toUByte() to byteArrayOf(9)))
        val decoded = PayloadContainer.decode(container)!!
        assertEquals(2, decoded.size)
        assertArrayEquals(byteArrayOf(9), decoded[5u])
    }

    @Test
    fun duplicateKeyLastWins() {
        val container = PayloadContainer.encode(
            listOf(2u.toUByte() to "first".toByteArray(), 2u.toUByte() to "last".toByteArray()),
        )
        assertEquals("last", String(PayloadContainer.decode(container)!![2u]!!))
    }

    @Test
    fun keyZeroRejected() {
        // 编码侧:key 0 条目直接丢弃
        val encoded = PayloadContainer.encode(listOf(0u.toUByte() to byteArrayOf(1), 1u.toUByte() to byteArrayOf(2)))
        assertEquals(1, PayloadContainer.decode(encoded)!!.size)
        // 解码侧:显式 key 0 → 整容器非法
        val bytes = "ZTP".toByteArray() + byteArrayOf(0, 0, 0, 0, 0)
        assertNull(PayloadContainer.decode(bytes))
    }

    @Test
    fun emptyFieldsYieldsMagicOnlyContainer() {
        val decoded = PayloadContainer.decode(PayloadContainer.encode(emptyList()))
        assertNotNull(decoded)
        assertTrue(decoded!!.isEmpty())
    }

    @Test
    fun badMagicReturnsNull() {
        assertNull(PayloadContainer.decode(ByteArray(0)))
        assertNull(PayloadContainer.decode("ZT".toByteArray()))
        assertNull(PayloadContainer.decode("XTP".toByteArray()))
        assertNull(PayloadContainer.decode(byteArrayOf(0x01, 0x02, 0x03, 0x04)))
    }

    @Test
    fun truncatedContainerReturnsNull() {
        val entry1 = ByteArray(32) { it.toByte() }
        val container = PayloadContainer.encode(
            listOf(1u.toUByte() to entry1, 2u.toUByte() to "name".toByteArray()),
        )
        // 设计特性:无总长字段,数据尾即容器尾——截断恰落在条目边界(3、3+37)
        // 时前缀条目本身是合法容器;其余任何截断点(条目中途)必须返回 null
        val boundaryPrefix = setOf(3, 3 + 5 + entry1.size)
        for (len in 0..container.size) {
            val decoded = PayloadContainer.decode(container.copyOfRange(0, len))
            if (len in boundaryPrefix || len == container.size) {
                assertNotNull(decoded)
            } else {
                assertNull("len=$len", decoded)
            }
        }
    }

    @Test
    fun lenOutOfRangeReturnsNull() {
        // 条目头 5 字节齐全但 len 声称超过剩余数据
        val bytes = "ZTP".toByteArray() + byteArrayOf(1, 0x10, 0, 0, 0) + ByteArray(4)
        assertNull(PayloadContainer.decode(bytes))
        // len 符号位为 1(按 Int 为负)同样视同越界
        val negative = "ZTP".toByteArray() + byteArrayOf(1, 0, 0, 0, -0x80) + ByteArray(4)
        assertNull(PayloadContainer.decode(negative))
    }
}

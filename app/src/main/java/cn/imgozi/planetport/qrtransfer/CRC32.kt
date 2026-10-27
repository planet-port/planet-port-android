package cn.imgozi.planetport.qrtransfer

/** 标准 CRC-32（IEEE 802.3，反射多项式 0xEDB88320）。查表由代码生成，不硬编码 256 项。 */
internal object CRC32 {
    private val table = IntArray(256).also { tbl ->
        for (i in 0 until 256) {
            var c = i
            // 0xEDB88320 超出 Int 范围，字面量会被推断为 Long，此处转回 Int（位模式不变）
            repeat(8) { c = if (c and 1 == 1) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1 }
            tbl[i] = c
        }
    }

    fun checksum(data: ByteArray): UInt {
        var crc = -1
        for (b in data) {
            crc = table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return (crc xor -1).toUInt()
    }
}

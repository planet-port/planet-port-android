package cn.imgozi.planetport.qrtransfer

/** ZTP KV 容器(载荷层 wire format)。容器 = magic "ZTP" + 无序条目流,
 * 条目 = key(1B) + len(4B 小端) + value,数据尾即容器尾。
 * 纯字节层编解码,对 key 含义零假设——key 语义是应用层约定,
 * 未知 key 由解析方自行忽略,保证 wire format 永不随业务演进变化。 */
internal object PayloadContainer {
    const val magic = "ZTP"

    /** 0 保留为非法哨兵 */
    const val invalidKey: UByte = 0u

    private val magicBytes = magic.toByteArray(Charsets.US_ASCII)

    /** 字段列表 → 容器。key 为 0 的字段直接丢弃;同 key 多条目时 decode
     * 取后值,编码方应避免产生重复。空列表产出仅含 magic 的合法空容器。 */
    fun encode(fields: List<Pair<UByte, ByteArray>>): ByteArray {
        var size = magicBytes.size
        for ((key, value) in fields) {
            if (key == invalidKey) continue
            size += 5 + value.size
        }
        val out = ByteArray(size)
        var pos = 0
        for (b in magicBytes) out[pos++] = b
        for ((key, value) in fields) {
            if (key == invalidKey) continue
            out[pos++] = key.toByte()
            val len = value.size
            out[pos++] = (len and 0xFF).toByte()
            out[pos++] = ((len shr 8) and 0xFF).toByte()
            out[pos++] = ((len shr 16) and 0xFF).toByte()
            out[pos++] = ((len shr 24) and 0xFF).toByte()
            value.copyInto(out, pos)
            pos += len
        }
        return out
    }

    /** 容器 → 字典。magic 不符、key 为 0、len 越界(截断/损坏)→ null;
     * 重复 key 取后出现的值(map 赋值天然覆盖)。 */
    fun decode(data: ByteArray): Map<UByte, ByteArray>? {
        if (data.size < magicBytes.size) return null
        for (i in magicBytes.indices) {
            if (data[i] != magicBytes[i]) return null
        }
        val fields = HashMap<UByte, ByteArray>()
        var i = magicBytes.size
        while (i < data.size) {
            val key = data[i].toUByte()
            if (key == invalidKey) return null
            // 条目头 key+len 共 5 字节;len 符号位为 1 时按 Int 为负,视同越界
            if (data.size - i < 5) return null
            val len = (data[i + 1].toInt() and 0xFF) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                ((data[i + 3].toInt() and 0xFF) shl 16) or
                ((data[i + 4].toInt() and 0xFF) shl 24)
            if (len < 0 || data.size - i - 5 < len) return null
            val valueStart = i + 5
            fields[key] = data.copyOfRange(valueStart, valueStart + len)
            i = valueStart + len
        }
        return fields
    }
}

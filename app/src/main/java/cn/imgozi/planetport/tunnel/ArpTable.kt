package cn.imgozi.planetport.tunnel

/** IPv4 ARP 表（IP 统一 UInt host order；报文构造/解析 RFC 826，字段大端） */
internal class ArpTable {
    private val map = HashMap<UInt, Long>()

    fun lookup(ip: UInt): Long? = synchronized(this) { map[ip] }
    fun update(ip: UInt, mac: Long) = synchronized(this) { map[ip] = mac }
    val count: Int get() = synchronized(this) { map.size }

    internal class ArpPacket(
        val op: Int,          // 1 请求 2 应答
        val senderMac: Long,
        val senderIp: UInt,
        val targetMac: Long,
        val targetIp: UInt,
    )

    companion object {
        private val HEADER = byteArrayOf(0, 1, 0x08, 0, 6, 4)  // htype=1 ptype=IPv4 hlen=6 plen=4

        fun request(senderIp: UInt, senderMac: Long, targetIp: UInt): ByteArray {
            val p = ByteWriter(28)
            p.bytes(HEADER); p.u16(1)
            p.mac(senderMac); p.ip(senderIp)
            p.mac(0); p.ip(targetIp)
            return p.build()
        }

        fun reply(senderIp: UInt, senderMac: Long, targetIp: UInt, targetMac: Long): ByteArray {
            val p = ByteWriter(28)
            p.bytes(HEADER); p.u16(2)
            p.mac(senderMac); p.ip(senderIp)
            p.mac(targetMac); p.ip(targetIp)
            return p.build()
        }

        fun parse(payload: ByteArray): ArpPacket? {
            if (payload.size < 28) return null
            if (payload[0] != 0.toByte() || payload[1] != 1.toByte() ||
                payload[2] != 0x08.toByte() || payload[3] != 0.toByte() ||
                payload[4] != 6.toByte() || payload[5] != 4.toByte()
            ) return null
            val op = ((payload[6].toInt() and 0xff) shl 8) or (payload[7].toInt() and 0xff)
            if (op != 1 && op != 2) return null
            return ArpPacket(
                op = op,
                senderMac = mac(payload, 8),
                senderIp = ip(payload, 14),
                targetMac = mac(payload, 18),
                targetIp = ip(payload, 24),
            )
        }

        private fun mac(p: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 6) v = (v shl 8) or (p[o + i].toLong() and 0xff)
            return v
        }

        private fun ip(p: ByteArray, o: Int): UInt {
            var v = 0u
            for (i in 0 until 4) v = (v shl 8) or (p[o + i].toUInt() and 0xffu)
            return v
        }
    }
}

/** 大端字节流构造器（ARP/NDP 报文构造用） */
internal class ByteWriter(capacity: Int) {
    private val buf = ByteArray(capacity)
    private var pos = 0

    fun bytes(b: ByteArray) { System.arraycopy(b, 0, buf, pos, b.size); pos += b.size }
    fun u16(v: Int) { buf[pos++] = (v shr 8).toByte(); buf[pos++] = (v and 0xff).toByte() }
    fun mac(m: Long) {
        for (shift in 40 downTo 0 step 8) buf[pos++] = ((m shr shift) and 0xff).toByte()
    }
    fun ip(v: UInt) {
        for (shift in 24 downTo 0 step 8) buf[pos++] = ((v shr shift) and 0xffu).toByte()
    }
    fun build(): ByteArray = buf
}

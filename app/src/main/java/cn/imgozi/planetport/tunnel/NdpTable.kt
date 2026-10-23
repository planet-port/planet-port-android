package cn.imgozi.planetport.tunnel

/** IPv6 地址值对象（ByteArray 作 map key 需按内容判等） */
internal class Ipv6Address(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Ipv6Address && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/** IPv6 邻居发现表（NS/NA 软件处理，对齐 iOS NDPTable） */
internal class NdpTable {
    private val map = HashMap<Ipv6Address, Long>()

    fun lookup(v6: Ipv6Address): Long? = synchronized(this) { map[v6] }
    fun update(v6: Ipv6Address, mac: Long) = synchronized(this) { map[v6] = mac }
    val count: Int get() = synchronized(this) { map.size }

    internal class NdpPacket(
        val type: Int,             // 135 NS / 136 NA
        val target: Ipv6Address,
        val senderMac: Long?,
    )

    companion object {
        /** NS/NA 的 ICMPv6 载荷（4 字节头 + 16 字节 target + 源 MAC 选项） */
        fun neighborSolicitation(target: Ipv6Address, senderMac: Long): ByteArray =
            icmpv6(135, target, senderMac, flagSolicited = false)

        fun neighborAdvertisement(target: Ipv6Address, senderMac: Long): ByteArray =
            icmpv6(136, target, senderMac, flagSolicited = true)

        /** IPv6 组播地址 → 组播 MAC（0x3333 + 低 32 位） */
        fun multicastMAC(v6: Ipv6Address): Long {
            val b = v6.bytes
            return 0x333300000000L or
                ((b[12].toLong() and 0xff) shl 24) or ((b[13].toLong() and 0xff) shl 16) or
                ((b[14].toLong() and 0xff) shl 8) or (b[15].toLong() and 0xff)
        }

        /** 请求节点组播地址（ff02::1:ffXX:XXXX） */
        fun solicitedNodeMulticast(target: Ipv6Address): Ipv6Address {
            val t = target.bytes
            val m = ByteArray(16)
            m[0] = 0xff.toByte(); m[1] = 0x02.toByte()
            m[11] = 0x01; m[12] = 0xff.toByte()
            m[13] = t[13]; m[14] = t[14]; m[15] = t[15]
            return Ipv6Address(m)
        }

        private fun icmpv6(type: Int, target: Ipv6Address, mac: Long, flagSolicited: Boolean): ByteArray {
            val p = ByteArray(4 + 16 + 8)
            p[0] = type.toByte(); p[1] = 0
            p[4] = if (flagSolicited) 0x60 else 0  // NA flags: Solicited+Override（RFC 4861 offset 4；NS 此处保留 0）
            target.bytes.copyInto(p, 4)
            p[20] = 1; p[21] = 1  // 源链路层地址选项
            for (shift in 40 downTo 0 step 8) {
                p[22 + (5 - shift / 8)] = ((mac shr shift) and 0xff).toByte()
            }
            return p
        }

        fun parse(payload: ByteArray): NdpPacket? {
            if (payload.size < 24) return null
            if (payload[0] != 135.toByte() && payload[0] != 136.toByte()) return null
            val target = Ipv6Address(payload.copyOfRange(8, 24))
            var senderMac: Long? = null
            if (payload.size >= 32 && payload[24] == 1.toByte() && payload[25] == 1.toByte()) {
                var v = 0L
                for (i in 2 until 8) v = (v shl 8) or (payload[24 + i].toLong() and 0xff)
                senderMac = v
            }
            return NdpPacket(payload[0].toInt(), target, senderMac)
        }

        /** 是否 NDP 报文（完整 IPv6 包：next header 58 且 ICMPv6 type 135/136） */
        fun isNDP(ipv6Packet: ByteArray): Boolean {
            if (ipv6Packet.size < 48) return false
            if (ipv6Packet[6] != 58.toByte()) return false
            val t = ipv6Packet[40].toInt() and 0xff
            return t == 135 || t == 136
        }
    }
}

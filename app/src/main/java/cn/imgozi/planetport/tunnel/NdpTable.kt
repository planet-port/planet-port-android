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
        /** NS/NA 的 ICMPv6 载荷（头4+flags/保留4+target16+源MAC选项8 共 32 字节，RFC 4861） */
        fun neighborSolicitation(target: Ipv6Address, senderMac: Long): ByteArray =
            icmpv6(135, target, senderMac, flagSolicited = false)

        fun neighborAdvertisement(target: Ipv6Address, senderMac: Long): ByteArray =
            icmpv6(136, target, senderMac, flagSolicited = true)

        /**
         * ICMPv6 伪头校验和（RFC 4443 §8.1）：src(16B)+dst(16B)+payload 长度(32bit)+
         * 3 零字节+nextHeader(58)，与 ICMPv6 载荷一起按 RFC 1071 互联网校验和求和，
         * 结果取反回写 payload[2..3]。计算前先清零校验和字段。真实节点内核会校验，
         * 恒 0 的报文被丢弃（Android↔Android 双端软件路径不校验故此前未暴露）。
         */
        fun icmpv6Checksum(src: Ipv6Address, dst: Ipv6Address, payload: ByteArray) {
            payload[2] = 0; payload[3] = 0
            var sum = 0L
            fun add16(word: Int) { sum += word }
            for (i in 0 until 16 step 2) {
                add16(((src.bytes[i].toInt() and 0xff) shl 8) or (src.bytes[i + 1].toInt() and 0xff))
            }
            for (i in 0 until 16 step 2) {
                add16(((dst.bytes[i].toInt() and 0xff) shl 8) or (dst.bytes[i + 1].toInt() and 0xff))
            }
            add16(payload.size) // 伪头 32bit 长度的高 16 位恒 0，单字相加等价
            add16(58)
            var i = 0
            while (i < payload.size) {
                val hi = payload[i].toInt() and 0xff
                val lo = if (i + 1 < payload.size) payload[i + 1].toInt() and 0xff else 0
                add16((hi shl 8) or lo)
                i += 2
            }
            while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
            val ck = sum.toInt().inv() and 0xffff
            payload[2] = (ck shr 8).toByte()
            payload[3] = (ck and 0xff).toByte()
        }

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

        /** RFC 4861 布局：ICMPv6 头(4) + flags/保留(4) + target(16) + 源 MAC 选项(8)，与 parse 对齐 */
        private fun icmpv6(type: Int, target: Ipv6Address, mac: Long, flagSolicited: Boolean): ByteArray {
            val p = ByteArray(8 + 16 + 8)
            p[0] = type.toByte(); p[1] = 0
            p[4] = if (flagSolicited) 0x60 else 0  // NA flags: Solicited+Override（NS 此处保留 0）
            target.bytes.copyInto(p, 8)
            p[24] = 1; p[25] = 1  // 源链路层地址选项
            for (shift in 40 downTo 0 step 8) {
                p[26 + (5 - shift / 8)] = ((mac shr shift) and 0xff).toByte()
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

package cn.imgozi.planetport.tunnel

import android.os.SystemClock

/**
 * 多网络源地址 NAT（iOS NAT44.swift 1:1 移植）。
 * Android 单 tun 多地址时内核同样存在源地址选错风险（路由无 per-route src
 * 绑定，源按接口主地址选择），路由层无出路，只能在数据面改包：
 * - 出站：源 IP 不属于所选网络时改写为该网络地址（增量修正 IP 头与 TCP/UDP
 *   校验和，RFC 1624），TCP/UDP 登记流表（proto + 远端 IP/端口 + 本地端口 →
 *   原始源）
 * - 入站：命中流表的回包把目的地址回写为原始源——否则应用 socket
 *   （本地地址是错误的原始源）匹配不上，TCP 握手无法完成
 * - ICMP 无需回写：echo 回包按 id 匹配 raw socket，不按目的地址；
 *   ICMP 差错报文（嵌入的原包头源已是改写后地址）无法送回原始 socket，是已知限制
 * - 分片包不进流表（非首片无端口无法匹配；ZT 内部 MTU 内基本不分片，维持现状）
 * - 单网络场景源不可能错，零改动直通，不引入回归
 * 线程：无锁，仅在 FrameConverter 的锁内使用。
 */
internal class NAT44 {

    /** TCP=6 / UDP=17，nwid + 四元组流键（不换端口，纯地址改写）。带 nwid：
     * 嵌套子网（如 10.0.0.0/8 与 10.0.0.0/24 两网共存，冲突检测按 CIDR 精确
     * 比较不拦）时同一四元组可能分属两网的不同流且原始源不同，按网络隔离流键
     * 即根除串流。 */
    data class FlowKey(
        val nwid: Long,
        val proto: Int,       // 6 / 17
        val remoteIP: UInt,   // host order
        val remotePort: Int,
        val localPort: Int,
    )

    private class Entry(val origSrc: UInt, var lastUsed: Long)

    companion object {
        /** 表上限 + LRU + 按协议空闲老化（服务进程内存约束）。TCP 拉长到小时级
         * （对齐 conntrack established 思路：真实保活间隔均为分钟级，空闲超时后
         * 「对端先发包」才断流，长连接场景需覆盖其空闲窗口）；UDP 无连接语义，
         * 短老化靠新出站重建。双向任一包都刷新活跃时间。 */
        const val MAX_ENTRIES = 512
        const val IDLE_TIMEOUT_TCP_MS = 6 * 3600 * 1000L
        const val IDLE_TIMEOUT_UDP_MS = 60 * 1000L

        // MARK: - 流键提取

        /** 出站 IPv4 包提取 TCP/UDP 流键；非 TCP/UDP 或分片返回 null（ICMP 无需流表） */
        fun outboundKey(pkt: ByteArray, nwid: Long): FlowKey? {
            if (pkt.size < 24 || (pkt[0].toInt() shr 4) != 4) return null
            val proto = pkt[9].toInt() and 0xff
            if (proto != 6 && proto != 17) return null
            if (isFragment(pkt)) return null
            val ihl = (pkt[0].toInt() and 0x0f) * 4
            if (pkt.size < ihl + 4) return null
            return FlowKey(
                nwid = nwid, proto = proto,
                remoteIP = read32(pkt, 16),
                remotePort = read16(pkt, ihl + 2),
                localPort = read16(pkt, ihl),
            )
        }

        /** 入站 IPv4 包提取流键（方向翻转：对端为源）；非 TCP/UDP 或分片返回 null */
        fun inboundKey(pkt: ByteArray, nwid: Long): FlowKey? {
            if (pkt.size < 24 || (pkt[0].toInt() shr 4) != 4) return null
            val proto = pkt[9].toInt() and 0xff
            if (proto != 6 && proto != 17) return null
            if (isFragment(pkt)) return null
            val ihl = (pkt[0].toInt() and 0x0f) * 4
            if (pkt.size < ihl + 4) return null
            return FlowKey(
                nwid = nwid, proto = proto,
                remoteIP = read32(pkt, 12),
                remotePort = read16(pkt, ihl),
                localPort = read16(pkt, ihl + 2),
            )
        }

        /** MF 位置位或片偏移非零（首 4 位以外的 flags+fragment 全域） */
        private fun isFragment(pkt: ByteArray): Boolean =
            (read16(pkt, 6) and 0x3fff) != 0

        /** 片偏移（8 字节单位，首片为 0） */
        private fun fragOffset(pkt: ByteArray): Int = read16(pkt, 6) and 0x1fff

        private fun read16(pkt: ByteArray, o: Int): Int =
            ((pkt[o].toInt() and 0xff) shl 8) or (pkt[o + 1].toInt() and 0xff)

        private fun read32(pkt: ByteArray, o: Int): UInt =
            (((pkt[o].toUInt() and 0xffu) shl 24) or
                ((pkt[o + 1].toUInt() and 0xffu) shl 16) or
                ((pkt[o + 2].toUInt() and 0xffu) shl 8) or
                (pkt[o + 3].toUInt() and 0xffu))

        // MARK: - 地址改写（校验和按 RFC 1624 增量修正）

        /** 改写 IPv4 包 [offset] 处地址（12=源 / 16=目的），增量修正 IP 头与 L4
         * 校验和。非首片分片（片偏移≠0）只修 IP 头——其 ihl 之后是用户载荷不是
         * L4 头，按 L4 校验和位置写回会破坏载荷；首片（MF=1 且偏移 0）含完整
         * L4 头可全量修正。调用方保证 v4、地址确实位于 offset。 */
        fun rewriteAddress(pkt: ByteArray, offset: Int, old: UInt, new: UInt) {
            if (pkt.size < 20) return
            adjustChecksum(pkt, 10, old, new)
            write32(pkt, offset, new)
            if (fragOffset(pkt) != 0) return
            val ihl = (pkt[0].toInt() and 0x0f) * 4
            when (pkt[9].toInt() and 0xff) {
                6 -> adjustChecksum(pkt, ihl + 16, old, new)
                17 -> {
                    // UDP 校验和在 ihl+6；0 表示未计算（卸载），保持
                    if (pkt.size >= ihl + 8 && !(pkt[ihl + 6] == 0.toByte() && pkt[ihl + 7] == 0.toByte())) {
                        adjustChecksum(pkt, ihl + 6, old, new, zeroAsFFFF = true)
                    }
                }
                // ICMP 校验和不覆盖 IP 地址，无需修正
            }
        }

        /** RFC 1624: HC' = ~(~HC + ~m + m')，反码进位回卷 */
        private fun adjustChecksum(
            pkt: ByteArray, ckOffset: Int, old: UInt, new: UInt,
            zeroAsFFFF: Boolean = false,
        ) {
            if (pkt.size < ckOffset + 2) return
            val hc = read16(pkt, ckOffset).toUInt()
            val m = ocAdd(old shr 16, old and 0xffffu)
            val mp = ocAdd(new shr 16, new and 0xffffu)
            var sum = (hc.inv() and 0xffffu) + (m.inv() and 0xffffu) + mp
            while (sum > 0xffffu) sum = (sum and 0xffffu) + (sum shr 16)
            var result = sum.inv() and 0xffffu
            if (zeroAsFFFF && result == 0u) result = 0xffffu // RFC 768：计算值为 0 传 0xffff
            pkt[ckOffset] = (result shr 8).toByte()
            pkt[ckOffset + 1] = result.toByte()
        }

        /** 反码加法（端回卷进位）；入参出参均 0..0xffff */
        private fun ocAdd(a: UInt, b: UInt): UInt {
            var s = a + b
            while (s > 0xffffu) s = (s and 0xffffu) + (s shr 16)
            return s
        }

        private fun write32(pkt: ByteArray, offset: Int, v: UInt) {
            pkt[offset] = (v shr 24).toByte()
            pkt[offset + 1] = ((v shr 16) and 0xffu).toByte()
            pkt[offset + 2] = ((v shr 8) and 0xffu).toByte()
            pkt[offset + 3] = (v and 0xffu).toByte()
        }
    }

    private val flows = HashMap<FlowKey, Entry>()

    /** 表项数（诊断输出用） */
    val count: Int get() = flows.size

    /** 出站登记/刷新（改写源前调用） */
    fun record(key: FlowKey, origSrc: UInt) {
        flows[key]?.let {
            it.lastUsed = SystemClock.elapsedRealtime()
            return
        }
        if (flows.size >= MAX_ENTRIES) evictLocked()
        flows[key] = Entry(origSrc, SystemClock.elapsedRealtime())
    }

    /** 入站查询（命中即刷新活跃时间） */
    fun lookup(key: FlowKey): UInt? {
        val e = flows[key] ?: return null
        e.lastUsed = SystemClock.elapsedRealtime()
        return e.origSrc
    }

    /** 清理：先淘汰过期（按各流协议老化时间），仍满则逐最旧（512 条 O(n) 可忽略） */
    private fun evictLocked() {
        val now = SystemClock.elapsedRealtime()
        flows.entries.removeIf { now - it.value.lastUsed >= timeoutOf(it.key) }
        while (flows.size >= MAX_ENTRIES) {
            val oldest = flows.minByOrNull { it.value.lastUsed } ?: break
            flows.remove(oldest.key)
        }
    }

    private fun timeoutOf(key: FlowKey): Long =
        if (key.proto == 6) IDLE_TIMEOUT_TCP_MS else IDLE_TIMEOUT_UDP_MS
}

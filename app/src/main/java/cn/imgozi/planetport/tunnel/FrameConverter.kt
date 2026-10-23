package cn.imgozi.planetport.tunnel

/**
 * IP 包 ↔ 虚拟网帧转换层。
 * - 下行（tun→核心）：L3 IP 包 → 聚合路由表最长前缀匹配选网络 → 查该网 ARP/NDP
 *   → processVirtualNetworkFrame
 * - 上行（核心→tun）：帧回调按 nwid 分流到对应 NetworkContext
 * 多网络隔离：MAC/地址/ARP/NDP/pending/路由全部按网络存放，互不串网。
 * 全方法持单一锁（对齐 iOS NSLock 模型）；锁内调用 Node JNI（核心内部有锁，
 * 不构成反向进入本类的路径）。
 */
internal class FrameConverter(private val runtime: NodeRuntime) {
    private val lock = Any()
    private val contexts = HashMap<Long, NetworkContext>()
    /** 激活顺序（首次 upsert 追加；聚合表同前缀先到者胜的裁决依据） */
    private val order = ArrayList<Long>()
    private var aggregateV4: List<AggregateV4Entry> = emptyList()
    private var aggregateV6: List<AggregateV6Entry> = emptyList()
    private var statNoRoute = 0

    companion object {
        /** 每下一跳待决包上限（超出丢包，防移动端内存失控） */
        const val MAX_PENDING_PER_TARGET = 16
    }

    // MARK: - 上下文管理（配置回调路径）

    fun updateNetworkInfo(
        nwid: Long, enabled: Boolean, mac: Long,
        ipv4: List<String>, ipv6: List<String>, routes: List<String>,
    ) {
        synchronized(lock) {
            if (!enabled) {
                removeNetworkLocked(nwid)
                return
            }
            val ctx = contexts[nwid]
            if (ctx != null) {
                ctx.update(mac, ipv4, ipv6, routes)
            } else {
                order.add(nwid)
                contexts[nwid] = NetworkContext(nwid, mac, ipv4, ipv6, routes)
            }
            rebuildAggregatesLocked()
        }
    }

    fun removeNetwork(nwid: Long) {
        synchronized(lock) { removeNetworkLocked(nwid) }
    }

    /** reconcile：仅保留给定网络集合，清理幽灵上下文（读文件与 leave 并发时防残留） */
    fun retainOnly(nwids: Set<Long>) {
        synchronized(lock) {
            val removed = contexts.keys.filter { it !in nwids }
            if (removed.isEmpty()) return
            for (nwid in removed) {
                contexts.remove(nwid)
                order.remove(nwid)
            }
            rebuildAggregatesLocked()
        }
    }

    private fun removeNetworkLocked(nwid: Long) {
        if (contexts.remove(nwid) == null) return
        order.remove(nwid)
        rebuildAggregatesLocked()
    }

    private fun rebuildAggregatesLocked() {
        val ordered = orderedContextsLocked()
        aggregateV4 = NetworkContextMath.buildAggregateV4(ordered)
        aggregateV6 = NetworkContextMath.buildAggregateV6(ordered)
    }

    private fun orderedContextsLocked(): List<NetworkContext> = order.mapNotNull { contexts[it] }

    /** 诊断快照（验证页/排查用） */
    fun debugSummary(): String = synchronized(lock) {
        val lines = mutableListOf(
            "contexts=${contexts.size} aggV4=${aggregateV4.size} aggV6=${aggregateV6.size} 无路由丢弃=$statNoRoute"
        )
        for (nwid in order) {
            val c = contexts[nwid] ?: continue
            lines.add(
                "ctx %016x mac=%012x v4=[%s] routes=${c.routesV4.size}/${c.routesV6.size} " +
                    "出站=${c.statFwdV4} 入站=${c.statInV4} ARP问=${c.statArpReq} ARP答=${c.statArpReply} " +
                    "保活=${c.statKeepalive} ARP表=${c.arp.count} 待决=${c.pendingV4.values.sumOf { it.size }}"
                    .format(nwid, c.mac, c.ipv4Addresses.joinToString(",") { NetworkContextMath.dotted(it) })
            )
        }
        lines.joinToString("\n")
    }

    // MARK: - tun -> core（Dataplane 读线程调用）

    fun handlePacketsFromTun(packets: List<ByteArray>) {
        synchronized(lock) {
            if (contexts.isEmpty()) return
            for (pkt in packets) {
                if (pkt.size < 20) continue
                when ((pkt[0].toInt() shr 4) and 0xf) {
                    4 -> handleV4Locked(pkt)
                    6 -> if (pkt.size >= 40) handleV6Locked(pkt)
                }
            }
        }
    }

    private fun handleV4Locked(pkt: ByteArray) {
        val dst = bigEndianIP32(pkt, 16)
        // 广播/组播直送所有有 IPv4 地址的网络（组播包发对应组播 MAC）
        if (dst == 0xffffffffu || isIPv4Multicast(dst)) {
            val dstMac = if (dst == 0xffffffffu) Ethernet.BROADCAST_MAC else ipv4MulticastMAC(dst)
            for (ctx in orderedContextsLocked()) {
                if (ctx.ipv4Addresses.isEmpty() || ctx.mac == 0L) continue
                runtime.processVirtualNetworkFrame(
                    ctx.nwid, ctx.mac, dstMac, Ethernet.TYPE_IPV4, 0, pkt
                )
            }
            return
        }
        // 聚合表最长前缀匹配（前缀降序、同前缀先激活者胜）；无命中丢弃
        val match = aggregateV4.firstOrNull { it.route.matches(dst) } ?: run {
            statNoRoute++
            return
        }
        val ctx = contexts[match.nwid] ?: return
        if (ctx.mac == 0L) return
        val nextHop = match.route.gateway ?: dst
        val mac = ctx.arp.lookup(nextHop)
        if (mac == null) {
            val pending = ctx.pendingV4.getOrPut(nextHop) { mutableListOf() }
            if (pending.size < MAX_PENDING_PER_TARGET) pending.add(pkt)
            ctx.statArpReq++
            sendARPRequestLocked(ctx, nextHop)
            return
        }
        runtime.processVirtualNetworkFrame(ctx.nwid, ctx.mac, mac, Ethernet.TYPE_IPV4, 0, pkt)
        ctx.statFwdV4++
    }

    private fun handleV6Locked(pkt: ByteArray) {
        val dstBytes = pkt.copyOfRange(24, 40)
        val dst = Ipv6Address(dstBytes)
        if (isIPv6Multicast(dstBytes)) {
            val mac = NdpTable.multicastMAC(dst)
            for (ctx in orderedContextsLocked()) {
                if (ctx.ipv6Addresses.isEmpty() || ctx.mac == 0L) continue
                runtime.processVirtualNetworkFrame(
                    ctx.nwid, ctx.mac, mac, Ethernet.TYPE_IPV6, 0, pkt
                )
            }
            return
        }
        val match = aggregateV6.firstOrNull { it.route.matches(dst) } ?: return
        val ctx = contexts[match.nwid] ?: return
        if (ctx.mac == 0L) return
        val nextHop = match.route.gateway?.let { Ipv6Address(it.bytes) } ?: dst
        val mac = ctx.ndp.lookup(nextHop)
        if (mac == null) {
            val pending = ctx.pendingV6.getOrPut(nextHop) { mutableListOf() }
            if (pending.size < MAX_PENDING_PER_TARGET) pending.add(pkt)
            sendNeighborSolicitationLocked(ctx, nextHop)
            return
        }
        runtime.processVirtualNetworkFrame(ctx.nwid, ctx.mac, mac, Ethernet.TYPE_IPV6, 0, pkt)
    }

    // MARK: - core -> tun（消费协程调用）；返回 IP 包列表写入 tun

    fun handleFrameFromCore(
        nwid: Long, srcMac: Long, dstMac: Long, etherType: Int, frame: ByteArray,
    ): List<ByteArray> {
        synchronized(lock) {
            val ctx = contexts[nwid] ?: return emptyList()
            return when (etherType) {
                Ethernet.TYPE_ARP -> {
                    handleARPLocked(ctx, frame, srcMac)
                    emptyList()
                }
                Ethernet.TYPE_IPV6 -> {
                    if (NdpTable.isNDP(frame)) {
                        handleNDPLocked(ctx, frame, srcMac)
                        emptyList()
                    } else {
                        listOf(frame)
                    }
                }
                Ethernet.TYPE_IPV4 -> {
                    ctx.statInV4++
                    listOf(frame)
                }
                else -> emptyList()
            }
        }
    }

    /** ARP 处理（仅更新本网络缓存）：请求对本网持有地址应答；应答更新表并冲刷待决 */
    private fun handleARPLocked(ctx: NetworkContext, payload: ByteArray, senderMac: Long) {
        val p = ArpTable.parse(payload) ?: return
        when (p.op) {
            1 -> if (ctx.ipv4Addresses.contains(p.targetIp)) {
                val reply = ArpTable.reply(p.targetIp, ctx.mac, p.senderIp, p.senderMac)
                runtime.processVirtualNetworkFrame(
                    ctx.nwid, ctx.mac, senderMac, Ethernet.TYPE_ARP, 0, reply
                )
            }
            2 -> {
                ctx.statArpReply++
                ctx.arp.update(p.senderIp, p.senderMac)
                val waiting = ctx.pendingV4.remove(p.senderIp)
                if (waiting != null) {
                    for (pkt in waiting) {
                        runtime.processVirtualNetworkFrame(
                            ctx.nwid, ctx.mac, p.senderMac, Ethernet.TYPE_IPV4, 0, pkt
                        )
                    }
                }
            }
        }
    }

    private fun handleNDPLocked(ctx: NetworkContext, packet: ByteArray, senderMac: Long) {
        val icmp6 = packet.copyOfRange(40, packet.size)
        val srcIPv6 = Ipv6Address(packet.copyOfRange(8, 24))
        val p = NdpTable.parse(icmp6) ?: return
        when (p.type) {
            135 -> if (ctx.ipv6Addresses.contains(p.target)) {
                val na = NdpTable.neighborAdvertisement(p.target, ctx.mac)
                runtime.processVirtualNetworkFrame(
                    ctx.nwid, ctx.mac, senderMac, Ethernet.TYPE_IPV6, 0,
                    ipv6Packet(na, srcIPv6),
                )
            }
            136 -> {
                val mac = p.senderMac ?: return
                ctx.ndp.update(p.target, mac)
                val waiting = ctx.pendingV6.remove(p.target)
                if (waiting != null) {
                    for (pkt in waiting) {
                        runtime.processVirtualNetworkFrame(
                            ctx.nwid, ctx.mac, mac, Ethernet.TYPE_IPV6, 0, pkt
                        )
                    }
                }
            }
        }
    }

    /** 闲置保活：每活跃网络广播一个 ARP 探测帧（防 peer 路径/NAT 冷却死亡） */
    fun sendKeepalive() {
        synchronized(lock) {
            for (ctx in orderedContextsLocked()) {
                if (ctx.mac == 0L) continue
                val probeIp = ctx.ipv4Addresses.firstOrNull() ?: continue
                ctx.statKeepalive++
                sendARPRequestLocked(ctx, probeIp)
            }
        }
    }

    // MARK: - 控制帧

    private fun sendARPRequestLocked(ctx: NetworkContext, target: UInt) {
        val req = ArpTable.request(ctx.ipv4Addresses.firstOrNull() ?: 0u, ctx.mac, target)
        runtime.processVirtualNetworkFrame(
            ctx.nwid, ctx.mac, Ethernet.BROADCAST_MAC, Ethernet.TYPE_ARP, 0, req
        )
    }

    private fun sendNeighborSolicitationLocked(ctx: NetworkContext, target: Ipv6Address) {
        val ns = NdpTable.neighborSolicitation(target, ctx.mac)
        runtime.processVirtualNetworkFrame(
            ctx.nwid, ctx.mac,
            NdpTable.multicastMAC(NdpTable.solicitedNodeMulticast(target)),
            Ethernet.TYPE_IPV6, 0, ipv6Packet(ns, target),
        )
    }

    /** ICMPv6 载荷包成最小 IPv6 包（next header 58，hop limit 255，src ::） */
    private fun ipv6Packet(payload: ByteArray, dst: Ipv6Address): ByteArray {
        val p = ByteArray(40 + payload.size)
        p[0] = 0x60
        p[4] = ((payload.size shr 8) and 0xff).toByte()
        p[5] = (payload.size and 0xff).toByte()
        p[6] = 58
        p[7] = 255.toByte()
        dst.bytes.copyInto(p, 24)
        payload.copyInto(p, 40)
        return p
    }

    // MARK: - 地址工具

    private fun bigEndianIP32(pkt: ByteArray, offset: Int): UInt =
        ((pkt[offset].toUInt() and 0xffu) shl 24) or
            ((pkt[offset + 1].toUInt() and 0xffu) shl 16) or
            ((pkt[offset + 2].toUInt() and 0xffu) shl 8) or
            (pkt[offset + 3].toUInt() and 0xffu)

    private fun isIPv4Multicast(ip: UInt) = (ip shr 28) == 0xEu

    private fun isIPv6Multicast(addr: ByteArray) = addr[0] == 0xff.toByte()

    /** IPv4 组播 → 01:00:5e:0f:ff:ff 系组播 MAC（低 23 位，最高位清零） */
    private fun ipv4MulticastMAC(ip: UInt): Long =
        0x01005e000000L or (ip and 0x7FFFFFu).toLong()
}

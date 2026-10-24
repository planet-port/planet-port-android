package cn.imgozi.planetport.tunnel

import kotlin.experimental.and

/** IPv4 路由条目（地址均 UInt host order） */
internal class IPv4RouteEntry(val network: UInt, val prefix: Int, val gateway: UInt?) {
    fun matches(address: UInt): Boolean {
        val mask: UInt = if (prefix == 0) 0u else UInt.MAX_VALUE shl (32 - prefix)
        return address and mask == network and mask
    }
}

/** IPv6 路由条目 */
internal class IPv6RouteEntry(val network: Ipv6Address, val prefix: Int, val gateway: Ipv6Address?) {
    fun matches(address: Ipv6Address): Boolean {
        val n = network.bytes; val a = address.bytes
        val fullBytes = prefix / 8
        for (i in 0 until fullBytes) if (n[i] != a[i]) return false
        val remaining = prefix % 8
        if (remaining == 0) return true
        val mask = (0xff shl (8 - remaining)).toByte()
        return n[fullBytes] and mask == a[fullBytes] and mask
    }
}

/** 聚合表条目：路由 + 归属网络（出站最长前缀匹配选网络） */
internal class AggregateV4Entry(val nwid: Long, val route: IPv4RouteEntry)
internal class AggregateV6Entry(val nwid: Long, val route: IPv6RouteEntry)

/**
 * 单个网络在数据面的全部隔离状态：MAC、地址、ARP/NDP、待决包、路由
 * 互不串网——多网络隔离的最小单元。
 */
internal class NetworkContext(
    val nwid: Long,
    mac: Long,
    ipv4Strings: List<String>,
    ipv6Strings: List<String>,
    routes: List<String>,
) {
    var mac = mac; private set
    var ipv4Addresses: List<UInt> = emptyList(); private set
    var ipv6Addresses: List<Ipv6Address> = emptyList(); private set
    var routesV4: List<IPv4RouteEntry> = emptyList(); private set
    var routesV6: List<IPv6RouteEntry> = emptyList(); private set
    val arp = ArpTable()
    val ndp = NdpTable()
    /** 等 ARP/NA 应答的包（每下一跳上限 FrameConverter.MAX_PENDING_PER_TARGET） */
    val pendingV4 = HashMap<UInt, MutableList<ByteArray>>()
    val pendingV6 = HashMap<Ipv6Address, MutableList<ByteArray>>()
    // 诊断计数（FrameConverter 锁内递增，仅诊断展示）
    var statFwdV4 = 0; var statArpReq = 0; var statArpReply = 0
    var statInV4 = 0; var statKeepalive = 0
    // NAT44（iOS 同名诊断）：出站源不符却非本机地址（不改写仅计数）/改写与入站回写量
    var statSrcMismatch = 0; var lastSrcMismatch: UInt? = null
    var statNatOut = 0; var statNatIn = 0

    init { update(mac, ipv4Strings, ipv6Strings, routes) }

    /**
     * 配置回调更新：仅刷新 MAC/地址/路由；ARP/NDP/pending 跨回调保留——
     * ZT 配置回调并不稀少（成员/路径变化即触发），整建重建会清缓存导致
     * ARP 风暴重放、在途待决包被静默丢弃。
     */
    fun update(mac: Long, ipv4Strings: List<String>, ipv6Strings: List<String>, routes: List<String>) {
        this.mac = mac
        ipv4Addresses = ipv4Strings.mapNotNull { NetworkContextMath.parseIPv4(RoutePlanner.ipOnly(it)) }
        ipv6Addresses = ipv6Strings.mapNotNull { NetworkContextMath.parseIPv6(RoutePlanner.ipOnly(it)) }
        routesV4 = (ipv4Strings.map { RoutePlanner.networkCIDR4(it) } + routes)
            .mapNotNull(NetworkContextMath::parseIPv4Route)
            .sortedByDescending { it.prefix }
        routesV6 = (ipv6Strings.map { RoutePlanner.networkCIDR6(it) } + routes)
            .mapNotNull(NetworkContextMath::parseIPv6Route)
            .sortedByDescending { it.prefix }
    }
}

/** 路由解析、冲突检测（后进入者让路）、跨网络聚合表构建（配置期纯逻辑，转发路径不碰） */
internal object NetworkContextMath {

    /** 冲突原因原始码（文案 UI 层生成，对齐 iOS NetworkConflict） */
    const val CONFLICT_ADDRESS = "address"
    const val CONFLICT_SUBNET = "subnet"

    /**
     * 冲突检测：输入按激活顺序。先到者登记已占 IP 与直连网段；后进入者
     * 撞车标记冲突（地址冲突优先于网段冲突）且不登记先占。冲突网络由
     * 调用方排除出数据面，保留 join 仅展示状态。
     */
    fun detectConflicts(ordered: List<Triple<Long, List<String>, List<String>>>): Map<Long, String> {
        val claimedIp4 = HashSet<UInt>()
        val claimedIp6 = HashSet<Ipv6Address>()
        val claimedSubnets = HashSet<String>()
        val conflicts = HashMap<Long, String>()
        for ((nwid, ipv4, ipv6) in ordered) {
            val subnets = ipv4.map { RoutePlanner.networkCIDR4(it) } +
                ipv6.map { RoutePlanner.networkCIDR6(it) }
            val ips4 = ipv4.mapNotNull { parseIPv4(RoutePlanner.ipOnly(it)) }
            val ips6 = ipv6.mapNotNull { parseIPv6(RoutePlanner.ipOnly(it)) }
            val conflict = when {
                ips4.any { it in claimedIp4 } || ips6.any { it in claimedIp6 } -> CONFLICT_ADDRESS
                subnets.any { it in claimedSubnets } -> CONFLICT_SUBNET
                else -> null
            }
            if (conflict != null) {
                conflicts[nwid] = conflict
                continue
            }
            claimedIp4.addAll(ips4)
            claimedIp6.addAll(ips6)
            claimedSubnets.addAll(subnets)
        }
        return conflicts
    }

    /** IPv4 聚合表：全部网络路由，前缀降序、同前缀按激活顺序（先到者胜） */
    fun buildAggregateV4(ordered: List<NetworkContext>): List<AggregateV4Entry> =
        ordered.flatMapIndexed { index, ctx ->
            ctx.routesV4.map { Triple(it.prefix, index, AggregateV4Entry(ctx.nwid, it)) }
        }.sortedWith(compareByDescending<Triple<Int, Int, AggregateV4Entry>> { it.first }.thenBy { it.second })
            .map { it.third }

    fun buildAggregateV6(ordered: List<NetworkContext>): List<AggregateV6Entry> =
        ordered.flatMapIndexed { index, ctx ->
            ctx.routesV6.map { Triple(it.prefix, index, AggregateV6Entry(ctx.nwid, it)) }
        }.sortedWith(compareByDescending<Triple<Int, Int, AggregateV6Entry>> { it.first }.thenBy { it.second })
            .map { it.third }

    /** "x.x.x.x" → UInt host order（手写解析：不走 InetAddress，避免任何解析服务介入） */
    fun parseIPv4(s: String): UInt? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        var v = 0u
        for (p in parts) {
            if (p.isEmpty() || p.length > 3 || (p.length > 1 && p[0] == '0')) return null
            val n = p.toIntOrNull() ?: return null
            if (n < 0 || n > 255) return null
            v = (v shl 8) or n.toUInt()
        }
        return v
    }

    /** "x::y" → Ipv6Address（先格式校验再 InetAddress 解析——纯字面量不触发主机名查询） */
    fun parseIPv6(s: String): Ipv6Address? {
        // 必须含 ':' 才是 IPv6 字面量——纯数字串交给 InetAddress 解析会触发主机名查询（DNS）
        if (!s.contains(':') || !s.matches(Regex("^[0-9a-fA-F:.%]+$"))) return null
        return try {
            val addr = java.net.InetAddress.getByName(s)
            if (addr is java.net.Inet6Address) Ipv6Address(addr.address) else null
        } catch (e: Exception) {
            null
        }
    }

    /** "10.0.0.0/24" 或 "10.0.0.0/24 via 10.0.0.1" → IPv4RouteEntry */
    fun parseIPv4Route(value: String): IPv4RouteEntry? {
        val fields = value.split(' ')
        val target = fields.firstOrNull() ?: return null
        val parts = target.split('/')
        if (parts.size != 2) return null
        val address = parseIPv4(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val gateway = fields.indexOf("via").takeIf { it >= 0 }
            ?.let { fields.getOrNull(it + 1) }?.let { parseIPv4(ipOnly(it)) }
        return IPv4RouteEntry(address, prefix, gateway)
    }

    fun parseIPv6Route(value: String): IPv6RouteEntry? {
        val fields = value.split(' ')
        val target = fields.firstOrNull() ?: return null
        val parts = target.split('/')
        if (parts.size != 2) return null
        val address = parseIPv6(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..128) return null
        val gateway = fields.indexOf("via").takeIf { it >= 0 }
            ?.let { fields.getOrNull(it + 1) }?.let { parseIPv6(ipOnly(it)) }
        return IPv6RouteEntry(address, prefix, gateway)
    }

    fun ipOnly(cidr: String): String = cidr.substringBefore('/')

    /** UInt host order → 点分十进制（FrameConverter/RoutePlanner 共用） */
    fun dotted(ip: UInt): String {
        val a = (ip shr 24) and 0xffu
        val b = (ip shr 16) and 0xffu
        val c = (ip shr 8) and 0xffu
        val d = ip and 0xffu
        return "$a.$b.$c.$d"
    }
}

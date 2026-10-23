package cn.imgozi.planetport.tunnel

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Builder.addRoute 直接可用的路由条目 */
internal class RouteEntry(val addr: String, val prefix: Int)

/** 网络序 IP 前缀（IPv4 用 4 字节，IPv6 用 16 字节） */
internal class IPPrefix(val bytes: ByteArray, val length: Int) {
    override fun equals(other: Any?): Boolean =
        other is IPPrefix && length == other.length && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * length + bytes.contentHashCode()

    /** self ⊇ other（other 完全落在 self 网段内） */
    fun contains(other: IPPrefix): Boolean {
        if (length > other.length) return false
        for (i in 0 until length) if (bit(i) != other.bit(i)) return false
        return true
    }

    /** 一分为二（二叉前缀分解用；length < 128 保证不越界） */
    fun split(): Pair<IPPrefix, IPPrefix> {
        val byteIndex = length / 8
        val mask = (1 shl (7 - length % 8))
        val left = bytes.copyOf(); val right = bytes.copyOf()
        left[byteIndex] = (left[byteIndex].toInt() and mask.inv() and 0xff).toByte()
        right[byteIndex] = (right[byteIndex].toInt() or mask and 0xff).toByte()
        return IPPrefix(left, length + 1) to IPPrefix(right, length + 1)
    }

    private fun bit(i: Int): Int = (bytes[i / 8].toInt() shr (7 - i % 8)) and 1
}

/**
 * 路由数学与物理接口探测（纯函数无状态，对齐 iOS RoutePlanner）：
 * CIDR 换算、前缀差集（挖除物理 on-link 子网）、物理接口枚举。
 * Android 无 excludedRoutes 概念——挖洞同样用差集展开（Builder 只会
 * addRoute「抓进 TUN」的前缀，差集展开后物理网段自然留在物理网卡）。
 */
internal object RoutePlanner {

    private fun subtract(dest: IPPrefix, hole: IPPrefix): List<IPPrefix> {
        if (dest == hole || hole.contains(dest)) return emptyList()
        if (!dest.contains(hole)) return listOf(dest)
        val (left, right) = dest.split()
        return subtract(left, hole) + subtract(right, hole)
    }

    /** IPv4 CIDR（可带 "via 网关"）挖除若干前缀后展开 */
    fun expandV4(r: String, holes: List<IPPrefix>): List<RouteEntry> {
        val dest = r.split(' ').firstOrNull() ?: r
        val ip = NetworkContextMath.parseIPv4(NetworkContextMath.ipOnly(dest)) ?: return emptyList()
        var result = listOf(
            IPPrefix(
                byteArrayOf(
                    ((ip shr 24) and 0xffu).toByte(), ((ip shr 16) and 0xffu).toByte(),
                    ((ip shr 8) and 0xffu).toByte(), (ip and 0xffu).toByte(),
                ),
                prefixLen(dest).coerceAtMost(32),
            )
        )
        for (hole in holes) result = result.flatMap { subtract(it, hole) }
        return result.map { RouteEntry(v4String(it.bytes), it.length) }
    }

    fun expandV6(r: String, holes: List<IPPrefix>): List<RouteEntry> {
        val dest = r.split(' ').firstOrNull() ?: r
        val addr = NetworkContextMath.parseIPv6(NetworkContextMath.ipOnly(dest)) ?: return emptyList()
        var result = listOf(IPPrefix(addr.bytes, prefixLen(dest).coerceAtMost(128)))
        for (hole in holes) result = result.flatMap { subtract(it, hole) }
        return result.map { RouteEntry(v6String(it.bytes), it.length) }
    }

    /**
     * 物理接口 on-link IPv4 子网 → 待挖除前缀集合。ZT managed routes
     * （如 0.0.0.0/0）会把本地网关/DNS 抓进 TUN 黑洞，挖除后本地子网
     * 仍走物理网卡。excludingNetworksOf：子网与 ZT 分配地址所在网段相同
     * 时跳过，避免反过来打断 ZeroTier。
     */
    fun physicalOnLinkPrefixes4(excludingNetworksOf: List<String>): List<IPPrefix> {
        val assignedNetworks = excludingNetworksOf.map { networkCIDR4(it) }.toHashSet()
        val seen = HashSet<String>()
        val prefixes = ArrayList<IPPrefix>()
        forEachPhysicalInterfaceAddress { address, prefix ->
            if (address !is Inet4Address) return@forEachPhysicalInterfaceAddress
            val network = maskBytes(address.address, prefix)
            val cidr = "${v4String(network)}/$prefix"
            if (cidr in seen || cidr in assignedNetworks) return@forEachPhysicalInterfaceAddress
            seen.add(cidr)
            prefixes.add(IPPrefix(network, prefix))
        }
        return prefixes
    }

    /** 物理接口 on-link IPv6 子网（跳过 fe80:: 链路本地——本就不经默认路由） */
    fun physicalOnLinkPrefixes6(excludingNetworksOf: List<String>): List<IPPrefix> {
        val assignedNetworks = excludingNetworksOf.map { networkCIDR6(it) }.toHashSet()
        val seen = HashSet<String>()
        val prefixes = ArrayList<IPPrefix>()
        forEachPhysicalInterfaceAddress { address, prefix ->
            if (address !is Inet6Address) return@forEachPhysicalInterfaceAddress
            if (address.isLinkLocalAddress) return@forEachPhysicalInterfaceAddress
            val network = maskBytes(address.address, prefix)
            val cidr = "${v6String(network)}/$prefix"
            if (cidr in seen || cidr in assignedNetworks) return@forEachPhysicalInterfaceAddress
            seen.add(cidr)
            prefixes.add(IPPrefix(network, prefix))
        }
        return prefixes
    }

    /** 枚举物理接口（wlan/eth/rmnet/usb——排除 lo/tun 等虚拟接口）的全部接口地址 */
    private inline fun forEachPhysicalInterfaceAddress(block: (InetAddress, Int) -> Unit) {
        val nifs = try {
            NetworkInterface.getNetworkInterfaces()
        } catch (e: Exception) {
            null
        } ?: return
        for (nif in nifs) {
            if (!nif.isUp || nif.isLoopback) continue
            val name = nif.name
            if (!(name.startsWith("wlan") || name.startsWith("eth") ||
                    name.startsWith("rmnet") || name.startsWith("usb"))) continue
            for (ia in nif.interfaceAddresses) {
                val a = ia.address ?: continue
                val prefix = ia.networkPrefixLength.toInt()
                // 点对点接口返回 -1（含 0 视为无效）——IPPrefix(-1) 的 contains 恒真，
                // 会把全部 ZT 路由挖空（iOS 从 netmask 推前缀故免疫此坑）
                if (prefix <= 0) continue
                block(a, prefix)
            }
        }
    }

    private fun maskBytes(bytes: ByteArray, prefix: Int): ByteArray {
        val out = bytes.copyOf()
        for (bit in prefix.coerceAtLeast(0) until out.size * 8) {
            out[bit / 8] = (out[bit / 8].toInt() and (1 shl (7 - bit % 8)).inv() and 0xff).toByte()
        }
        return out
    }

    // MARK: - CIDR 换算

    fun ipOnly(cidr: String): String = cidr.substringBefore('/')

    fun isDefaultRoute(route: String): Boolean {
        val destination = route.split(' ').firstOrNull() ?: route
        return destination == "0.0.0.0/0" || destination == "::/0"
    }

    /** "fd00::/24" → 24（无前缀按 64，对齐 iOS） */
    fun prefixLen(cidr: String): Int = cidr.substringAfter('/', "64").toIntOrNull() ?: 64

    fun networkCIDR4(cidr: String): String {
        val prefix = prefixLen(cidr).coerceAtMost(32)
        val ip = NetworkContextMath.parseIPv4(ipOnly(cidr)) ?: return cidr
        val mask: UInt = if (prefix == 0) 0u else UInt.MAX_VALUE shl (32 - prefix)
        return "${NetworkContextMath.dotted(ip and mask)}/$prefix"
    }

    fun networkCIDR6(cidr: String): String {
        val prefix = prefixLen(cidr).coerceAtMost(128)
        val addr = NetworkContextMath.parseIPv6(ipOnly(cidr)) ?: return cidr
        return "${v6String(maskBytes(addr.bytes, prefix))}/$prefix"
    }

    private fun v4String(bytes: ByteArray): String =
        bytes.joinToString(".") { (it.toInt() and 0xff).toString() }

    private fun v6String(bytes: ByteArray): String =
        try {
            InetAddress.getByAddress(bytes).hostAddress ?: "::"
        } catch (e: Exception) {
            "::"
        }
}

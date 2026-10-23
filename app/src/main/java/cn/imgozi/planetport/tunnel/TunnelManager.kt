package cn.imgozi.planetport.tunnel

import android.net.VpnService
import cn.imgozi.planetport.store.AppConfigStore
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.store.NetworkEntry
import com.zerotier.sdk.VirtualNetworkConfigOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * netconf 聚合与 TUN 建立器（对齐 iOS PacketTunnelProvider 的聚合应用路径）。
 *
 * 配置回调只登记快照；合并、冲突检测、Builder 组装与 establish 统一在
 * configMutex 内串行（iOS 靠 configQueue + setTunnelNetworkSettings 异步
 * 单飞；Android establish 同步返回，mutex 串行即等价，无需 dirty 机制）。
 * 去抖 300ms：连续配置回调合并为一次应用。
 */
internal class TunnelManager(
    private val scope: CoroutineScope,
    private val runtime: NodeRuntime,
    private val converter: FrameConverter,
    private val dataplane: Dataplane,
    private val store: AppConfigStore,
    // Builder 是 VpnService 的非静态内部类，构造必须挂在外层实例上（UdpCom 同模式）
    private val vpnService: VpnService,
) {
    private val configMutex = Mutex()
    private var snapshots = HashMap<Long, NetConf>()
    private var settingsJob: Job? = null
    private var joinedNetworks = HashSet<Long>()
    private var orbitedMoons = HashSet<Long>()
    /** 已应用的用户侧配置（nwid → Triple(routeVia, dnsMode, customDns)），变更热重配 */
    private var appliedEntries = HashMap<Long, Triple<Boolean, DnsMode, List<String>>>()
    private var configChangesJob: Job? = null
    private var keepaliveJob: Job? = null

    fun start() {
        // 配置变更订阅：P1 configChanges 有损合并（16 溢出即丢），
        // 订阅方必须 level-triggered 全量重读——applyConfig 每次读文件现算
        configChangesJob = scope.launch {
            store.configChanges.collect { applyConfig() }
        }
        // 闲置保活：45s 周期广播（防 peer 路径/NAT 冷却死亡，对齐 iOS keepaliveTimer）
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(45_000)
                converter.sendKeepalive()
            }
        }
    }

    fun stop() {
        configChangesJob?.cancel(); configChangesJob = null
        keepaliveJob?.cancel(); keepaliveJob = null
        settingsJob?.cancel(); settingsJob = null
        snapshots = HashMap()
    }

    /**
     * 配置回调（消费协程调用）。状态所有回调都上报；快照只在
     * 「UP(1)/CONFIG_UPDATE(2) 且带地址」时登记——
     * 核心语义：端口首次初始化后的第一份完整 netconf 以 UP 回调，仅认
     * CONFIG_UPDATE 会漏掉首次加入的网络（磁盘有缓存后控制器重推才变 2，
     * 表现为“新网络永远不生效，重开一次就好”）；DOWN(3)/DESTROY(4) 的
     * 回调可能仍带旧地址，误存会把已离开网络的残留配置塞回快照。
     */
    suspend fun onNetworkConfig(nwid: Long, op: VirtualNetworkConfigOperation, conf: NetConf) {
        TunnelStateBus.setNetworkStatus("%016x".format(nwid), conf.status)
        TunnelStateBus.log(
            "cfg %016x op=%s status=%d addrs=%d".format(nwid, op.name, conf.status, conf.assignedAddrs.size)
        )
        if (op != VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_UP &&
            op != VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_CONFIG_UPDATE
        ) return
        if (conf.assignedAddrs.isEmpty()) return
        configMutex.withLock {
            snapshots[nwid] = conf
            scheduleApplyLocked()
        }
    }

    /** 去抖：连续配置回调合并为一次应用（300ms 窗口） */
    private fun scheduleApplyLocked() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            delay(300)
            applyAggregated()
        }
    }

    /**
     * 配置同步（首启 + configChanges 回调共用）：join/leave/orbit 与
     * 用户侧配置热重配。level-triggered——每次全量重读文件现算差分。
     */
    suspend fun applyConfig() = configMutex.withLock {
        val networks = store.readActiveNetworks()
        val moons = store.readActiveMoons()
        val wanted = networks.filter { it.lastActivated }
            .mapNotNull { it.networkId.toULongOrNull(16)?.toLong() }
            .toHashSet()
        for (nwid in wanted) if (nwid !in joinedNetworks) {
            runtime.join(nwid)
            joinedNetworks.add(nwid)
            TunnelStateBus.log("join %016x".format(nwid))
        }
        for (nwid in joinedNetworks.toList()) if (nwid !in wanted) {
            runtime.leave(nwid)
            joinedNetworks.remove(nwid)
            converter.removeNetwork(nwid)
            snapshots.remove(nwid)
            TunnelStateBus.log("leave %016x".format(nwid))
            scheduleApplyLocked()
        }
        for (moon in moons) {
            val worldId = moon.worldId.toLong()
            if (worldId !in orbitedMoons) {
                runtime.orbit(worldId, moon.seed.toLong())
                orbitedMoons.add(worldId)
            }
        }
        // 用户侧配置（默认路由/DNS）变更 → 用核心当前配置热重套（对齐 iOS appliedEntries）
        for (nwid in joinedNetworks) {
            val entry = networks.firstOrNull {
                it.networkId.toULongOrNull(16)?.toLong() == nwid
            } ?: continue
            val applied = Triple(entry.routeViaZeroTier, entry.dnsMode, entry.customDns)
            if (appliedEntries[nwid] != applied) {
                appliedEntries[nwid] = applied
                runtime.networkConf(nwid)?.let { upsertSnapshotLocked(nwid, it) }
            }
        }
    }

    /** onNetworkConfig 锁内变体用的快照登记（Mutex 非重入，P1 教训） */
    private fun upsertSnapshotLocked(nwid: Long, conf: NetConf) {
        TunnelStateBus.setNetworkStatus("%016x".format(nwid), conf.status)
        if (conf.assignedAddrs.isEmpty()) return
        snapshots[nwid] = conf
        scheduleApplyLocked()
    }

    /** 聚合应用：冲突检测 → 转换器上下文 → 合并地址/路由/MTU/DNS → 单次 establish */
    private suspend fun applyAggregated() = configMutex.withLock {
        val entries = store.readActiveNetworks().filter { it.lastActivated }
        val disableIpv6 = store.disableIpv6()

        class Item(val nwid: Long, val entry: NetworkEntry, val conf: NetConf,
                   val v4: List<String>, val v6: List<String>)

        // 没有可用地址的配置不进数据面（残缺设置同样会黑洞物理路由）
        val items = entries.mapNotNull { entry ->
            val nwid = entry.networkId.toULongOrNull(16)?.toLong() ?: return@mapNotNull null
            val conf = snapshots[nwid] ?: return@mapNotNull null
            val v4 = conf.assignedAddrs.filter { it.contains('.') }
            val v6 = if (disableIpv6) emptyList() else conf.assignedAddrs.filter { it.contains(':') }
            if (v4.isEmpty() && v6.isEmpty()) return@mapNotNull null
            Item(nwid, entry, conf, v4, v6)
        }

        // 冲突检测（后进入者让路）→ 转换器上下文（冲突网络不建，保留 join 仅展示状态）
        val conflicts = NetworkContextMath.detectConflicts(
            items.map { Triple(it.nwid, it.v4, it.v6) }
        )
        for (item in items) {
            converter.updateNetworkInfo(
                item.nwid, enabled = !conflicts.containsKey(item.nwid),
                mac = item.conf.mac, ipv4 = item.v4, ipv6 = item.v6, routes = item.conf.routes,
            )
        }
        // 收敛：清理本次快照之外的网络上下文（读文件与 leave 并发时的幽灵路由）
        converter.retainOnly(items.map { it.nwid }.toHashSet())
        val valid = items.filter { !conflicts.containsKey(it.nwid) }
        TunnelStateBus.log(
            "apply items=${items.size} valid=${valid.size} conflicts=${conflicts.size} " +
                "快照=${snapshots.keys.sorted().joinToString(",") { "%016x".format(it) }}"
        )
        if (valid.isEmpty()) {
            TunnelStateBus.setApplyTrace(
                "items=${items.size} valid=0（无可用网络，未套设置）\n" + converter.debugSummary()
            )
            return@withLock
        }

        // 多网络下默认路由一律不生效；单网络按用户选择（对齐 iOS routeVia）
        val routeVia = valid.size == 1 && valid[0].entry.routeViaZeroTier
        val allV4 = valid.flatMap { it.v4 }
        val allV6 = valid.flatMap { it.v6 }
        // 物理 on-link 子网挖除：ZT managed routes 会覆盖本机物理局域网
        val v4Holes = RoutePlanner.physicalOnLinkPrefixes4(allV4)
        val v6Holes = RoutePlanner.physicalOnLinkPrefixes6(allV6)

        val v4Routes = ArrayList<RouteEntry>()
        val seenV4 = HashSet<String>()
        fun appendV4(routes: List<RouteEntry>) {
            for (r in routes) if (seenV4.add("${r.addr}/${r.prefix}")) v4Routes.add(r)
        }
        val v6Routes = ArrayList<RouteEntry>()
        val seenV6 = HashSet<String>()
        fun appendV6(routes: List<RouteEntry>) {
            for (r in routes) if (seenV6.add("${r.addr}/${r.prefix}")) v6Routes.add(r)
        }
        if (routeVia) {
            appendV4(RoutePlanner.expandV4("0.0.0.0/0", v4Holes))
            appendV6(RoutePlanner.expandV6("::/0", v6Holes))
        }
        for (item in valid) {
            // 分配地址的直连网段（分配地址本身不会自动保证其网段进 TUN）
            for (address in item.v4) {
                val cidr = RoutePlanner.networkCIDR4(address)
                appendV4(listOf(RouteEntry(RoutePlanner.ipOnly(cidr), RoutePlanner.prefixLen(cidr))))
            }
            for (address in item.v6) {
                val cidr = RoutePlanner.networkCIDR6(address)
                appendV6(listOf(RouteEntry(RoutePlanner.ipOnly(cidr), RoutePlanner.prefixLen(cidr))))
            }
            // managed 路由：默认路由仅在 routeVia 时纳入（非 routeVia 不抢物理默认路由）
            for (r in item.conf.routes) {
                if (r.contains('.') && (routeVia || !RoutePlanner.isDefaultRoute(r))) {
                    appendV4(RoutePlanner.expandV4(r, v4Holes))
                }
                if (r.contains(':') && (routeVia || !RoutePlanner.isDefaultRoute(r))) {
                    appendV6(RoutePlanner.expandV6(r, v6Holes))
                }
            }
        }

        val trace = buildString {
            append("items=${items.size} valid=${valid.size}\n")
            for (i in items) {
                append("item %016x v4=${i.v4} v6=${i.v6} conflict=${conflicts[i.nwid] ?: "-"}\n"
                    .format(i.nwid))
            }
            append("includedV4=" + v4Routes.joinToString(",") { "${it.addr}/${it.prefix}" } + "\n")
            append("includedV6=" + v6Routes.joinToString(",") { "${it.addr}/${it.prefix}" } + "\n")
            append(converter.debugSummary())
        }

        // Builder 组装（单网络 DNS 三模式；多网络不设——默认路由已禁用，ZT DNS 无意义）
        val builder = vpnService.Builder()
            .setSession("Planet Port")
            .setMtu(valid.map { it.conf.mtu }.filter { it > 0 }.minOrNull() ?: 2800)
        for (a in allV4) builder.addAddress(RoutePlanner.ipOnly(a), RoutePlanner.prefixLen(a))
        for (a in allV6) builder.addAddress(RoutePlanner.ipOnly(a), RoutePlanner.prefixLen(a))
        for (r in v4Routes) builder.addRoute(r.addr, r.prefix)
        for (r in v6Routes) builder.addRoute(r.addr, r.prefix)
        if (valid.size == 1) {
            val item = valid[0]
            val servers: List<String> = when (item.entry.dnsMode) {
                DnsMode.network -> item.conf.dns
                DnsMode.custom -> item.entry.customDns
                DnsMode.none -> emptyList()
            }
            for (dns in servers) builder.addDnsServer(dns)
        }
        val fd = withContext(Dispatchers.IO) { builder.establish() }
        if (fd == null) {
            TunnelStateBus.log("establish 失败（Builder 拒绝，多为路由/地址冲突）")
            TunnelStateBus.setTunnelState(TunnelState.Failed("TUN 建立失败", recoverable = true))
            return@withLock
        }
        dataplane.swap(fd)
        // 组播订阅：broadcast + ADI=各网络首个 IPv4（ARP 可靠性的关键，SDK 文档要求）
        for (item in valid) {
            val adi = item.v4.firstOrNull()
                ?.let { NetworkContextMath.parseIPv4(RoutePlanner.ipOnly(it)) } ?: continue
            runtime.multicastSubscribe(item.nwid, Ethernet.BROADCAST_MAC, adi.toLong())
        }
        TunnelStateBus.setApplyTrace(trace)
        TunnelStateBus.log("establish 成功")
    }
}

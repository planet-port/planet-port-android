package cn.imgozi.planetport.tunnel

import com.zerotier.sdk.DataStoreGetListener
import com.zerotier.sdk.DataStorePutListener
import com.zerotier.sdk.Event
import com.zerotier.sdk.EventListener
import com.zerotier.sdk.Node
import com.zerotier.sdk.PacketSender
import com.zerotier.sdk.ResultCode
import com.zerotier.sdk.VirtualNetworkConfig
import com.zerotier.sdk.VirtualNetworkConfigListener
import com.zerotier.sdk.VirtualNetworkConfigOperation
import com.zerotier.sdk.VirtualNetworkFrameListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

/** 网络配置快照（字段契约对齐 iOS ZTNetworkConfigData；status 存原始码，type 为枚举名） */
internal class NetConf(
    val nwid: Long,
    val name: String,
    val status: Int,
    /** "PRIVATE"/"PUBLIC"（ZT_VirtualNetworkType 枚举名去 NETWORK_TYPE_ 前缀；未知为空串） */
    val type: String,
    val mac: Long,
    val mtu: Int,
    val broadcastEnabled: Boolean,
    val bridge: Boolean,
    val dhcp: Boolean,
    /** "ip/prefix"（prefix 在 InetSocketAddress.port 字段，SDK JNI 已转主机序数值） */
    val assignedAddrs: List<String>,
    /** "dest/prefix" 或 "dest/prefix via gw" */
    val routes: List<String>,
    /** DNS server IP（不含端口） */
    val dns: List<String>,
)

/** 网络配置回调事件（JNI 线程投递，消费协程处理） */
internal class NetConfEvent(
    val nwid: Long,
    val op: VirtualNetworkConfigOperation,
    val conf: NetConf,
)

/** 帧回调事件（帧数据不含以太网头，SDK 语义同 iOS C API） */
internal class FrameEvent(
    val nwid: Long,
    val srcMac: Long,
    val dstMac: Long,
    val etherType: Int,
    val vlanId: Int,
    val frame: ByteArray,
)

/**
 * ZeroTier 核心运行时（对齐 iOS ZTBridge 职责）：Node 创建/销毁、
 * dataStore 读写 hook（planet 重定向 + 按 planet 隔离）、后台任务驱动、
 * join/leave/orbit、帧与配置回调投递。
 *
 * 回调跑在 ZT 原生线程（attach 由 ZT_jniguard 保证），实现只做
 * Channel.trySend / 内存字段写，禁止任何阻塞或锁等待。
 */
internal class NodeRuntime(
    private val dataStoreRoot: File,
    private val planetDir: File,
    private val planetFile: File?,
    private val scope: CoroutineScope,
    /**
     * 节点身份冲突（EVENT_FATAL_ERROR_IDENTITY_COLLISION）的失败原因文案。
     * 本类不持 Context，文案由持 Context 的装配方（ZeroTierVpnService）注入；
     * reason 经 HomeViewModel 启动失败弹窗直显用户，须为本地化资源串
     * （iOS tunnel.status.fatalConflict 同义）。
     */
    private val identityCollisionReason: String,
) {
    val frameChannel = Channel<FrameEvent>(Channel.UNLIMITED)
    val configChannel = Channel<NetConfEvent>(Channel.UNLIMITED)

    var node: Node? = null
        private set

    /** 后台任务驱动协程（shutdown 时先 cancel + join 再 close node，防 native UAF） */
    private var supervisorJob: Job? = null

    // MARK: - 生命周期

    /** @Synchronized：start/shutdown 互斥——stopTunnel 与启动协程 finally 并发双入
     *  shutdown 时（STOP 落在启动挂起点），无锁会双方都读到非 null node 各调一次
     *  close() → native double free。串行化后第二次进入读到 null 自然幂等。 */
    @Synchronized
    fun start(sender: PacketSender): Boolean {
        val n = Node(System.currentTimeMillis())
        val rc = n.init(
            StoreGetListener(), StorePutListener(), sender,
            EventListenerImpl(), FrameListenerImpl(), ConfigListenerImpl(), null,
        )
        if (rc != ResultCode.RESULT_OK) {
            n.close()
            TunnelStateBus.log("node_init 失败 rc=$rc")
            return false
        }
        node = n
        // 启动即驱动一次后台任务（对齐 iOS startWithDataStorePath：identity 生成/装载在此发生）
        n.processBackgroundTasks(System.currentTimeMillis(), longArrayOf(0))
        TunnelStateBus.setNodeStatus(
            NodeStatusInfo(nodeId = "%010x".format(n.address()), online = false, coreVersion = version())
        )
        TunnelStateBus.log("核心已启动 nodeId=%010x version=%s".format(n.address(), version()))
        startSupervisor()
        return true
    }

    @Synchronized
    fun shutdown() {
        // 先停后台驱动协程并等其真正退出，再 close node：协程仍处
        // processBackgroundTasks JNI 内时 close 会 native UAF。JNI 调用毫秒级，
        // runBlocking/join 的短暂阻塞（≤一个驱动周期）在停机路径可接受
        val job = supervisorJob
        supervisorJob = null
        job?.cancel()
        if (job != null) runBlocking { job.join() }
        node?.close()
        node = null
    }

    /** 后台任务 deadline 驱动（对齐 iOS bgTimer：50..100ms 自适应窗口） */
    private fun startSupervisor() {
        supervisorJob = scope.launch(Dispatchers.IO) {
            val deadline = longArrayOf(0)
            while (isActive) {
                val n = node ?: break
                val rc = n.processBackgroundTasks(System.currentTimeMillis(), deadline)
                if (rc != ResultCode.RESULT_OK) {
                    // 对齐 P4 终审 B-3：此前静默 break 会把 TunnelState 悬在 Running，
                    // 用户看到假在线；先置 Failed 再退出驱动循环。
                    // recoverable = true：服务进程仍活（scope/通知在），用户可显式停止，
                    // 切网自愈由 NetWatch 链路在下次网络事件重启核心；keepalive/peers
                    // 协程不连带收口——Failed 存活态下 keepalive 温热 UDP 无害，统一
                    // 收口归服务 stopTunnel 既有链路。
                    // reason 为技术串（本类无 Context 无法本地化），HomeViewModel 消费侧
                    // 按 "core_driver:" 前缀映射通用失败文案，不直显用户。
                    TunnelStateBus.log("processBackgroundTasks rc=$rc，停止驱动")
                    TunnelStateBus.setTunnelState(
                        TunnelState.Failed("core_driver:$rc", recoverable = true),
                    )
                    break
                }
                val wait = (deadline[0] - System.currentTimeMillis()).coerceIn(50, 100)
                delay(wait)
            }
        }
    }

    // MARK: - 控制与数据入口

    fun join(nwid: Long) { node?.join(nwid) }
    fun leave(nwid: Long) { node?.leave(nwid) }
    fun orbit(worldId: Long, seed: Long) { node?.orbit(worldId, seed) }
    fun deorbit(worldId: Long) { node?.deorbit(worldId) }
    fun multicastSubscribe(nwid: Long, group: Long, adi: Long) {
        node?.multicastSubscribe(nwid, group, adi)
    }

    fun address(): Long = node?.address() ?: 0L

    fun version(): String {
        val v = node?.getVersion() ?: return ""
        return "${v.major}.${v.minor}.${v.revision}"
    }

    /** UDP 收包入口（UdpCom 收包线程调用；线程安全：C 核心内部有锁） */
    fun processWirePacket(data: ByteArray, remote: InetSocketAddress): Boolean {
        val n = node ?: return false
        val rc = n.processWirePacket(System.currentTimeMillis(), -1, remote, data, longArrayOf(0))
        return rc == ResultCode.RESULT_OK
    }

    /** TUN 帧入口（Dataplane 读线程经 FrameConverter 调用） */
    fun processVirtualNetworkFrame(
        nwid: Long, srcMac: Long, dstMac: Long, etherType: Int, vlanId: Int, frame: ByteArray,
    ) {
        val n = node ?: return
        n.processVirtualNetworkFrame(
            System.currentTimeMillis(), nwid, srcMac, dstMac, etherType, vlanId,
            frame, longArrayOf(0),
        )
    }

    /** 当前网络配置快照（applyConfig 热重配路径用，对齐 iOS bridge.networkConfig） */
    fun networkConf(nwid: Long): NetConf? = node?.networkConfig(nwid)?.toNetConf()

    /**
     * 当前 peer 快照（JNI 直查，对齐 iOS bridge.peers）。node 未启动返回空列表。
     * 数据源仅供隧道侧：5s 周期推流见 TunnelManager.peersJob，applyTrace 摘要行
     * 同样直查此入口（Peers 页无下拉刷新直查，只订阅推流）。
     */
    fun peersNow(): List<PeerInfo> {
        val n = node ?: return emptyList()
        val peers = n.peers() ?: return emptyList()
        return peers.mapNotNull { p ->
            if (p == null) return@mapNotNull null
            PeerInfo(
                address = p.address,
                // 对齐 iOS ZTBridge.peers：版本三段直拼（未知 -1 时为 "v-1.-1.-1"，
                // 与 iOS 同构；语义为「版本未知」，UI 层自行判别）
                version = "v${p.versionMajor}.${p.versionMinor}.${p.versionRev}",
                latency = p.latency,
                // ZT_PeerRole 枚举名去 PEER_ROLE_ 前缀："LEAF"/"MOON"/"PLANET"
                //（iOS 传输层为小写 leaf/moon/planet，UI 层展示时自行大小写处理）
                role = p.role?.name?.removePrefix("PEER_ROLE_") ?: "",
                // 物理路径 "ip:port"；空列表 = relay 中转（对齐 iOS path 空串语义）
                paths = p.paths?.mapNotNull { pp ->
                    val host = pp?.address?.address?.hostAddress ?: return@mapNotNull null
                    "$host:${pp.address.port}"
                } ?: emptyList(),
            )
        }
    }

    // MARK: - dataStore 文件映射（Global Constraints 路径契约）

    /**
     * dataStore 对象名 → 落盘文件。返回 null 表示未知对象（读写各按错误码上报）。
     * identity 跨 planet 共享（files/zt/）；moon/peer/netconf 按激活 planet 隔离
     * （files/zt/<planetId>/）；planet 在自定义 planet 时重定向到导入文件
     * （官方 planet 时读核心内置——文件不存在 stateGet 返回 -1 即可）。
     */
    internal fun stateFile(name: String): File? = when {
        name == "identity.public" || name == "identity.secret" -> File(dataStoreRoot, name)
        name == "planet" -> planetFile ?: File(dataStoreRoot, "planet")
        name.startsWith("moons.d/") || name.startsWith("networks.d/") ||
            name.startsWith("peers.d/") -> File(planetDir, name)
        else -> null
    }

    private inner class StoreGetListener : DataStoreGetListener {
        override fun onDataStoreGet(name: String, outBuffer: ByteArray?): Long {
            val f = stateFile(name) ?: return -2
            val data = try {
                if (f.exists()) f.readBytes() else return -1
            } catch (e: IOException) {
                return -2
            }
            if (outBuffer == null) return data.size.toLong()
            val n = minOf(data.size, outBuffer.size)
            System.arraycopy(data, 0, outBuffer, 0, n)
            return n.toLong()
        }
    }

    private inner class StorePutListener : DataStorePutListener {
        override fun onDataStorePut(name: String, buffer: ByteArray?, secure: Boolean): Int {
            val f = stateFile(name) ?: return -1
            val bytes = buffer ?: return -1
            return try {
                f.parentFile?.mkdirs()
                // 原子写（对齐 iOS NSDataWritingAtomic）：核心状态文件崩溃残留半文件
                // 会导致下次启动解析失败
                val tmp = File(f.path + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(f)) {
                    tmp.delete()
                    return -1
                }
                // 私钥文件 owner-only（0600）：Core 约定 identity.secret 属敏感对象
                // （secure=true），桌面版 ZeroTier One 同样收紧该文件权限。app 沙盒内
                // 其余 app 本不可达，此处防 root/adb 拉取与备份提取的纵深防御；
                // rename 后新文件权限来自 tmp，逐次收紧幂等（identity.secret 重写亦覆盖）
                if (secure || name == "identity.secret") {
                    f.setReadable(false, false)
                    f.setWritable(false, false)
                    f.setExecutable(false, false)
                    f.setReadable(true, true)
                    f.setWritable(true, true)
                }
                0
            } catch (e: IOException) {
                -1
            }
        }

        override fun onDelete(name: String): Int {
            stateFile(name)?.delete()
            return 0
        }
    }

    private inner class EventListenerImpl : EventListener {
        override fun onEvent(event: Event) {
            when (event) {
                Event.EVENT_ONLINE -> updateOnline(true)
                Event.EVENT_OFFLINE -> updateOnline(false)
                Event.EVENT_FATAL_ERROR_IDENTITY_COLLISION -> {
                    TunnelStateBus.setTunnelState(
                        TunnelState.Failed(identityCollisionReason, recoverable = false)
                    )
                    TunnelStateBus.log("FATAL_ERROR_IDENTITY_COLLISION")
                }
                else -> {}
            }
        }

        override fun onTrace(message: String?) {}
    }

    private fun updateOnline(online: Boolean) {
        val cur = TunnelStateBus.nodeStatus.value
        TunnelStateBus.setNodeStatus(
            NodeStatusInfo(
                nodeId = cur?.nodeId ?: "%010x".format(address()),
                online = online,
                coreVersion = cur?.coreVersion ?: version(),
            )
        )
        TunnelStateBus.log(if (online) "ONLINE" else "OFFLINE")
    }

    private inner class FrameListenerImpl : VirtualNetworkFrameListener {
        override fun onVirtualNetworkFrame(
            nwid: Long, srcMac: Long, destMac: Long, etherType: Long, vlanId: Long,
            frameData: ByteArray?,
        ) {
            val frame = frameData ?: return
            frameChannel.trySend(
                FrameEvent(nwid, srcMac, destMac, etherType.toInt(), vlanId.toInt(), frame)
            )
        }
    }

    private inner class ConfigListenerImpl : VirtualNetworkConfigListener {
        override fun onNetworkConfigurationUpdated(
            nwid: Long, op: VirtualNetworkConfigOperation, config: VirtualNetworkConfig?,
        ): Int {
            val conf = config ?: return 0
            configChannel.trySend(NetConfEvent(nwid, op, conf.toNetConf()))
            return 0
        }
    }

    // MARK: - VirtualNetworkConfig → NetConf（对齐 iOS ztConvertNetworkConfig）

    /**
     * SDK InetSocketAddress 语义：IP 装地址字段、prefix 装端口字段（核心 C API 约定）。
     * via 的「无网关」在 C 层为 family=0，JNI 转出后可能表现为 null 或全零地址，
     * 两者都按「无 via」处理（防御式，不依赖 JNI 边界行为）。
     */
    private fun VirtualNetworkConfig.toNetConf(): NetConf {
        val addrs = assignedAddresses?.mapNotNull { a ->
            a?.address?.hostAddress?.let { "$it/${a.port}" }
        } ?: emptyList()
        val routeList = routes?.mapNotNull { r ->
            val target = r?.target ?: return@mapNotNull null
            val dest = target.address?.hostAddress?.let { "$it/${target.port}" } ?: return@mapNotNull null
            val viaIp = r.via?.address?.hostAddress
            if (viaIp != null && viaIp != "0.0.0.0" && viaIp != "::") "$dest via $viaIp" else dest
        } ?: emptyList()
        val dnsList = dns?.servers?.mapNotNull { it?.address?.hostAddress } ?: emptyList()
        return NetConf(
            nwid = nwid,
            name = name ?: "",
            status = status?.ordinal ?: -1,
            type = type?.name?.removePrefix("NETWORK_TYPE_") ?: "",
            mac = mac,
            mtu = mtu,
            broadcastEnabled = isBroadcastEnabled,
            bridge = isBridge,
            dhcp = isDhcp,
            assignedAddrs = addrs,
            routes = routeList,
            dns = dnsList,
        )
    }
}

package cn.imgozi.planetport.tunnel

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date

/** 隧道宏观状态（语义对齐 iOS StatusStore.tunnelStatus 文案集合；文案在 UI 层生成） */
sealed interface TunnelState {
    data object Idle : TunnelState
    data object Starting : TunnelState
    data object Running : TunnelState
    data class Reconnecting(val reason: String) : TunnelState
    data class Stopped(val reason: String? = null) : TunnelState
    data class Failed(val reason: String, val recoverable: Boolean) : TunnelState
}

/** 节点信息快照（nodeId 十位十六进制、核心版本、在线位） */
data class NodeStatusInfo(
    val nodeId: String,
    val online: Boolean,
    val coreVersion: String,
)

/**
 * Peer 快照（对齐 iOS PeerEntry）。address 为 ZT 40 位节点地址原值；
 * version 形如 "v1.14.0"（版本未知时为 "v-1.-1.-1"，与 iOS 同构）；
 * role 取值为 "LEAF"/"MOON"/"PLANET"；paths 为物理路径 "ip:port" 列表，
 * 空列表 = relay 中转（对齐 iOS path 空串语义）。
 */
data class PeerInfo(
    val address: Long,
    val version: String,
    val latency: Int,
    val role: String,
    val paths: List<String>,
)

/**
 * 隧道状态总线（对齐 iOS StatusStore；同进程内存态，服务写、UI 读）。
 *
 * networkStatuses 存 ZT_VirtualNetworkStatus 原始码（0..6，键为 16 位十六进制 nwid）——
 * 不在隧道侧生成文案：契约与展示语言解耦，文案由 UI 层生成（对齐 iOS 跨进程契约约定）。
 * StateFlow 无 replay 语义差异问题（热流始终有当前值），但 configChanges 的有损合并
 * 契约同样适用：订阅方收到通知后必须全量重读，不得假设收到每一次变更。
 */
object TunnelStateBus {
    private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private val _nodeStatus = MutableStateFlow<NodeStatusInfo?>(null)
    val nodeStatus: StateFlow<NodeStatusInfo?> = _nodeStatus.asStateFlow()

    /**
     * 物理网络可用（NetWatch 路径表透出，对齐 iOS StatusStore.pathSatisfied）：
     * 飞行模式/断网即时为 false，卡片显示「无网络」；不影响开关与 VPN 状态。
     * 缺省 true（隧道未注册监听时不误显），停机复位防跨会话残留。
     */
    private val _pathSatisfied = MutableStateFlow(true)
    val pathSatisfied: StateFlow<Boolean> = _pathSatisfied.asStateFlow()

    /**
     * Core 版本缓存（编译期随核心二进制定死的静态常量，停机不清——对齐 iOS
     * StatusStore.coreVersion：核心启动时写入一次，stopTunnel 不清除，停机后
     * UI 仍显示最后已知版本）。随每份节点快照灌入（见 setNodeStatus）。
     */
    private val _coreVersion = MutableStateFlow("")
    val coreVersion: StateFlow<String> = _coreVersion.asStateFlow()

    private val _networkStatuses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val networkStatuses: StateFlow<Map<String, Int>> = _networkStatuses.asStateFlow()

    /**
     * 实时冲突表（键为 16 位十六进制 nwid → 冲突原因码 "address"/"subnet"；空表 = 无冲突，
     * 对齐 iOS StatusStore.networkConflicts）。键形态与 networkStatuses 一致——UI 侧以
     * NetworkEntry.networkId 直查。值是原始码：文案在 UI 层生成（跨层契约同上）。
     * 每次聚合应用全量替换（冲突解除的网络自然出表）；停机清空。
     */
    private val _networkConflicts = MutableStateFlow<Map<String, String>>(emptyMap())
    val networkConflicts: StateFlow<Map<String, String>> = _networkConflicts.asStateFlow()

    /** 网络配置快照（键为 16 位十六进制 nwid；对齐 iOS StatusStore.networkConfigs）。
     *  来源 = TunnelManager 聚合快照的同步镜像，仅供 UI 展示（详情页类型/桥接/广播/
     *  DHCP、地址/路由/DNS 列表）；不参与数据面聚合。停机清空。
     *  internal：元素类型 NetConf 是隧道模块内部契约（UI 同在 :app 模块，可正常订阅）。 */
    private val _networkConfigs = MutableStateFlow<Map<String, NetConf>>(emptyMap())
    internal val networkConfigs: StateFlow<Map<String, NetConf>> = _networkConfigs.asStateFlow()

    /** Peer 快照（5s 周期推流 + 停机清空；Peers 页只订阅此流，无直查入口） */
    private val _peers = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peers: StateFlow<List<PeerInfo>> = _peers.asStateFlow()

    /** 聚合应用诊断快照（排查“已连接但不通”，对齐 iOS StatusStore.applyTrace） */
    private val _applyTrace = MutableStateFlow("")
    val applyTrace: StateFlow<String> = _applyTrace.asStateFlow()

    /** 隧道事件滚动日志（最近 40 条，对齐 iOS StatusStore.eventLog） */
    private val _eventLog = MutableStateFlow("")
    val eventLog: StateFlow<String> = _eventLog.asStateFlow()

    fun setTunnelState(state: TunnelState) { _tunnelState.value = state }

    /** 物理路径可用性更新（NetWatch 候选表变化时即时调用，对齐 iOS 逐次透出） */
    fun setPathSatisfied(satisfied: Boolean) { _pathSatisfied.value = satisfied }

    /**
     * 停止指令在途：STOP intent 已下发、尚未落地为终态（intent 队列异步，StateFlow
     * 的 tunnelState 仍显示存活态）。供快速路径判定排除在途停止——关网络后立即
     * 重开时，isRunning 仍 true 会误走「只写配置」路径，无人拉起隧道、卡片卡
     * 「建立隧道」（iOS performStart 等待 .disconnecting 的对应缺口）。
     */
    @Volatile
    var stopInFlight = false

    /** 置 null（停机/启动失败）只清 nodeStatus，coreVersion 缓存留存供 UI 展示 */
    fun setNodeStatus(info: NodeStatusInfo?) {
        info?.coreVersion?.takeIf { it.isNotBlank() }?.let { _coreVersion.value = it }
        _nodeStatus.value = info
    }

    fun setNetworkStatus(nwidHex: String, status: Int) {
        _networkStatuses.value = _networkStatuses.value + (nwidHex to status)
    }

    fun clearNetworkStatuses() { _networkStatuses.value = emptyMap() }

    /** 冲突表全量替换（键 Long → 16 位十六进制字符串，与 networkStatuses 一致） */
    fun setNetworkConflicts(conflicts: Map<Long, String>) {
        _networkConflicts.value = conflicts.mapKeys { "%016x".format(it.key) }
    }

    fun clearNetworkConflicts() { _networkConflicts.value = emptyMap() }

    /** 网络配置快照全量同步（键 Long → 16 位十六进制字符串，与 networkStatuses 一致） */
    internal fun setNetworkConfigs(snapshots: Map<Long, NetConf>) {
        _networkConfigs.value = snapshots.mapKeys { "%016x".format(it.key) }
    }

    fun clearNetworkConfigs() { _networkConfigs.value = emptyMap() }

    fun setPeers(peers: List<PeerInfo>) { _peers.value = peers }

    fun clearPeers() { _peers.value = emptyList() }

    fun setApplyTrace(trace: String) { _applyTrace.value = trace }

    /** 低频事件直写（配置回调/聚合决策/错误），高频帧不走这里。
     *  同步透传 logcat（tag=PlanetPort）：eventLog 仅存内存 StateFlow，落 logcat
     *  后真机排查可直接 adb logcat，无需进诊断页看 UI（T7 用户裁定） */
    fun log(line: String) {
        Log.d("PlanetPort", line)
        val stamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
        val current = _eventLog.value
        val lines = (if (current.isEmpty()) emptyList() else current.split('\n')) + "$stamp $line"
        _eventLog.value = lines.takeLast(40).joinToString("\n")
    }
}

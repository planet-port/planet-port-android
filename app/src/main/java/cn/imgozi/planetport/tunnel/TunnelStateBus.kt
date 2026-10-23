package cn.imgozi.planetport.tunnel

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

    private val _networkStatuses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val networkStatuses: StateFlow<Map<String, Int>> = _networkStatuses.asStateFlow()

    /** 聚合应用诊断快照（排查“已连接但不通”，对齐 iOS StatusStore.applyTrace） */
    private val _applyTrace = MutableStateFlow("")
    val applyTrace: StateFlow<String> = _applyTrace.asStateFlow()

    /** 隧道事件滚动日志（最近 40 条，对齐 iOS StatusStore.eventLog） */
    private val _eventLog = MutableStateFlow("")
    val eventLog: StateFlow<String> = _eventLog.asStateFlow()

    fun setTunnelState(state: TunnelState) { _tunnelState.value = state }
    fun setNodeStatus(info: NodeStatusInfo?) { _nodeStatus.value = info }

    fun setNetworkStatus(nwidHex: String, status: Int) {
        _networkStatuses.value = _networkStatuses.value + (nwidHex to status)
    }

    fun clearNetworkStatuses() { _networkStatuses.value = emptyMap() }

    fun setApplyTrace(trace: String) { _applyTrace.value = trace }

    /** 低频事件直写（配置回调/聚合决策/错误），高频帧不走这里 */
    fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS").format(Date())
        val current = _eventLog.value
        val lines = (if (current.isEmpty()) emptyList() else current.split('\n')) + "$stamp $line"
        _eventLog.value = lines.takeLast(40).joinToString("\n")
    }
}

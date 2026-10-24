package cn.imgozi.planetport.ui

import androidx.annotation.StringRes
import cn.imgozi.planetport.R
import cn.imgozi.planetport.tunnel.TunnelState

/** 网络状态语义色（M3 ColorScheme 无状态槽位，映射见 ui.theme.PlanetStatusColors）。 */
enum class StatusColor { Good, Warn, Neutral, Danger }

/**
 * 网络卡片状态行模型（对齐 iOS NetworkCardView.swift:8-52 的 NetworkCardStatus）：
 * 文案 + 语义色。跨层只传原始码（与 iOS 跨进程契约一致），文案以 [StringRes] 承载、
 * 由 UI 层 stringResource 解析（三语）。[labelArgRes] 供带占位文案（如「%@,断开」），
 * 实参本身也是资源 ID（冲突原因文案），解析时先解实参再格式化。
 */
data class NetworkCardStatus(
    @StringRes val label: Int,
    val color: StatusColor,
    @StringRes val labelArgRes: Int? = null,
)

/**
 * ZT_VirtualNetworkStatus 原始码 → 文案资源（iOS Shared/Models.swift:16-54 全集）。
 * [code] 取值 0..6；-1 与其他未知值归入「未知」。UI 层用 [stringResource] 解析。
 */
@StringRes
fun ztStatusLabel(code: Int): Int = when (code) {
    0 -> R.string.network_status_requestingConfiguration
    1 -> R.string.network_status_ok
    2 -> R.string.network_status_accessDenied
    3 -> R.string.network_status_notFound
    4 -> R.string.network_status_portError
    5 -> R.string.network_status_clientTooOld
    6 -> R.string.network_status_authRequired
    else -> R.string.network_status_unknown
}

/**
 * [ztStatusLabel] 的字面量版（HomeViewModel 事件弹窗等非 Composable 上下文消费）。
 * 无 Context 无法本地化，暂为简体固定文案——VM 侧文案资源化归 T6b 领地移交项。
 */
fun ztStatusText(code: Int): String = when (code) {
    0 -> "正在初始化"
    1 -> "在线"
    2 -> "拒绝访问"
    3 -> "网络不存在"
    4 -> "端口错误"
    5 -> "客户端过旧"
    6 -> "需要认证"
    else -> "未知"
}

/** 隧道宏观状态文案（自 P2 脚手架页上移，脚手架页已退役；诊断用途，未接 UI）。 */
fun tunnelStateText(state: TunnelState): String = when (state) {
    is TunnelState.Idle -> "空闲"
    is TunnelState.Starting -> "启动中"
    is TunnelState.Running -> "运行中"
    is TunnelState.Reconnecting -> "重连中(${state.reason})"
    is TunnelState.Stopped -> "已停止${state.reason?.let { "($it)" } ?: ""}"
    is TunnelState.Failed -> "失败(${state.reason})"
}

/**
 * 网络卡片状态五级判定（优先级严格对齐 iOS 侦查 §2.5 / NetworkCardView.swift:8-52）：
 *
 * 1. `!vpnRunning` → lastActivated ? 「建立隧道」橙 : 「未连接」中性
 * 2. 实时冲突（liveConflict: "address"/"subnet"）→ 红「地址冲突/网段冲突」
 * 3. `!lastActivated` → 有 lastConflict 则红「<冲突原因>，断开」，否则「未连接」
 * 4. 有实时状态码（[hasStatus]）→ 待授权(2/6) 橙「待授权」；ok(1) 绿；其余橙（状态文案）
 * 5. 兜底「等待节点响应」中性
 *
 * 纯函数模型：只产出资源 ID，文案解析在 UI 层（stringResource）。
 *
 * @param liveConflict 当前会话的冲突原因码，null = 无冲突
 * @param lastConflict 上次会话遗留的冲突原因码，null = 无记录
 * @param statusCode   TunnelStateBus.networkStatuses 的实时原始码，null = 暂无
 * @param hasStatus    实时状态是否存在；默认由 [statusCode] 非空推出，保留参数以贴合
 *                     iOS「networkStatuses[nwid] 存在」的判定语义（调用方持有状态表时可用）
 */
fun deriveNetworkCardStatus(
    vpnRunning: Boolean,
    lastActivated: Boolean,
    lastConflict: String?,
    liveConflict: String?,
    statusCode: Int?,
    hasStatus: Boolean = statusCode != null,
): NetworkCardStatus {
    if (!vpnRunning) {
        return if (lastActivated) {
            NetworkCardStatus(R.string.status_establishing, StatusColor.Warn)
        } else {
            NetworkCardStatus(R.string.status_disconnected, StatusColor.Neutral)
        }
    }
    liveConflict?.takeIf { lastActivated }?.let {
        return NetworkCardStatus(conflictLabelRes(it), StatusColor.Danger)
    }
    if (!lastActivated) {
        return if (lastConflict != null) {
            NetworkCardStatus(
                R.string.card_conflict_offline,
                StatusColor.Danger,
                conflictLabelRes(lastConflict),
            )
        } else {
            NetworkCardStatus(R.string.status_disconnected, StatusColor.Neutral)
        }
    }
    if (hasStatus && statusCode != null) {
        if (statusCode == 2 || statusCode == 6) {
            return NetworkCardStatus(R.string.status_pendingAuth, StatusColor.Warn)
        }
        return NetworkCardStatus(
            label = ztStatusLabel(statusCode),
            color = if (statusCode == 1) StatusColor.Good else StatusColor.Warn,
        )
    }
    return NetworkCardStatus(R.string.status_waitingNode, StatusColor.Neutral)
}

/**
 * 冲突原因码 → 文案资源（NetworkEntry.lastConflict 约定 "address"/"subnet"）。
 * 「地址冲突/网段冲突」单发与「%@,断开」拼串两场景共用同一资源。
 */
private fun conflictLabelRes(code: String): Int = when (code) {
    "subnet" -> R.string.card_conflict_subnet
    else -> R.string.card_conflict_address
}

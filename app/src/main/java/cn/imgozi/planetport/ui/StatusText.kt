package cn.imgozi.planetport.ui

import cn.imgozi.planetport.tunnel.TunnelState

/** 网络状态语义色（M3 ColorScheme 无状态槽位，映射见 ui.theme.PlanetStatusColors）。 */
enum class StatusColor { Good, Warn, Neutral, Danger }

/**
 * 网络卡片状态行模型（对齐 iOS NetworkCardView.swift:8-52 的 NetworkCardStatus）：
 * 文案 + 语义色。文案在此生成、跨层只传原始码（与 iOS 跨进程契约一致）。
 */
data class NetworkCardStatus(
    val label: String,
    val color: StatusColor,
)

/**
 * ZT_VirtualNetworkStatus 原始码 → 文案（iOS Shared/Models.swift:16-54 全集）。
 * [code] 取值 0..6；-1 与其他未知值归入「未知」。
 *
 * 注：P2 脚手架页（ui/P2SelfCheck.kt）退役前，其文件内私有 ztStatusText 与本函数
 * 同包同名冲突，故此处更名；T3 换挂删除脚手架页后即为唯一文案映射入口。
 */
fun ztNetworkStatusText(code: Int): String = when (code) {
    0 -> "正在初始化"
    1 -> "在线"
    2 -> "拒绝访问"
    3 -> "网络不存在"
    4 -> "端口错误"
    5 -> "客户端过旧"
    6 -> "需要认证"
    -1 -> "未知"
    else -> "未知"
}

/** 隧道宏观状态文案（自 ui/P2SelfCheck.kt:39-46 上移，T3 换挂后脚手架页退役）。 */
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
 * 纯函数模型，本任务不接 UI（T3 网络卡消费）。
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
            NetworkCardStatus("建立隧道", StatusColor.Warn)
        } else {
            NetworkCardStatus("未连接", StatusColor.Neutral)
        }
    }
    liveConflict?.takeIf { lastActivated }?.let {
        return NetworkCardStatus(conflictLabel(it), StatusColor.Danger)
    }
    if (!lastActivated) {
        return if (lastConflict != null) {
            NetworkCardStatus("${conflictLabel(lastConflict)}，断开", StatusColor.Danger)
        } else {
            NetworkCardStatus("未连接", StatusColor.Neutral)
        }
    }
    if (hasStatus && statusCode != null) {
        if (statusCode == 2 || statusCode == 6) {
            return NetworkCardStatus("待授权", StatusColor.Warn)
        }
        return NetworkCardStatus(
            label = ztNetworkStatusText(statusCode),
            color = if (statusCode == 1) StatusColor.Good else StatusColor.Warn,
        )
    }
    return NetworkCardStatus("等待节点响应", StatusColor.Neutral)
}

/** 冲突原因码 → 文案（NetworkEntry.lastConflict 约定 "address"/"subnet"）。 */
private fun conflictLabel(code: String): String = when (code) {
    "subnet" -> "网段冲突"
    else -> "地址冲突"
}

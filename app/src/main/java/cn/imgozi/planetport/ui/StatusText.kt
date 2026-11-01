package cn.imgozi.planetport.ui

import androidx.annotation.StringRes
import cn.imgozi.planetport.R

/** lastConflict 原始码（跨层契约，对齐 iOS ConflictCode）：address/subnet/timeout
 *  （timeout 仅旧版本超时断开时的遗留记录，现不再写入，只为遗留记录保留展示与清理）。 */
const val CONFLICT_TIMEOUT = "timeout"

/**
 * 「连接服务器」阶段（核心连行星根服务器）的超时阈值：到点仍未 online 先复核，
 * 复核失败即重启核心进入下一轮（固定间隔、不限次数、无提示，直到 online 或
 * 用户手动关闭），不判定故障、不自动断开（对齐 iOS AppState.connectTimeout）。
 */
const val CONNECT_TIMEOUT_MS = 45_000L

/** 网络状态语义色（M3 ColorScheme 无状态槽位，映射见 ui.theme.PlanetStatusColors）。 */
enum class StatusColor { Good, Warn, Neutral, Danger }

/**
 * 网络卡片状态行模型（对齐 iOS NetworkCardView.swift 的 NetworkCardStatus）：
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
 * [code] 取值 0..6；-1 与其他未知值归入「未知」。Composable 用 [stringResource] 解析；
 * 非 Composable 上下文（HomeViewModel 事件弹窗等）经 Application context
 * `getString` 解析，VM 侧不再保留字面量版。
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
 * 网络卡片状态五级判定（优先级对齐 iOS NetworkCardView.swift（b4fb534 后终态））：
 *
 * 1. 非当前隧道行星（[isActivePlanet]）→ 一律「未连接」中性（开关为关，打开即
 *    切换行星，无需中间状态）
 * 2. 实时冲突（liveConflict: "address"/"subnet"）→ 红「地址冲突/网段冲突」
 * 3. `!lastActivated` → 有 lastConflict 则红「断开原因」；timeout 直显「连接超时」
 *    （共享故障不套「冲突,断开」模板，仅旧版本遗留记录），冲突原因走「<原因>，断开」
 *    模板；否则「未连接」
 * 4. `!vpnRunning` → 「建立隧道」橙（断开态判定先于 vpnRunning：自动断开伴随
 *    停隧道，先判 vpnRunning 会把原因吞成「建立隧道」，iOS b4fb534 同序修正）
 * 5. `!pathSatisfied` → 「无网络」橙（物理断网即时显示，优先于核心状态——
 *    netconf 缓存仍是 ok 会假亮「在线」，iOS 55945a7 同序）
 * 6. 有实时状态码（[hasStatus]）→ 待授权(2/6) 橙「待授权」；requestingConfiguration(0)
 *    按 online 拆段（[connectPhaseLabel]）；ok(1) 绿；其余橙（状态文案）
 * 7. 兜底「等待节点响应」中性
 *
 * 纯函数模型：只产出资源 ID，文案解析在 UI 层（stringResource）。
 *
 * @param isActivePlanet 卡片归属行星是否当前隧道行星；false 时无视其余参数直接
 *                       「未连接」（对齐 iOS 第一级——隧道运行中滑回残留
 *                       lastActivated 的旁页，不误显「等待节点响应」）
 * @param liveConflict 当前会话的冲突原因码，null = 无冲突
 * @param lastConflict 上次会话遗留的断开原因码（"address"/"subnet"/"timeout"），null = 无记录
 * @param statusCode   TunnelStateBus.networkStatuses 的实时原始码，null = 暂无
 * @param hasStatus    实时状态是否存在；默认由 [statusCode] 非空推出，保留参数以贴合
 *                     iOS「networkStatuses[nwid] 存在」的判定语义（调用方持有状态表时可用）
 * @param nodeOnline   核心是否已连通行星根服务器（VPN 级共享状态，TunnelStateBus.nodeStatus）
 * @param pathSatisfied 物理网络可用（NetWatch 透出）；false 时已连接卡片显示「无网络」
 */
fun deriveNetworkCardStatus(
    isActivePlanet: Boolean,
    vpnRunning: Boolean,
    lastActivated: Boolean,
    lastConflict: String?,
    liveConflict: String?,
    statusCode: Int?,
    hasStatus: Boolean = statusCode != null,
    nodeOnline: Boolean = true,
    pathSatisfied: Boolean = true,
): NetworkCardStatus {
    if (!isActivePlanet) {
        return NetworkCardStatus(R.string.status_disconnected, StatusColor.Neutral)
    }
    liveConflict?.takeIf { lastActivated }?.let {
        return NetworkCardStatus(conflictLabelRes(it), StatusColor.Danger)
    }
    if (!lastActivated) {
        return when (lastConflict) {
            CONFLICT_TIMEOUT -> NetworkCardStatus(R.string.card_timeout_offline, StatusColor.Danger)
            null -> NetworkCardStatus(R.string.status_disconnected, StatusColor.Neutral)
            else -> NetworkCardStatus(
                R.string.card_conflict_offline,
                StatusColor.Danger,
                conflictLabelRes(lastConflict),
            )
        }
    }
    if (!vpnRunning) {
        return NetworkCardStatus(R.string.status_establishing, StatusColor.Warn)
    }
    if (!pathSatisfied) {
        return NetworkCardStatus(R.string.status_noNetwork, StatusColor.Warn)
    }
    if (hasStatus && statusCode != null) {
        if (statusCode == 2 || statusCode == 6) {
            return NetworkCardStatus(R.string.status_pendingAuth, StatusColor.Warn)
        }
        if (statusCode == 0) {
            // REQUESTING_CONFIGURATION 按 online 拆段：未连根服务器=「连接服务器」
            // （超过阈值由 HomeViewModel 无感重启核心重试），已连但未拿到配置=
            // 「请求网络配置」
            return NetworkCardStatus(connectPhaseLabel(nodeOnline), StatusColor.Warn)
        }
        return NetworkCardStatus(
            label = ztStatusLabel(statusCode),
            color = if (statusCode == 1) StatusColor.Good else StatusColor.Warn,
        )
    }
    return NetworkCardStatus(R.string.status_waitingNode, StatusColor.Neutral)
}

/**
 * REQUESTING_CONFIGURATION 的分段文案（卡片与详情页共用，对齐 iOS
 * NetworkCardStatus.connectPhase）：online 为核心是否已连通行星根服务器。
 * 超时重试在 HomeViewModel 内无感循环（不限次数、无提示），此处不感知重试
 * 状态，全程显示进行中文案。
 */
@StringRes
fun connectPhaseLabel(nodeOnline: Boolean): Int =
    if (nodeOnline) R.string.network_status_requestingConfig
    else R.string.network_status_connectingServer

/**
 * 冲突原因码 → 文案资源（NetworkEntry.lastConflict 约定 "address"/"subnet"/"timeout"）。
 * 「地址冲突/网段冲突」单发与「%@,断开」拼串两场景共用同一资源；timeout 为共享
 * 故障，不参与拼串（直显分支见 [deriveNetworkCardStatus] 的 CONFLICT_TIMEOUT）。
 */
private fun conflictLabelRes(code: String): Int = when (code) {
    "subnet" -> R.string.card_conflict_subnet
    else -> R.string.card_conflict_address
}

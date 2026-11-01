package cn.imgozi.planetport.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.MoonEntry
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.store.PlanetImporter
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StoreRepo
import cn.imgozi.planetport.tunnel.NetConf
import cn.imgozi.planetport.tunnel.TunnelController
import cn.imgozi.planetport.tunnel.TunnelState
import cn.imgozi.planetport.tunnel.TunnelStateBus
import cn.imgozi.planetport.ui.CONFLICT_TIMEOUT
import cn.imgozi.planetport.ui.CONNECT_TIMEOUT_MS
import cn.imgozi.planetport.ui.ztStatusLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/**
 * 跨行星切换交接弹窗（用户裁定的交互动效）：确认阶段提示切换会断开当前行星；
 * 确认后进入断开阶段（弹窗保持打开、显示断开进度、不可取消），当前 VPN 确认
 * 停机后关闭弹窗再起新行星。
 */
data class SwitchPlanetDialog(
    /** 待开启的网络条目。 */
    val entry: NetworkEntry,
    /** 该网络归属的行星（切换目标）。 */
    val planetId: String,
    /** true = 已确认，正在断开当前 VPN（无按钮、不可取消）。 */
    val disconnecting: Boolean = false,
)

/** 首页聚合状态（store 全量 + TunnelStateBus 五流的 level-triggered 快照）。 */
data class HomeUiState(
    /** 首次 reload 完成（HomeView 据此门控分页组合，首帧直接渲染已选行星）。 */
    val loaded: Boolean = false,
    val planets: List<PlanetProfile> = emptyList(),
    val selectedPlanetId: String = PlanetProfile.OFFICIAL_ID,
    val activePlanetId: String = PlanetProfile.OFFICIAL_ID,
    /** 是否激活过任何行星（未激活过不显示「上次使用」角标，iOS hasActivatedPlanet）。 */
    val hasActivatedPlanet: Boolean = false,
    /** 各行星各自的网络列表（分页并行渲染，避免相邻页共享当前页数据）。 */
    val planetNetworks: Map<String, List<NetworkEntry>> = emptyMap(),
    /** 各行星 moon 数（planetId 为 null 的遗留记录计入当前选中行星，iOS 同构）。 */
    val moonCounts: Map<String, Int> = emptyMap(),
    /** 各行星节点 ID 缓存（隧道在跑时回写，离线可显示）。 */
    val planetNodeIds: Map<String, String> = emptyMap(),
    /** 同时连接软上限（2..6，取 max(2, 偏好值)，iOS HomeView 同构）。 */
    val maxNetworks: Int = 2,
    /** 隧道在跑（Running/Starting/Reconnecting，对齐 iOS vpnRunning 判定）。 */
    val tunnelRunning: Boolean = false,
    /** 核心是否已连通行星根服务器（VPN 级共享状态，iOS AppState.online 同构）。 */
    val nodeOnline: Boolean = false,
    /** 物理网络可用（NetWatch 透出；飞行模式/断网即时 false，iOS pathSatisfied 同构）。 */
    val pathSatisfied: Boolean = true,
    /** nwid → ZT 原始状态码（隧道侧契约：文案在 UI 层生成）。 */
    val networkStatuses: Map<String, Int> = emptyMap(),
    /** nwid → 实时冲突原因码 "address"/"subnet"（隧道侧契约：文案在 UI 层生成；空表 = 无冲突）。 */
    val networkConflicts: Map<String, String> = emptyMap(),
    /** nwid → 已分配地址（去前缀，仅当前隧道行星有数据）。 */
    val assignedAddrs: Map<String, List<String>> = emptyMap(),
    /** 跨行星切换交接弹窗；null = 无弹窗。 */
    val switchDialog: SwitchPlanetDialog? = null,
)

/** 首页一次性弹窗事件（iOS 的多组 .alert 均为「标题 + 内容」形态，收敛为一类）。 */
sealed interface HomeEvent {
    data class Notice(val title: String, val message: String) : HomeEvent
}

/**
 * 首页编排：聚合 store 与隧道状态；网络开关、加入网络、添加行星等全部
 * 配置变更走本类（对齐 iOS AppState + HomeView 编排职责）。
 *
 * 数据契约：configChanges 有损合并 → 订阅即全量重读（level-triggered）；
 * 隧道侧五流为 StateFlow 热流，直接映射。写配置后显式 reload（selectPlanet/
 * activatePlanet 静默不发信号）。
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    /** 开关请求处理中（加载圈替代 Toggle；iOS pendingNetworks 上移至 VM）。 */
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pendingNetworks: StateFlow<Set<String>> = _pending.asStateFlow()

    /** VPN 授权请求器（MainActivity.requestVpnPermission，组合期注入）。 */
    private var permissionRequester: ((onGranted: () -> Unit) -> Unit)? = null

    /** 非空 = 有进行中的启动尝试（Failed 到来时据此区分「启动失败」与运行期 TUN 故障）。 */
    private var startAttemptAt = 0L

    private val appContext get() = getApplication<Application>()

    init {
        viewModelScope.launch {
            // 启动时隧道未存活：上次标记为已连接的网络实际已离线，复位避免
            // 「等待 VPN 连接」悬置与下次启动悄悄重连（iOS resetStaleNetworksIfNeeded）。
            // 例外=按需连接开启（iOS NEOnDemandRuleConnect 语义：保持持久在线）：
            // Android 无系统级钩子，落地为不复位 + 自动拉起，兑现「重启后按需连接」
            if (!TunnelController.isRunning) {
                val networks = StoreRepo.store.readActiveNetworks()
                if (StoreRepo.store.connectOnDemand() && networks.any { it.lastActivated }) {
                    TunnelStateBus.log("按需连接开启，启动时自动恢复隧道")
                    // Failed 存活态先 stop 再启：Failed 态服务进程仍活（scope 未取消），
                    // isRunning 判为不在跑放行 START，但服务侧双 START 守卫只刷新通知
                    // 就 return，核心不会重启——与 applySelectedPlanetAndStart 兜底一致
                    if (TunnelStateBus.tunnelState.value is TunnelState.Failed) {
                        TunnelController.stop(appContext)
                    }
                    TunnelController.ensureStarted(
                        appContext,
                        requestPermission = { onGranted -> permissionRequester?.invoke(onGranted) },
                    )
                } else {
                    storeWrite("resetAllNetworkActivation") {
                        StoreRepo.store.resetAllNetworkActivation()
                    }
                }
            }
            reload()
        }
        viewModelScope.launch { StoreRepo.configChanges.collect { reload() } }
        // 行星表 UI 字段变更（图标/底色/名称/导入）不发 configChanges（避免隧道
        // 无谓热重配），经 UI 级 planetsChanges 刷新卡片
        viewModelScope.launch { StoreRepo.planetsChanges.collect { reload() } }
        viewModelScope.launch { TunnelStateBus.tunnelState.collect { onTunnelState(it) } }
        viewModelScope.launch {
            TunnelStateBus.nodeStatus.collect { info ->
                _state.update { it.copy(nodeOnline = info?.online ?: false) }
                evaluateConnectTimer()
                val id = info?.nodeId.orEmpty()
                if (id.isNotEmpty()) cacheNodeId(id)
            }
        }
        viewModelScope.launch {
            TunnelStateBus.pathSatisfied.collect { satisfied ->
                _state.update { it.copy(pathSatisfied = satisfied) }
            }
        }
        viewModelScope.launch {
            TunnelStateBus.networkStatuses.collect {
                _state.update { s -> s.copy(networkStatuses = it) }
                checkAlertableStatus(it)
            }
        }
        viewModelScope.launch {
            TunnelStateBus.networkConflicts.collect { conflicts ->
                _state.update { s -> s.copy(networkConflicts = conflicts) }
                autoDisconnectConflictedNetworks(conflicts)
            }
        }
        viewModelScope.launch {
            TunnelStateBus.networkConfigs.collect { configs ->
                _state.update { s ->
                    s.copy(
                        assignedAddrs = configs.mapValues { (_, conf) ->
                            conf.assignedAddrs.map { addr -> addr.substringBefore('/') }
                        },
                    )
                }
                cacheNetworkNames(configs)
            }
        }
    }

    // MARK: - 状态聚合

    /**
     * store 写点兜底：写盘 IOException 等异常不崩进程，只记日志（不弹 UI——
     * 首页无提示基建，为低概率磁盘故障扩张不值），对齐 MoonViewModel 既有
     * runCatching 范式。失败后 UI 态与磁盘态由下次 reload / configChanges 收敛。
     */
    private suspend fun <T> storeWrite(tag: String, block: suspend () -> T) {
        runCatching { block() }.onFailure { Log.w(TAG, "$tag 写盘失败", it) }
    }

    /** store 全量重读（level-triggered；任何写路径后亦显式调用）。 */
    private suspend fun reload() {
        val store = StoreRepo.store
        val planets = store.readPlanets()
        val selected = store.selectedPlanetId()
        val active = store.activePlanetId()
        val hasActivated = store.hasActivatedPlanet()
        val prefs = store.readPrefs()
        val networks = store.readNetworksByPlanet()
        val moonCounts = planets.associate { it.id to 0 }.toMutableMap()
        for (moon in store.readAllMoons()) {
            val key = moon.planetId ?: selected
            moonCounts[key] = (moonCounts[key] ?: 0) + 1
        }
        _state.update {
            it.copy(
                loaded = true,
                planets = planets,
                selectedPlanetId = selected,
                activePlanetId = active,
                hasActivatedPlanet = hasActivated,
                planetNetworks = networks,
                moonCounts = moonCounts,
                planetNodeIds = planets.mapNotNull { p -> p.lastNodeId?.let { id -> p.id to id } }.toMap(),
                maxNetworks = max(2, prefs.maxNetworks),
            )
        }
    }

    private fun onTunnelState(st: TunnelState) {
        _state.update {
            it.copy(
                tunnelRunning = st is TunnelState.Running ||
                    st is TunnelState.Starting || st is TunnelState.Reconnecting
            )
        }
        // 起跑/停机边沿即连接计时起点/取消点（iOS connectionStatus 边沿 evaluateConnectTimer）
        evaluateConnectTimer()
        when (st) {
            is TunnelState.Running -> startAttemptAt = 0
            is TunnelState.Failed -> {
                val fromStartAttempt = startAttemptAt > 0
                startAttemptAt = 0
                // 仅启动尝试期的 Failed 报「无法启动 VPN」；运行期 TUN 故障由状态行呈现
                if (fromStartAttempt) {
                    emitNotice(
                        appContext.getString(R.string.home_error_vpnStartTitle),
                        failedReasonText(st.reason),
                    )
                }
            }
            else -> Unit
        }
    }

    /**
     * Failed reason → 弹窗文案。reason 有两类装配：隧道侧持 Context 的生产点已
     * getString（identityCollision/TUN 建立失败等，直显即本地化文案）；核心侧
     * （NodeRuntime 驱动循环）无 Context，只能给技术串 "core_driver:$rc"。技术串
     * 按前缀识别落通用失败文案，恒定资源、零技术串直显（T7b 审查 I-1 同范式）。
     */
    private fun failedReasonText(reason: String): String =
        if (reason.startsWith("core_driver:")) {
            appContext.getString(R.string.tunnel_error_coreDriver)
        } else {
            reason
        }

    // MARK: - 连接计时与超时自动重试（对齐 iOS AppState.evaluateConnectTimer）

    /** 连接超时任务（App 侧计时；进程冻结期 delay 顺延，解冻后迟到即补触发。
     *  循环仅前台成立：Activity 销毁 → viewModelScope 取消即停摆，隧道侧核心
     *  自身继续尝试连接，重开 App 由隧道边沿的评估接续开窗）。 */
    private var connectTimeoutJob: Job? = null

    /**
     * 连接周期起点（毫秒 epoch）：tunnelRunning 且未 online 且有启用网络时起算，
     * online 达成或隧道停止即清空；超时判定（[CONNECT_TIMEOUT_MS]）的起点
     * （iOS AppState.connectStartedAt 同构，纯 VM 内部状态，UI 不感知）。
     */
    private var connectStartedAtMs: Long? = null

    /**
     * 连接计时的起止判定：tunnelRunning 且未 online 且活跃行星有启用网络时起算；
     * online 达成或隧道停止即取消。超时到点走 [retryOnConnectTimeout]——
     * Android 与核心同进程（TunnelStateBus 直读核心回调线程），无 iOS Darwin
     * 通知送达延迟，无需重启前向隧道复核 online 的竞态防护（b4fb534）。
     */
    private fun evaluateConnectTimer() {
        viewModelScope.launch {
            val s = _state.value
            val hasActiveNetworks =
                StoreRepo.store.readActiveNetworks().any { it.lastActivated }
            val shouldRun = s.tunnelRunning && !s.nodeOnline && hasActiveNetworks
            if (shouldRun) {
                if (connectStartedAtMs != null) return@launch
                connectStartedAtMs = System.currentTimeMillis()
                // 新连接周期开始：上一轮的遗留超时记录随之失效（超时是整轮共享事件，
                // 不该在新周期里继续显示在未参与连接的网络上；冲突记录单网络事件，保留）
                clearStaleTimeoutRecords()
                connectTimeoutJob?.cancel()
                connectTimeoutJob = launch {
                    delay(CONNECT_TIMEOUT_MS)
                    retryOnConnectTimeout()
                }
            } else {
                // online 达成 / 隧道停止 / 全部断开：周期结束，计时状态清空
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                connectStartedAtMs = null
            }
        }
    }

    /** 清除活跃行星上一轮连接周期遗留的超时记录（仅 [CONFLICT_TIMEOUT]（旧版本遗留），
     *  对齐 iOS clearStaleTimeoutRecords）。 */
    private suspend fun clearStaleTimeoutRecords() {
        val store = StoreRepo.store
        val planetId = store.activePlanetId()
        val networks = store.readNetworks(planetId)
        if (networks.none { it.lastConflict == CONFLICT_TIMEOUT }) return
        storeWrite("writeNetworks") {
            store.writeNetworks(
                networks.map { entry ->
                    if (entry.lastConflict == CONFLICT_TIMEOUT) {
                        entry.copy(lastConflict = null)
                    } else {
                        entry
                    }
                },
                planetId,
            )
        }
    }

    /**
     * 连接超时自动重试：核心起跑超过 [CONNECT_TIMEOUT_MS] 仍未连通行星根服务器，
     * 直接无感重启核心（磁盘持久化身份与配置，隧道侧恢复 join；新源端口重建
     * UDP 流，间歇性故障可赶上恢复窗口），不限次数直到 online 或用户手动关闭；
     * 重启经 restartIfRunning 的停启边沿由 evaluateConnectTimer 重新起算下一轮
     * 窗口，UI 稳态显示「连接服务器」（停启边沿瞬闪「建立隧道/等待节点响应」，
     * iOS 重启不停会话无此闪烁），无超时/重试提示；代价是 FGS 通知随每轮重启
     * （≥46s）移除重建一次（对齐 iOS retryOnConnectTimeout）。
     */
    private suspend fun retryOnConnectTimeout() {
        val s = _state.value
        // nodeOnline 现场复核（终审 I-1 同族竞态）：_state 快照可能落后——核心恰在
        // 超时到点前达成 online 而 nodeStatus collector 尚未调度时，快照仍是
        // false，会把刚上线的节点误判超时而无谓重启核心
        if (s.nodeOnline) return
        if (TunnelStateBus.nodeStatus.value?.online == true) return
        // 停启中间态到期的假窗口（重启间隙起算）：作废起点等 Running 边沿
        // 重新起算——不清 startedAt 会让新窗口被残留起点拒绝、链路卡死
        if (!s.tunnelRunning) {
            connectTimeoutJob = null
            connectStartedAtMs = null
            return
        }
        // 在途停止守卫（同 activate 快速路径词汇）：45s 到点的消息可能排在用户
        // stop 之后、Stopped 边沿之前执行——此刻快照仍显示在跑，无守卫会把用户
        // 明确停止的隧道顶回（stopGeneration 防的就是同族竞态）。拦下即止，
        // Stopped 边沿的评估照常收尾，循环终止
        if (TunnelStateBus.stopInFlight) {
            connectTimeoutJob = null
            connectStartedAtMs = null
            return
        }
        // 只置空引用，不 cancel：本函数正是由该 Job 执行，cancel 自身会让
        // 下一个挂起点抛 CancellationException，静默吞掉后续重启
        connectTimeoutJob = null
        connectStartedAtMs = null
        TunnelController.restartIfRunning(appContext)
    }

    // MARK: - 行星浏览

    /** 只切换浏览上下文，不触碰隧道（iOS AppState.selectPlanet）。 */
    fun selectPlanet(id: String) {
        viewModelScope.launch {
            storeWrite("selectPlanet") { StoreRepo.store.selectPlanet(id) }
            reload()
        }
    }

    // MARK: - 网络开关

    /**
     * 开/关网络（对齐 iOS HomeView.setNetworkActive + performNetworkActivate）。
     * 开：软上限判定 → 达限回调 [onOverLimit]（UI 弹确认，「仍要开启」执行 force）；
     * 关：写配置后该行星（当前隧道行星）零启用网络则停隧道。
     */
    fun setNetworkActive(
        planetId: String,
        entry: NetworkEntry,
        active: Boolean,
        onOverLimit: (force: () -> Unit) -> Unit = {},
    ) {
        viewModelScope.launch {
            if (!active) {
                deactivate(planetId, entry)
                return@launch
            }
            // 跨行星开启先确认（用户裁定交互）：当前行星隧道在跑时，开启另一行星
            // 的网络必须先确认；确认后走「先断开、后启动」交接（confirmSwitchPlanet），
            // 不确认不落任何配置。顺序在软上限之前（iOS switchConfirmTarget 同序）
            if (planetId != StoreRepo.store.activePlanetId() && TunnelController.isRunning) {
                _state.update {
                    it.copy(switchDialog = SwitchPlanetDialog(entry, planetId))
                }
                return@launch
            }
            val networks = StoreRepo.store.readNetworks(planetId)
            val activeCount = networks.count { it.lastActivated && it.networkId != entry.networkId }
            if (activeCount >= _state.value.maxNetworks) {
                onOverLimit { viewModelScope.launch { activate(planetId, entry) } }
                return@launch
            }
            activate(planetId, entry)
        }
    }

    /** 关网络：置 lastActivated=false → 写配置 → 该行星零启用则停隧道。 */
    private suspend fun deactivate(planetId: String, entry: NetworkEntry) {
        val store = StoreRepo.store
        val networks = store.readNetworks(planetId)
        val index = networks.indexOfFirst { it.networkId == entry.networkId }
        if (index < 0) return
        val updated = networks.toMutableList()
        updated[index] = updated[index].copy(lastActivated = false)
        storeWrite("writeNetworks") { store.writeNetworks(updated, planetId) }
        // 停隧道判定只锚 planetId==activePlanetId（iOS 19f7681：不再叠加
        // selectedPlanetId——滑页过半时选中态先行变化，叠加判定会漏停隧道）
        if (planetId == store.activePlanetId() && updated.none { it.lastActivated }) {
            // 停机前先收敛 UI：planetNetworks 走 configChanges→reload 异步链，若
            // Stopped 边沿先到，角标会在「未跑+旧表仍有启用网络」窗口误显「连接中」
            //（iOS planetNetworks 主线程同步更新无此窗口，本显式 reload 即等价写序）
            reload()
            TunnelController.stop(appContext)
        }
    }

    /**
     * 开网络（对齐 iOS performNetworkActivate）：置 lastActivated + 清 lastConflict +
     * 多网自动关 routeVia（一次性提示）→ 快速路径（同行星且隧道在跑：writeNetworks
     * 已发变更，隧道差分 join/leave，仅摆 600ms 加载圈）；否则激活行星 + 起停隧道。
     */
    private suspend fun activate(planetId: String, entry: NetworkEntry) {
        writeActivatedConfig(planetId, entry)

        // 应用目标全程锚定网络归属行星（iOS performNetworkActivate →
        // applyPlanetAndStartVPN(planetId) 同构）：不重读 selectedPlanetId——
        // 滑页竞态下选中态先行变化，以 selected 应用会激活错行星
        val wasRunning = TunnelController.isRunning
        // 快速路径=网络所属行星就是隧道行星且隧道在跑且无在途停止（iOS 同构）：
        // writeNetworks 已发变更，隧道差分 join/leave，仅摆 600ms 加载圈不重启。
        // 排除在途停止：关网络后立即重开时 STOP intent 尚未落地、isRunning 仍
        // true，误走快速路径会无人拉起隧道，卡片卡「建立隧道」
        val fastPath = planetId == StoreRepo.store.activePlanetId() &&
            wasRunning && !TunnelStateBus.stopInFlight
        _pending.value = _pending.value + entry.networkId
        if (fastPath) {
            // 给隧道一个响应窗口，避免开关上的加载圈一闪而过（iOS 600ms 同构）
            delay(600)
            clearPending(entry.networkId)
            return
        }
        if (TunnelStateBus.stopInFlight) {
            // 停止在途：等停机落地再走起停编排（iOS performStart 等待上一会话
            // 拆除的同构），有界等待兜底进程级异常
            withTimeoutOrNull(15_000) {
                while (TunnelStateBus.stopInFlight) delay(100)
            }
        }
        applySelectedPlanetAndStart(planetId)
        waitTunnelSettled(requireLeaveRunning = wasRunning)
        clearPending(entry.networkId)
    }

    /**
     * 开启网络的落盘段（activate 与跨行星交接共用）：置 lastActivated + 清
     * lastConflict + 多网自动关 routeVia（一次性提示）。
     */
    private suspend fun writeActivatedConfig(planetId: String, entry: NetworkEntry) {
        val store = StoreRepo.store
        val networks = store.readNetworks(planetId)
        val index = networks.indexOfFirst { it.networkId == entry.networkId }
        if (index < 0) {
            // 条目不在目标行星列表（并发删网络/绕过路径兜底）：记日志跳过，调用方
            // 照常走后续起停——无启用网络时隧道按空载收敛
            Log.w(TAG, "writeActivatedConfig: 网络 ${entry.networkId} 不在行星 $planetId 列表，跳过写盘")
            return
        }
        val updated = networks.toMutableList()
        // 重新手动开启视为用户已知冲突，清除上次的冲突断开记录
        updated[index] = updated[index].copy(lastActivated = true, lastConflict = null)
        var routeViaTurnedOff = false
        if (updated.count { it.lastActivated } > 1) {
            for (i in updated.indices) if (updated[i].routeViaZeroTier) {
                updated[i] = updated[i].copy(routeViaZeroTier = false)
                routeViaTurnedOff = true
            }
        }
        storeWrite("writeNetworks") { store.writeNetworks(updated, planetId) }
        if (routeViaTurnedOff) {
            emitNotice(
                appContext.getString(R.string.home_error_multiNetworkTitle),
                appContext.getString(R.string.home_error_multiNetworkMessage),
            )
        }
    }

    /** 取消跨行星切换（仅确认阶段可取消；此时未落任何配置，直接收弹窗）。 */
    fun cancelSwitchPlanet() {
        _state.update { it.copy(switchDialog = null) }
    }

    /**
     * 确认跨行星切换，交接时序（用户裁定，五步）：
     * 1-2. 弹窗确认（本函数入口）；
     * 3. **先停当前 VPN**（弹窗保持打开显示断开状态）——落配置/切指针必须等
     *     停机之后：writeNetworks/activatePlanet 发的 configChanges 会驱动仍在
     *     跑的旧核心 applyConfig，此时指针已指新行星，旧核心会去 join 新行星
     *     网络（经旧根服务器取不到 netconf，正是本交互要消除的病理）；
     * 4. 停机终态确认后落配置 + 切指针 + 交接收敛，关闭弹窗；
     * 5. 留 [HANDOVER_DELAY_MS] 收口窗口再起新行星（核心按新指针装载）。
     *
     * 显式「先停后起」替代热重启编排：交接各阶段对用户可见、可判定，旧核心
     * 在新核心启动前必然已收口，不存在两套启停交错。确认后的停→落盘段包
     * [NonCancellable]：用户退出页面取消协程时，交接已不可逆（弹窗已进断开
     * 阶段），至少把磁盘态收敛到自洽（新行星配置+指针），隧道启动段留在可
     * 取消区。
     */
    fun confirmSwitchPlanet() {
        val dlg = _state.value.switchDialog ?: return
        if (dlg.disconnecting) return
        viewModelScope.launch {
            val entry = dlg.entry
            _state.update { it.copy(switchDialog = dlg.copy(disconnecting = true)) }
            _pending.value = _pending.value + entry.networkId
            withContext(NonCancellable) {
                // 先断开当前 VPN；弹窗不关，断开进度由 disconnecting 态呈现
                if (TunnelController.isRunning) {
                    TunnelController.stop(appContext)
                    val settled = withTimeoutOrNull(STOP_SETTLE_TIMEOUT_MS) {
                        TunnelStateBus.tunnelState.first {
                            it !is TunnelState.Running && it !is TunnelState.Starting &&
                                it !is TunnelState.Reconnecting
                        }
                    }
                    if (settled == null) TunnelStateBus.log("切换行星：等待停机终态超时")
                }
                // 停机收口后再落配置 + 切指针：旧核心 collector 已随服务 scope
                // 取消，configChanges 不会再驱动旧核心；新核心 startTunnel 起核
                // 时自读新盘
                writeActivatedConfig(dlg.planetId, entry)
                switchPlanetPointer(dlg.planetId)
                // 停机确认后关闭弹窗
                _state.update { it.copy(switchDialog = null) }
                // 停→起收口窗口（用户裁定 50~150ms 取中值）
                delay(HANDOVER_DELAY_MS)
            }
            if (TunnelController.isRunning) {
                // 停机超时兜底：仍在跑则走热重启（排队补发已内建），确保核心按新指针重建
                TunnelController.restartIfRunning(appContext)
            } else {
                if (TunnelStateBus.tunnelState.value is TunnelState.Failed) {
                    TunnelController.stop(appContext)
                }
                startAttemptAt = System.currentTimeMillis()
                TunnelController.ensureStarted(
                    appContext,
                    requestPermission = { onGranted -> permissionRequester?.invoke(onGranted) },
                    onPermissionDenied = {
                        emitNotice(
                            appContext.getString(R.string.home_error_vpnStartTitle),
                            appContext.getString(R.string.home_error_vpnPermissionDenied),
                        )
                    },
                )
            }
            waitTunnelSettled(requireLeaveRunning = false)
            clearPending(entry.networkId)
        }
    }

    /**
     * 行星指针切换 + 交接收敛（applySelectedPlanetAndStart 与跨行星交接共用）：
     * activatePlanet 落指针后，旧行星启用标记清零（隧道已不再承载，残留会让
     * 旧行星卡片显示错态；lastConflict 保留真实历史）、总线共享状态清空（旧核心
     * 会话遗留不再有所属行星）、连接超时周期重置（新行星重新起算）。
     * 返回 planetChanged（指针未变时跳过收敛，幂等无害）。
     */
    private suspend fun switchPlanetPointer(newPlanetId: String): Boolean {
        val store = StoreRepo.store
        val previousPlanetId = store.activePlanetId()
        var planetChanged = false
        // activatePlanet 失败只记日志：planetChanged 保持 false，后续重启联动按原
        // 语义照走（指针未换成功时不重启与磁盘态一致；即便触发重启亦无害幂等）
        storeWrite("activatePlanet") {
            planetChanged = StoreRepo.store.activatePlanet(newPlanetId)
        }
        _state.update { it.copy(activePlanetId = StoreRepo.store.activePlanetId()) }
        if (planetChanged) {
            storeWrite("writeNetworks") {
                val old = store.readNetworks(previousPlanetId)
                if (old.any { it.lastActivated }) {
                    store.writeNetworks(
                        old.map { e -> if (e.lastActivated) e.copy(lastActivated = false) else e },
                        previousPlanetId,
                    )
                }
            }
            // 总线清空（对齐 iOS b4fb534：activePlanetId 已指向新行星，遗留状态
            // 不再有所属行星可用）——重启后由新会话重写。configs（地址/详情页快照）
            // 一并清：对齐 iOS applyPlanetAndStartVPN 清 networkConfigs，否则停启
            // 窗口期新行星页面读到旧行星会话的地址与配置（同 nwid 跨行星直接串显）
            TunnelStateBus.clearNetworkStatuses()
            TunnelStateBus.clearNetworkConflicts()
            TunnelStateBus.clearNetworkConfigs()
            TunnelStateBus.setNodeStatus(null)
            // 核心随行星重建，旧连接周期作废（新周期由新会话的隧道边沿重新起算）。
            // 显式作废而非靠停启边沿：切行星落盘后新行星网络已启用，停机边沿的
            // 评估会立即为新行星开窗，残留起点会让窗口跨行星串轮
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            connectStartedAtMs = null
        }
        return planetChanged
    }

    /**
     * 应用所选行星到隧道并确保运行（对齐 iOS applySelectedPlanetAndStartVPN，
     * AppState.swift:135-153）。三分支已按等终态时序复核：
     * - 在跑且 planetChanged → [TunnelController.restartIfRunning]：停 → 等停机
     *   终态 → 启（替代 iOS restart 固定 1s 延迟）。核心 planet 文件/状态目录只在
     *   服务启动时装载，applyConfig 热重载不换 planet，切行星必须整隧道重启。
     * - 在跑且未变更 → no-op（iOS 同：running 且未变更仅回调 completion）。
     * - 未跑 → 授权后启动；Failed 存活态先 stop 再启——Failed 态服务进程仍活
     *   （scope 未取消），双 START 守卫会吞掉启动指令；STOP/START intent 在服务
     *   主线程按序派发且 stopTunnel 同步收口，后续 START 必落在停完之后（iOS 无
     *   「失败但进程存活」态，此分支为 Android 平台特有）。
     *
     * 指针切换与交接收敛收敛在 [switchPlanetPointer]（与跨行星交接共用）。
     * 跨行星开启已在本类入口处走显式交接（confirmSwitchPlanet），本路径的
     * planetChanged 分支实际只覆盖「同行星指针未变 + 隧道未跑」等场景，保留
     * restartIfRunning 兜底不动。
     */
    private suspend fun applySelectedPlanetAndStart(selectedPlanetId: String) {
        val planetChanged = switchPlanetPointer(selectedPlanetId)
        when {
            TunnelController.isRunning ->
                if (planetChanged) TunnelController.restartIfRunning(appContext)
            else -> {
                if (TunnelStateBus.tunnelState.value is TunnelState.Failed) {
                    TunnelController.stop(appContext)
                }
                startAttemptAt = System.currentTimeMillis()
                TunnelController.ensureStarted(
                    appContext,
                    requestPermission = { onGranted -> permissionRequester?.invoke(onGranted) },
                    onPermissionDenied = {
                        emitNotice(
                            appContext.getString(R.string.home_error_vpnStartTitle),
                            appContext.getString(R.string.home_error_vpnPermissionDenied),
                        )
                    },
                )
            }
        }
    }

    /** 等待起停/重启收口（Running/Failed 或超时）；重启路径先等状态离开 Running。 */
    private suspend fun waitTunnelSettled(requireLeaveRunning: Boolean) {
        var leftRunning = !requireLeaveRunning
        var waited = 0
        while (waited < 15_000) {
            delay(250)
            waited += 250
            when (val st = TunnelStateBus.tunnelState.value) {
                is TunnelState.Failed -> return
                is TunnelState.Running -> if (leftRunning) return
                else -> leftRunning = true
            }
        }
    }

    private fun clearPending(networkId: String) {
        _pending.value = _pending.value - networkId
    }

    // MARK: - 加入网络

    /**
     * 加入网络（仅保存不连接，iOS JoinNetworkSheet.join）：重复 ID 静默忽略——
     * 查重归属 UI 侧自查（JoinNetworkSheet 点按前已拦截并弹窗），此处仅兜底
     * 防并发/绕过路径重复写库。
     */
    fun joinNetwork(entry: NetworkEntry) {
        viewModelScope.launch {
            val store = StoreRepo.store
            val planetId = store.selectedPlanetId()
            val existing = store.readNetworks(planetId)
            if (existing.any { it.networkId == entry.networkId }) return@launch
            storeWrite("writeNetworks") { store.writeNetworks(existing + entry, planetId) }
        }
    }

    // MARK: - 添加行星

    /**
     * 从文件数据添加行星：World ID 查重（含官方内置）→ 落库 → 选中新行星。
     * [onDone] 二参为错误文案（含查重与导入失败），null = 成功。
     */
    fun importPlanetFile(
        data: ByteArray,
        name: String,
        icon: String? = null,
        iconColor: String? = null,
        onDone: (PlanetProfile?, String?) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val worldId = PlanetImporter.worldIdOf(data)
                if (worldId != null && worldId in StoreRepo.planetImporter.existingWorldIds()) {
                    // 查重经专用类型走同链路回传，文案在消费侧映射——本地化文案
                    // 不混入异常 message 通道（iOS addplanet.duplicate；T7b 审查 I-1）
                    throw DuplicateWorld(worldId)
                }
                StoreRepo.planetImporter.importPlanetData(data, name, icon, iconColor)
            }
            result.fold(
                onSuccess = { profile ->
                    storeWrite("selectPlanet") { StoreRepo.store.selectPlanet(profile.id) }
                    reload()
                    onDone(profile, null)
                },
                onFailure = { onDone(null, importErrorText(it)) },
            )
        }
    }

    /** 仅下载 planet 文件内容（「先就绪、后添加」交互；校验与落库归 importPlanetFile）。
     *  [onProgress] 边收边报（IO 线程回调，见 PlanetImporter.downloadPlanetFile）。 */
    fun downloadPlanet(
        url: String,
        onProgress: (Double?) -> Unit = { _ -> },
        onDone: (ByteArray?, String?) -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { StoreRepo.planetImporter.downloadPlanetFile(url, onProgress) }.fold(
                onSuccess = { onDone(it, null) },
                onFailure = { onDone(null, importErrorText(it)) },
            )
        }
    }

    /**
     * 导入/下载链异常 → 用户文案：按 [PlanetImporter.ImportError] 子类与
     * [DuplicateWorld] 映射资源。store 层 message 是稳定英文标识串（无 Context
     * 无法本地化），未识别异常不再透出 message——技术串进日志，用户见通用文案
     * （T7b 审查 I-1）。
     */
    private fun importErrorText(e: Throwable): String = when (e as? PlanetImporter.ImportError) {
        is PlanetImporter.ImportError.BadHeader ->
            appContext.getString(R.string.error_planet_badHeader)
        is PlanetImporter.ImportError.EmptyName ->
            appContext.getString(R.string.error_planet_emptyName)
        is PlanetImporter.ImportError.DownloadFailed ->
            appContext.getString(R.string.error_downloadFailed)
        is PlanetImporter.ImportError.TooLarge ->
            appContext.getString(R.string.error_planet_tooLarge)
        is PlanetImporter.ImportError.WriteFailed ->
            appContext.getString(R.string.error_planet_writeFailed)
        null -> when (e) {
            is DuplicateWorld ->
                // 16 位 hex 与全 App 及 iOS %016llx 一致（P4 终审 M-1）
                appContext.getString(R.string.addplanet_duplicate, MoonEntry.formatWorldId(e.worldId))
            else -> {
                Log.w(TAG, "导入链未识别异常", e)
                appContext.getString(R.string.addplanet_importFailed)
            }
        }
    }

    /** 添加行星查重（VM 层编排，不属导入器校验域）。 */
    private class DuplicateWorld(val worldId: ULong) : RuntimeException("duplicate world id")

    // MARK: - 隧道运行期回写缓存

    /**
     * 冲突自动断开（对齐 iOS AppState.autoDisconnectConflictedNetworks）：隧道侧检出
     * 地址/网段冲突（原始码经 TunnelStateBus 透出）后，把冲突网络记因 lastConflict 并
     * 复位 lastActivated，写回触发 configChanges → applyConfig 差分 leave。数据面已在
     * 隧道侧隔离（冲突网络不进聚合），此处只收敛配置态。
     *
     * lastConflict 只在用户重开网络时清除（activate）：冲突解除（网络出冲突表）不清，
     * 卡片持稳显示「<原因>，断开」（五级判定第 3 级）。作用域：冲突只可能来自当前
     * 隧道会话（= 活跃行星），只处理活跃行星——同一 networkId 可存在于多个行星，
     * 全量扫会把其它行星同 ID 网络误关（iOS b4fb534 同款隔离修正）。
     */
    private suspend fun autoDisconnectConflictedNetworks(conflicts: Map<String, String>) {
        if (conflicts.isEmpty()) return
        val store = StoreRepo.store
        val planetId = store.activePlanetId()
        val networks = store.readNetworks(planetId)
        if (networks.any { it.lastActivated && conflicts[it.networkId] != null }) {
            storeWrite("writeNetworks") {
                store.writeNetworks(
                    networks.map { entry ->
                        val code = conflicts[entry.networkId]
                        if (entry.lastActivated && code != null) {
                            entry.copy(lastConflict = code, lastActivated = false)
                        } else {
                            entry
                        }
                    },
                    planetId,
                )
            }
        }
        // 空跑收敛（iOS 19f7681 stopVPNIfNoActiveNetworks）：冲突断开可能清空
        // 活跃行星的全部启用网络，隧道不得无网络空跑。停机前先收敛 UI
        //（同 deactivate：防 Stopped 边沿先于 reload 到达误显「连接中」角标）
        if (store.readActiveNetworks().none { it.lastActivated }) {
            reload()
            TunnelController.stop(appContext)
        }
    }

    /** 节点 ID 缓存：隧道在跑读到即持久化到激活行星（iOS AppState.cacheNodeId）。 */
    private suspend fun cacheNodeId(nodeId: String) {
        val store = StoreRepo.store
        val activeId = store.activePlanetId()
        val planets = store.readPlanets()
        val index = planets.indexOfFirst { it.id == activeId }
        if (index < 0 || planets[index].lastNodeId == nodeId) return
        val updated = planets.toMutableList()
        updated[index] = updated[index].copy(lastNodeId = nodeId)
        storeWrite("writePlanets") { store.writePlanets(updated) }
        reload()
    }

    /** 网络名缓存：连接后快照名非空且与存量不同则回写（iOS cacheNetworkName）。 */
    private suspend fun cacheNetworkNames(configs: Map<String, NetConf>) {
        if (configs.isEmpty()) return
        val store = StoreRepo.store
        val planetId = store.activePlanetId()
        val networks = store.readNetworks(planetId)
        var changed = false
        val updated = networks.map { entry ->
            val name = configs[entry.networkId]?.name?.trim().orEmpty()
            if (name.isNotEmpty() && name != entry.networkName) {
                changed = true
                entry.copy(networkName = name)
            } else {
                entry
            }
        }
        if (changed) storeWrite("writeNetworks") { store.writeNetworks(updated, planetId) }
    }

    // MARK: - 交互辅助

    /** VPN 授权请求器注入（HomeView 组合期绑定 MainActivity 既有链）。 */
    fun attachPermissionRequester(requester: (onGranted: () -> Unit) -> Unit) {
        permissionRequester = requester
    }

    /**
     * 需要弹窗提醒的异常网络状态（对齐 iOS onChange(networkStatuses)）：
     * 当前隧道行星且隧道在跑时，notFound/portError/clientTooOld 触发提示
     * （待授权 2/6 属正常流程不弹，状态行显示即可）。
     */
    private fun checkAlertableStatus(statuses: Map<String, Int>) {
        val s = _state.value
        if (s.selectedPlanetId != s.activePlanetId || !s.tunnelRunning) return
        for ((nwid, code) in statuses) {
            if (code == 3 || code == 4 || code == 5) {
                val name = s.planetNetworks[s.selectedPlanetId]
                    ?.firstOrNull { it.networkId == nwid }?.networkName ?: nwid
                emitNotice(
                    appContext.getString(R.string.home_alert_networkStatusError),
                    appContext.getString(
                        R.string.home_alert_networkStatus,
                        name,
                        appContext.getString(ztStatusLabel(code)),
                    ),
                )
                break
            }
        }
    }

    private fun emitNotice(title: String, message: String) {
        _events.tryEmit(HomeEvent.Notice(title, message))
    }

    private companion object {
        const val TAG = "HomeViewModel"

        /** 跨行星交接停→起收口窗口（用户裁定 50~150ms 区间取中值）。 */
        const val HANDOVER_DELAY_MS = 100L

        /** 交接等待停机终态上限（与 TunnelController.awaitSettled 默认口径一致）。 */
        const val STOP_SETTLE_TIMEOUT_MS = 15_000L
    }
}

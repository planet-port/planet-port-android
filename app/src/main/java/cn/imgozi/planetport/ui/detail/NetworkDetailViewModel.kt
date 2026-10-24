package cn.imgozi.planetport.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StoreRepo
import cn.imgozi.planetport.tunnel.NetConf
import cn.imgozi.planetport.tunnel.TunnelStateBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 网络配置快照的 UI 投影（来源 TunnelStateBus.networkConfigs 的隧道内部契约 [NetConf]）。
 * 不让 internal 类型出现在公开签名里；文案（状态）仍由视图层经 [statusCode] 生成。
 */
data class NetworkSnapshot(
    val name: String,
    /** ZT_VirtualNetworkStatus 原始码 0..6（-1/其他 = 未知） */
    val statusCode: Int,
    /** "PRIVATE"/"PUBLIC" */
    val type: String,
    /** 12 位小写十六进制（对齐 iOS %012llx） */
    val mac: String,
    val mtu: Int,
    val broadcastEnabled: Boolean,
    val bridge: Boolean,
    /** "ip/prefix" */
    val assignedAddrs: List<String>,
    /** "dest/prefix" 或 "dest/prefix via gw" */
    val routes: List<String>,
    /** 网络下发的 DNS 服务器（不含端口） */
    val dns: List<String>,
)

data class NetworkDetailUiState(
    /** 持久化条目（null = 加载中或已被删除） */
    val entry: NetworkEntry? = null,
    val snapshot: NetworkSnapshot? = null,
    val selectedPlanetId: String = PlanetProfile.OFFICIAL_ID,
    val activePlanetId: String? = null,
    /** 同行星下是否还有其它已启用网络（routeVia 锁定判定的第二个条件） */
    val otherActivatedExists: Boolean = false,
)

/**
 * 网络详情页状态（对齐 iOS NetworkDetailView 的数据通路差异）：
 * iOS 是 onAppear/refreshable 经 ProviderMessage 向扩展拉取快照（拉模式），
 * Android 与隧道同进程——快照改走 TunnelStateBus.networkConfigs 推模式流，
 * 配置写入侧维持「写 store → configChanges → 隧道热重配」同构语义。
 */
class NetworkDetailViewModel(
    private val nwid: String,
    application: Application,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(NetworkDetailUiState())
    val ui: StateFlow<NetworkDetailUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            // store 侧：初始加载 + configChanges 全量重读（level-triggered 契约）
            reloadStoreState()
            StoreRepo.configChanges.collect { reloadStoreState() }
        }
        viewModelScope.launch {
            // 快照侧：推模式订阅（停机清空 → 页面回退显示「—」）
            TunnelStateBus.networkConfigs.collect { configs ->
                _ui.update { it.copy(snapshot = configs[nwid]?.toSnapshot()) }
            }
        }
    }

    private suspend fun reloadStoreState() {
        val store = StoreRepo.store
        val byPlanet = store.readNetworksByPlanet()
        var hitPlanetId: String? = null
        var hitEntry: NetworkEntry? = null
        var others = false
        for ((planetId, list) in byPlanet) {
            val index = list.indexOfFirst { it.networkId == nwid }
            if (index < 0) continue
            hitPlanetId = planetId
            hitEntry = list[index]
            others = list.any { it.networkId != nwid && it.lastActivated }
            break
        }
        _ui.update {
            it.copy(
                entry = hitEntry,
                selectedPlanetId = store.selectedPlanetId(),
                activePlanetId = store.activePlanetId(),
                otherActivatedExists = others,
            )
        }
    }

    /** 「应用 DNS 配置」：写 dnsMode/customDns（network/none 模式清空自定义列表，对齐 iOS applyDNS）。 */
    fun applyDns(mode: DnsMode, servers: List<String>) {
        viewModelScope.launch {
            mutateEntry { it.copy(dnsMode = mode, customDns = if (mode == DnsMode.custom) servers else emptyList()) }
        }
    }

    /** routeVia 开关（立即写库，通知隧道热重配）。 */
    fun setRouteVia(on: Boolean) {
        viewModelScope.launch { mutateEntry { it.copy(routeViaZeroTier = on) } }
    }

    /** 删除网络条目（隧道经 configChanges 自动 leave）。[onDeleted] 供视图退栈。 */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val planetId = _ui.value.entry?.planetId
            StoreRepo.store.deleteNetwork(networkId = nwid, planetId = planetId)
            onDeleted()
        }
    }

    /** 读-改-写整组替换（writeNetworks 按 planet 整组盖写，必须传全量列表）。 */
    private suspend fun mutateEntry(transform: (NetworkEntry) -> NetworkEntry) {
        val byPlanet = StoreRepo.store.readNetworksByPlanet()
        for ((planetId, list) in byPlanet) {
            val index = list.indexOfFirst { it.networkId == nwid }
            if (index < 0) continue
            val updated = list.toMutableList()
            updated[index] = transform(updated[index])
            StoreRepo.store.writeNetworks(updated, planetId)
            return
        }
    }
}

private fun NetConf.toSnapshot(): NetworkSnapshot = NetworkSnapshot(
    name = name,
    statusCode = status,
    type = type,
    mac = "%012x".format(mac),
    mtu = mtu,
    broadcastEnabled = broadcastEnabled,
    bridge = bridge,
    assignedAddrs = assignedAddrs,
    routes = routes,
    dns = dns,
)

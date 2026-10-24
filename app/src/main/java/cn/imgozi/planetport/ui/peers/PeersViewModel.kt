package cn.imgozi.planetport.ui.peers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.store.StoreRepo
import cn.imgozi.planetport.tunnel.PeerInfo
import cn.imgozi.planetport.tunnel.TunnelState
import cn.imgozi.planetport.tunnel.TunnelStateBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Peers 列表页状态（对齐 iOS PeerListView）。
 *
 * 数据源 = [TunnelStateBus.peers] 订阅流（隧道 Running 态 5s 周期推流），
 * 页面只订阅不直查——「下拉刷新」语义即展示当前流值，故不做 JNI 直查。
 * [loaded] 表达「查询中 → 已查询」迁移：等待首个非空推流（至多一个推流周期，
 * 6s 超时），超时按「已查询」处理（隧道在跑但确实没有 peer 的情形）。
 */
class PeersViewModel : ViewModel() {

    val peers: StateFlow<List<PeerInfo>> = TunnelStateBus.peers

    val tunnelState: StateFlow<TunnelState> = TunnelStateBus.tunnelState

    /** false = 尚未拿到首个推流（查询中转圈）；true = 后续空列表即真实空态。 */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** 活跃行星展示名（空态文案用，对齐 iOS app.activePlanet.displayName）。 */
    private val _planetName = MutableStateFlow(PlanetProfile.OFFICIAL.name)
    val planetName: StateFlow<String> = _planetName.asStateFlow()

    init {
        viewModelScope.launch {
            // 5s 推流周期 + 1s 余量；超时说明隧道在跑但列表为空
            withTimeoutOrNull(6_000) { TunnelStateBus.peers.first { it.isNotEmpty() } }
            _loaded.value = true
        }
        viewModelScope.launch {
            // 先建立订阅再刷新：configChanges 是无 replay 的 SharedFlow，
            // 若刷新先行，刷新期间到达的变更事件会丢失
            launch { StoreRepo.configChanges.collect { refreshPlanetName() } }
            refreshPlanetName()
        }
    }

    private suspend fun refreshPlanetName() {
        val store = StoreRepo.store
        val id = store.activePlanetId()
        _planetName.value = store.readPlanets().firstOrNull { it.id == id }?.name
            ?: PlanetProfile.OFFICIAL.name
    }
}

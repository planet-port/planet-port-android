package cn.imgozi.planetport.ui.peers

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.PortableWifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.tunnel.PeerInfo
import cn.imgozi.planetport.tunnel.TunnelState
import cn.imgozi.planetport.ui.components.Badge
import cn.imgozi.planetport.ui.components.EmptyStateCard
import cn.imgozi.planetport.ui.components.SectionCard
import cn.imgozi.planetport.ui.components.StatusDot
import cn.imgozi.planetport.ui.theme.HomeMetrics
import cn.imgozi.planetport.ui.theme.LocalPlanetStatusColors
import cn.imgozi.planetport.ui.theme.PlanetFonts
import cn.imgozi.planetport.ui.theme.tertiaryTextColor

/**
 * Peers 列表页（对齐 iOS PeerListView，诊断用途）。
 *
 * 数据源 = TunnelStateBus.peers 订阅流（隧道 Running 态 5s 周期推流），页面只订阅：
 * 下拉刷新手势省略——流本身已周期自刷新，刷新语义即「展示当前流值」。
 * 状态三分：查询中（转圈）/ 隧道未运行 / 运行但列表空，均复用空态卡形态。
 * Peer 卡：地址 10 位 hex 等宽 + 角色徽标（PLANET/MOON/LEAF，主色 0.12 底）
 * + 次行版本 + 延迟（6dp 点 + N ms，<30 绿 <100 橙 ≥100 红；未知显示「-」）
 * + 第三行：无路径 → 橙色「中转 relay」徽标；有路径 → 第一条优选物理路径
 * （对齐 iOS ZTBridge.peers 的单路径选择：取到第一条有效路径即 break）。
 *
 * @param onBack 返回回调（AppRoot 手写页面栈接线；默认空便于独立预览）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListView(onBack: () -> Unit = {}) {
    val vm: PeersViewModel = viewModel()
    val peers by vm.peers.collectAsState()
    val tunnelState by vm.tunnelState.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val planetName by vm.planetName.collectAsState()

    // 隧道存活（对齐 TunnelController.isRunning 判定）：查询中/运行中的数据才有意义
    val tunnelUp = when (tunnelState) {
        is TunnelState.Running, is TunnelState.Starting, is TunnelState.Reconnecting -> true
        else -> false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Peers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                peers.isNotEmpty() -> PeerList(peers)
                !tunnelUp -> StatusCard {
                    EmptyStateCard(
                        icon = Icons.Filled.PortableWifiOff,
                        title = "隧道未运行",
                        hint = "开启网络后可查看实时连接",
                    )
                }
                !loaded -> StatusCard {
                    // 查询中（对齐 iOS ProgressView + peers.loading）
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("正在查询 Peers…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                else -> StatusCard {
                    EmptyStateCard(
                        icon = Icons.Filled.Group,
                        title = "“$planetName”暂无 Peers",
                        hint = "列表约每 5 秒自动刷新",
                    )
                }
            }
        }
    }
}

/** 状态卡统一的页面边距容器（对齐 iOS listRowInsets leading/trailing 20）。 */
@Composable
private fun StatusCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = HomeMetrics.pageHMargin, vertical = 8.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun PeerList(peers: List<PeerInfo>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = HomeMetrics.pageHMargin, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(HomeMetrics.cardSpacing),
    ) {
        items(peers, key = { it.address }) { peer ->
            PeerCard(peer)
        }
    }
}

/**
 * Peer 卡（对齐 iOS peerCard）：地址 + 角色徽标 / 版本 + 延迟 / 优选路径或中转徽标。
 */
@Composable
private fun PeerCard(peer: PeerInfo) {
    val statusColors = LocalPlanetStatusColors.current
    val tertiary = tertiaryTextColor(isSystemInDarkTheme())

    SectionCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "%010x".format(peer.address),
                    style = PlanetFonts.MonoBody,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                if (peer.role.isNotEmpty()) {
                    Badge(text = peer.role, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = peer.version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                LatencyText(peer.latency)
            }
            if (peer.paths.isEmpty()) {
                // 无直连路径 = 经 planet/root 中转，性能劣化信号，橙色标识
                Badge(
                    text = "中转 relay",
                    color = statusColors.warn,
                    icon = Icons.AutoMirrored.Filled.AltRoute,
                )
            } else {
                // 优选物理路径：对齐 iOS ZTBridge.peers，取第一条有效路径展示
                Text(
                    text = peer.paths.first(),
                    style = PlanetFonts.MonoCaption,
                    color = tertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 延迟展示：>0 语义色 + 6dp 点；≤0 显示「-」。
 * 核心对未知延迟写 -1（Node.cpp：p->latency >= 0xffff → -1），SDK 未测得为 0，
 * `<=0` 守卫同时覆盖两者（Peer.java「zero if unknown」注释已过时）。
 */
@Composable
private fun LatencyText(latency: Int) {
    if (latency <= 0) {
        Text(
            text = "-",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val color = latencyColor(latency)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusDot(color = color)
        Text(
            text = "$latency ms",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** 延迟语义色（对齐 iOS latencyColor）：<30 好 / <100 注意 / ≥100 危险。 */
@Composable
private fun latencyColor(ms: Int): Color {
    val statusColors = LocalPlanetStatusColors.current
    return when {
        ms < 30 -> statusColors.good
        ms < 100 -> statusColors.warn
        else -> statusColors.danger
    }
}

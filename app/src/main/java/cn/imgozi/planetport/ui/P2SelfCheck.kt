package cn.imgozi.planetport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.imgozi.planetport.store.AppConfigStore
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.store.StorePaths
import cn.imgozi.planetport.tunnel.TunnelState
import cn.imgozi.planetport.tunnel.TunnelStateBus
import cn.imgozi.planetport.tunnel.ZeroTierVpnService
import kotlinx.coroutines.launch

/** ZT_VirtualNetworkStatus 原始码 → 验证页文案（正式文案映射 P4 落地） */
private fun ztStatusText(code: Int): String = when (code) {
    0 -> "请求配置中"; 1 -> "正常"; 2 -> "拒绝访问"; 3 -> "网络不存在"
    4 -> "端口错误"; 5 -> "客户端过旧"; 6 -> "待授权"; else -> "未知($code)"
}

private fun stateText(state: TunnelState): String = when (state) {
    is TunnelState.Idle -> "空闲"
    is TunnelState.Starting -> "启动中"
    is TunnelState.Running -> "运行中"
    is TunnelState.Reconnecting -> "重连中(${state.reason})"
    is TunnelState.Stopped -> "已停止${state.reason?.let { "($it)" } ?: ""}"
    is TunnelState.Failed -> "失败(${state.reason})"
}

/**
 * P2 验证页（临时脚手架，P4 换正式 UI）：启停隧道、临时 join 网络、
 * 状态/事件日志观察。join 按钮写 networks.json——P1 configChanges 自动
 * 触发 applyConfig，形成「UI 写 store → 隧道 join」的正式数据通路。
 */
@Composable
internal fun P2SelfCheckScreen(activity: MainActivity) {
    val state by TunnelStateBus.tunnelState.collectAsState()
    val node by TunnelStateBus.nodeStatus.collectAsState()
    val statuses by TunnelStateBus.networkStatuses.collectAsState()
    val trace by TunnelStateBus.applyTrace.collectAsState()
    val log by TunnelStateBus.eventLog.collectAsState()
    var nwid by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("隧道: ${stateText(state)}", style = MaterialTheme.typography.titleMedium)
        Text("节点: ${node?.nodeId ?: "-"}  核心 ${node?.coreVersion ?: "-"}  在线=${node?.online ?: false}")
        for ((n, s) in statuses) Text("网络 $n: ${ztStatusText(s)}")

        OutlinedTextField(
            value = nwid, onValueChange = { nwid = it.trim() },
            label = { Text("网络 ID（16 位 hex）") }, singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                activity.requestVpnPermission {
                    ZeroTierVpnService.start(activity)
                }
            }) { Text("启动") }
            Button(
                enabled = nwid.length == 16 &&
                    nwid.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' },
                onClick = {
                    val s = AppConfigStore(StorePaths(activity.filesDir))
                    scope.launch {
                        val planetId = s.activePlanetId()
                        val existing = s.readNetworks(planetId).filter { it.networkId != nwid.lowercase() }
                        val entry = NetworkEntry(
                            networkId = nwid.lowercase(),
                            networkName = "p2-${nwid.takeLast(4)}",
                            lastActivated = true,
                            routeViaZeroTier = false,
                            dnsMode = DnsMode.none,
                            customDns = emptyList(),
                            planetId = planetId,
                        )
                        // 其余网络置静默（P2 单网验证语义）
                        s.writeNetworks(
                            existing.map { it.copy(lastActivated = false) } + entry, planetId
                        )
                    }
                },
            ) { Text("加入") }
            Button(onClick = { ZeroTierVpnService.stop(activity) }) { Text("停止") }
        }

        Text("applyTrace:", fontSize = 12.sp)
        Text(trace.ifEmpty { "（空）" }, fontSize = 12.sp)
        Text("事件日志:", fontSize = 12.sp)
        Text(log.ifEmpty { "（空）" }, fontSize = 12.sp)
    }
}

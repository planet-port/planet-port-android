package cn.imgozi.planetport.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.ui.components.DnsConfigSection
import cn.imgozi.planetport.ui.components.isDnsConfigValid
import cn.imgozi.planetport.ui.form.FormRow
import cn.imgozi.planetport.ui.form.FormSection
import cn.imgozi.planetport.ui.form.FormToggleRow
import cn.imgozi.planetport.ui.theme.PlanetFonts
import cn.imgozi.planetport.ui.ztNetworkStatusText

/**
 * 网络详情页（iOS NetworkDetailView.swift 1:1）：
 * 八行静态+实时合并信息 / 分配的地址 / 托管路由 / DNS（含网络下发只读区与应用按钮）/
 * routeVia 开关（多网连接锁定）/ 删除（AlertDialog 确认）。
 *
 * 快照走 TunnelStateBus.networkConfigs 推模式（iOS 为 ProviderMessage 拉模式，见 VM 注释）。
 *
 * @param nwid 16 位十六进制网络 ID（自 Page.NetworkDetail 取）
 * @param onBack 页面退栈回调（删除成功后也经此退出，对齐 iOS dismiss）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailView(
    nwid: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: NetworkDetailViewModel = viewModel(key = "network-detail-$nwid") {
        NetworkDetailViewModel(nwid, app)
    }
    val ui by vm.ui.collectAsState()
    val entry = ui.entry

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("网络详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (entry == null) {
            // 加载中 / 条目不存在（已被删除）：iOS 端 entry 由上一页传入不存在此态，
            // Android 页面只持 nwid，首读完成仍无条目时给出明确文案
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (ui.loaded) {
                    Text(
                        "网络不存在或已被删除",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
            return@Scaffold
        }
        NetworkDetailContent(
            entry = entry,
            snapshot = ui.snapshot,
            routeViaLocked = ui.selectedPlanetId == ui.activePlanetId && ui.otherActivatedExists,
            onApplyDns = vm::applyDns,
            onSetRouteVia = vm::setRouteVia,
            onDelete = { vm.delete(onBack) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@Composable
private fun NetworkDetailContent(
    entry: NetworkEntry,
    snapshot: NetworkSnapshot?,
    routeViaLocked: Boolean,
    onApplyDns: (DnsMode, List<String>) -> Unit,
    onSetRouteVia: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // DNS 编辑态：条目首次就绪时初始化一次；后续 store 回读不打断编辑（对齐 iOS @State dns）
    var dnsMode by remember(entry.networkId) { mutableStateOf(entry.dnsMode) }
    var dnsServers by remember(entry.networkId) { mutableStateOf(entry.customDns) }
    var showDelete by remember { mutableStateOf(false) }

    val dnsDirty = dnsMode != entry.dnsMode || dnsServers != entry.customDns

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FormSection {
            FormRow("网络 ID", entry.networkId)
            FormRow("名称", snapshot?.name ?: entry.networkName)
            FormRow("状态", snapshot?.let { ztNetworkStatusText(it.statusCode) } ?: "—")
            FormRow("类型", snapshot?.type ?: "—")
            FormRow("MAC", snapshot?.mac ?: "—")
            FormRow("MTU", snapshot?.let { it.mtu.toString() } ?: "—")
            FormRow("广播", snapshot?.let { if (it.broadcastEnabled) "允许" else "禁止" } ?: "—")
            FormRow("桥接", snapshot?.let { if (it.bridge) "是" else "否" } ?: "—")
        }

        FormSection(header = "分配的地址") {
            MonoListOrEmpty(snapshot?.assignedAddrs.orEmpty())
        }

        FormSection(header = "托管路由") {
            MonoListOrEmpty(snapshot?.routes.orEmpty())
        }

        FormSection(
            header = "DNS",
            // iOS network.dnsFooter：热重配不重连
            footer = "修改后需点「应用」生效；隧道运行时会自动重配，无需断开重连。",
        ) {
            DnsConfigSection(
                mode = dnsMode,
                dns = dnsServers,
                enabled = true,
                onChange = { mode, servers ->
                    dnsMode = mode
                    dnsServers = servers
                },
                // 网络模式追加只读下发区（iOS 在 dns.mode == .network 时展示 snapshot.dns）
                showNetworkDns = if (dnsMode == DnsMode.network) snapshot?.dns.orEmpty() else null,
            )
            if (dnsDirty) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onApplyDns(dnsMode, dnsServers) },
                    enabled = isDnsConfigValid(dnsMode, dnsServers),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("应用 DNS 配置")
                }
            }
        }

        FormSection(
            footer = if (routeViaLocked) {
                "多网络同时连接时不可用"
            } else {
                "开启后该网络的默认路由经 ZeroTier，立即生效"
            },
        ) {
            FormToggleRow(
                title = "经 ZeroTier 路由（默认路由）",
                checked = entry.routeViaZeroTier,
                onCheckedChange = onSetRouteVia,
                enabled = !routeViaLocked,
            )
        }

        FormSection {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable { showDelete = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "删除网络",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除这个网络？") },
            text = { Text("将从当前行星的网络列表中移除；若隧道正在使用，将停止加入该网络。") },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete()
                }) {
                    Text("删除网络", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 等宽列表（地址/路由），空表显示「—」（对齐 iOS ForEach / Text("—")）。 */
@Composable
private fun MonoListOrEmpty(values: List<String>) {
    if (values.isEmpty()) {
        Text(
            "—",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        return
    }
    Column {
        values.forEach { value ->
            Text(
                value,
                style = PlanetFonts.MonoBody,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            )
        }
    }
}

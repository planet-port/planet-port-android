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
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.ui.components.DnsConfigSection
import cn.imgozi.planetport.ui.components.isDnsConfigValid
import cn.imgozi.planetport.ui.form.FormRow
import cn.imgozi.planetport.ui.form.FormSection
import cn.imgozi.planetport.ui.form.FormToggleRow
import cn.imgozi.planetport.ui.theme.PlanetFonts
import cn.imgozi.planetport.ui.ztStatusLabel

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
                title = { Text(stringResource(R.string.network_detailTitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
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
                        stringResource(R.string.network_notFoundOrDeleted),
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
            // 锁定判定锚定本网络归属行星（iOS 19f7681）：只有隧道行星下才存在
            // 「同时连接」的其他网络；全局选中页锚定在滑页竞态下会瞬时翻转
            routeViaLocked = ui.entryPlanetId != null &&
                ui.entryPlanetId == ui.activePlanetId && ui.otherActivatedExists,
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
            FormRow(stringResource(R.string.network_id), entry.networkId)
            FormRow(
                stringResource(R.string.common_name),
                snapshot?.name ?: entry.networkName,
            )
            FormRow(
                stringResource(R.string.common_status),
                snapshot?.let { stringResource(ztStatusLabel(it.statusCode)) } ?: "—",
            )
            FormRow(stringResource(R.string.common_type), snapshot?.type ?: "—")
            FormRow("MAC", snapshot?.mac ?: "—")
            FormRow("MTU", snapshot?.let { it.mtu.toString() } ?: "—")
            FormRow(
                stringResource(R.string.network_broadcast),
                snapshot?.let {
                    stringResource(if (it.broadcastEnabled) R.string.common_allowed else R.string.common_forbidden)
                } ?: "—",
            )
            FormRow(
                stringResource(R.string.network_bridge),
                snapshot?.let {
                    stringResource(if (it.bridge) R.string.common_yes else R.string.common_no)
                } ?: "—",
            )
        }

        FormSection(header = stringResource(R.string.network_assignedAddrs)) {
            MonoListOrEmpty(snapshot?.assignedAddrs.orEmpty())
        }

        FormSection(header = stringResource(R.string.network_managedRoutes)) {
            MonoListOrEmpty(snapshot?.routes.orEmpty())
        }

        FormSection(
            header = "DNS",
            // iOS network.dnsFooter：热重配不重连
            footer = stringResource(R.string.network_dnsFooter),
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
                    Text(stringResource(R.string.network_applyDns))
                }
            }
        }

        FormSection(
            footer = if (routeViaLocked) {
                stringResource(R.string.network_routeVia_lockedFooter)
            } else {
                stringResource(R.string.network_routeVia_footer)
            },
        ) {
            FormToggleRow(
                title = stringResource(R.string.network_routeVia),
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
                    stringResource(R.string.network_delete),
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
            title = { Text(stringResource(R.string.network_delete_confirmTitle)) },
            text = { Text(stringResource(R.string.network_delete_confirmMessage)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    onDelete()
                }) {
                    Text(
                        stringResource(R.string.network_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
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

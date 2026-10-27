package cn.imgozi.planetport.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import android.graphics.Bitmap
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.ui.components.CopyableRow
import cn.imgozi.planetport.ui.components.CopyableValue
import cn.imgozi.planetport.ui.components.DnsConfigSection
import cn.imgozi.planetport.ui.components.SheetScaffold
import cn.imgozi.planetport.ui.components.isDnsConfigValid
import cn.imgozi.planetport.ui.connectPhaseLabelRes
import cn.imgozi.planetport.ui.form.FormRow
import cn.imgozi.planetport.ui.form.FormSection
import cn.imgozi.planetport.ui.form.FormToggleRow
import cn.imgozi.planetport.ui.home.HomeViewModel
import cn.imgozi.planetport.ui.rememberConnectNowMs
import cn.imgozi.planetport.ui.theme.PlanetFonts
import cn.imgozi.planetport.ui.ztStatusLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 网络详情页（iOS NetworkDetailView.swift 1:1）：
 * 八行静态+实时合并信息 / 分配的地址 / 托管路由 / DNS（含网络下发只读区与应用按钮）/
 * routeVia 开关（多网连接锁定）/ 删除（AlertDialog 确认）。
 *
 * 快照走 TunnelStateBus.networkConfigs 推模式（iOS 为 ProviderMessage 拉模式，见 VM 注释）。
 *
 * @param planetId 条目归属行星（自 Page.NetworkDetail 取；同 ID 网络跨行星时
 *   仅凭 nwid 会定位到错误条目，iOS 由卡片直传 entry 天然带归属）
 * @param nwid 16 位十六进制网络 ID（自 Page.NetworkDetail 取）
 * @param onBack 页面退栈回调（删除成功后也经此退出，对齐 iOS dismiss）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailView(
    planetId: String,
    nwid: String,
    onBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: NetworkDetailViewModel = viewModel(key = "network-detail-$planetId-$nwid") {
        NetworkDetailViewModel(planetId, nwid, app)
    }
    val ui by vm.ui.collectAsState()
    val entry = ui.entry
    // 连接分段展示依赖 App 侧连接计时（对齐 iOS NetworkDetailView 持 app: AppState）：
    // HomeViewModel 是 Activity 级单例，此处取得与首页同一实例
    val homeVm: HomeViewModel = viewModel()
    val homeState by homeVm.state.collectAsState()
    val connectNowMs = rememberConnectNowMs(homeState.connectStartedAtMs).value

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
            nodeOnline = homeState.nodeOnline,
            connectStartedAtMs = homeState.connectStartedAtMs,
            connectNowMs = connectNowMs,
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
    nodeOnline: Boolean,
    connectStartedAtMs: Long?,
    connectNowMs: Long,
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
    var showQR by remember { mutableStateOf(false) }
    // 已切到 via 网关视图的路由下标（点击值区域切换）
    var viaExpandedRoutes by remember { mutableStateOf(setOf<Int>()) }

    val dnsDirty = dnsMode != entry.dnsMode || dnsServers != entry.customDns

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FormSection {
            NetworkIdRow(
                networkId = entry.networkId,
                onShowQR = { showQR = true },
            )
            FormRow(
                stringResource(R.string.common_name),
                snapshot?.name ?: entry.networkName,
            )
            FormRow(
                stringResource(R.string.common_status),
                snapshot?.let {
                    // REQUESTING_CONFIGURATION 与卡片一致按 online 分段
                    //（连接服务器/请求网络配置/连接超时，iOS 897245a）
                    if (it.statusCode == 0) {
                        stringResource(connectPhaseLabelRes(nodeOnline, connectStartedAtMs, connectNowMs))
                    } else {
                        stringResource(ztStatusLabel(it.statusCode))
                    }
                } ?: "—",
            )
            FormRow(stringResource(R.string.common_type), snapshot?.type ?: "—")
            CopyableRow("MAC", macText(snapshot))
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
            MonoListOrEmpty(
                snapshot?.assignedAddrs.orEmpty(),
                labelRes = R.string.network_addrLabel,
                emptyLabelRes = R.string.network_addr,
                copyable = true,
            )
        }

        FormSection(header = stringResource(R.string.network_managedRoutes)) {
            val routes = snapshot?.routes.orEmpty()
            if (routes.isEmpty()) {
                // 空表仍渲染标签行 +「—」（与 MonoListOrEmpty 空态同构）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.network_route), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                routes.forEachIndexed { index, route ->
                    RouteRow(
                        index = index,
                        route = route,
                        viaExpanded = index in viaExpandedRoutes,
                        toggle = {
                            viaExpandedRoutes = if (index in viaExpandedRoutes) {
                                viaExpandedRoutes - index
                            } else {
                                viaExpandedRoutes + index
                            }
                        },
                    )
                }
            }
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

    if (showQR) {
        NetworkQRSheet(
            networkId = entry.networkId,
            networkName = snapshot?.name?.takeIf { it.isNotEmpty() } ?: entry.networkName,
            onDismiss = { showQR = false },
        )
    }
}

/**
 * 网络 ID 行（iOS networkIdRow）：值可点复制（无常驻图标，避免与二维码入口双图标）
 * + 二维码按钮。
 */
@Composable
private fun NetworkIdRow(networkId: String, onShowQR: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.network_id), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        CopyableValue(networkId, showsIcon = false)
        Icon(
            Icons.Filled.QrCode2,
            contentDescription = stringResource(R.string.qrcode_title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier
                .padding(start = 12.dp)
                .size(15.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.qrcode_title),
                ) { onShowQR() },
        )
    }
}

/**
 * 路由行（iOS RouteRow）：路由串较长（`目标 via 网关`），默认只显示目标网段，
 * 点击值区域切换为 via 网关；直连路由（无 via）不可切换、不显示图标。
 * 展开状态由调用方按下标追踪（路由字符串可能重复，不能用内容当 key）。
 */
@Composable
private fun RouteRow(
    index: Int,
    route: String,
    viaExpanded: Boolean,
    toggle: () -> Unit,
) {
    // "192.168.8.0/24 via 152.124.13.2" → 目标 / via 网关
    val parts = route.split(" via ", limit = 2)
    val target = parts[0]
    val via = parts.getOrNull(1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.network_routeLabel, index + 1), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        if (via != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = toggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (viaExpanded) "via $via" else target,
                    style = PlanetFonts.MonoBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    textAlign = TextAlign.End,
                )
                Icon(
                    // swap_horiz 左右对称，material-icons 1.7.8 无 AutoMirrored 变体，
                    // 直接用 Outlined 版（对应 iOS arrow.left.arrow.right）
                    Icons.Outlined.SwapHoriz,
                    contentDescription = stringResource(R.string.network_managedRoutes),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Text(
                target,
                style = PlanetFonts.MonoBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}

/** MAC 常用格式 aa:bb:cc:dd:ee:ff（隧道传 12 位无分隔 hex，UI 层格式化，iOS 同构）。 */
private fun macText(snapshot: NetworkSnapshot?): String {
    val raw = snapshot?.mac ?: return "—"
    val hex = raw.lowercase()
    if (hex.length != 12 || hex.any { Character.digit(it, 16) < 0 }) return raw
    return hex.chunked(2).joinToString(":")
}

/**
 * 网络 ID 二维码分享 sheet（iOS NetworkQRSheet）：二维码（内容 = 网络 ID 纯文本，
 * 与 App 内扫码填 ID 互逆）+ 网络名称 + 网络 ID（点击复制）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkQRSheet(
    networkId: String,
    networkName: String,
    onDismiss: () -> Unit,
) {
    // 纠错 M 对齐 iOS CIQRCodeGenerator inputCorrectionLevel；420px 足够 16 hex 字符清晰。
    // 编码 + 位图分配放后台线程（组合期主线程同步生成在低端机开 sheet 会掉帧），
    // 生成完成前省略图位
    val qr by produceState<ImageBitmap?>(initialValue = null, networkId) {
        value = withContext(Dispatchers.Default) { qrImage(networkId) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 统一 large detent（屏高-16dp）：跳过半展开，直接全高
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // 统一 large detent 高度容器（屏幕高度 - 16dp，与其他弹窗一致）
        SheetScaffold {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.qrcode_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                qr?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.qrcode_title),
                        // 最近邻缩放保持模块边缘锐利（iOS interpolation(.none)）
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(200.dp),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        networkName.ifEmpty { networkId },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    CopyableValue(
                        networkId,
                        style = PlanetFonts.MonoCaption,
                        iconSize = 9.dp,
                    )
                }
            }
        }
    }
}

/** zxing 生成二维码位图（失败返回 null，sheet 中省略图位）。 */
private fun qrImage(content: String, size: Int = 420): ImageBitmap? {
    return try {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val offset = y * size
            for (x in 0 until size) {
                pixels[offset + x] = if (matrix[x, y]) 0xFF000000u.toInt() else 0xFFFFFFFFu.toInt()
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (e: Exception) {
        android.util.Log.e("PlanetPort", "qrImage failed", e)
        null
    }
}

/**
 * 等宽列表（地址/路由），空表仍渲染标签行 +「—」（对齐 iOS row(loc("network.addr"), "—")）。
 * 每行左「地址/路由 N」标签（[labelRes]，iOS network.addrLabel / network.routeLabel）、
 * 右等宽值（FormRow 同构：值右对齐）；地址行值可复制。
 * 空表行标签用 [emptyLabelRes]（iOS network.addr / network.route，不带序号）。
 */
@Composable
private fun MonoListOrEmpty(
    values: List<String>,
    @StringRes labelRes: Int,
    @StringRes emptyLabelRes: Int,
    copyable: Boolean = false,
) {
    if (values.isEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(emptyLabelRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "—",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column {
        values.forEachIndexed { index, value ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(labelRes, index + 1),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.weight(1f).padding(start = 12.dp))
                if (copyable) {
                    CopyableValue(value)
                } else {
                    Text(
                        value,
                        style = PlanetFonts.MonoBody,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

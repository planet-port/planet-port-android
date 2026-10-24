package cn.imgozi.planetport.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.ui.LocalNavigator
import cn.imgozi.planetport.ui.MainActivity
import cn.imgozi.planetport.ui.NetworkCardStatus
import cn.imgozi.planetport.ui.Page
import cn.imgozi.planetport.ui.components.EmptyStateCard
import cn.imgozi.planetport.ui.deriveNetworkCardStatus
import cn.imgozi.planetport.ui.theme.HomeMetrics
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * 主页（对齐 iOS HomeView.swift）：头区（logo/标题/当前行星链接 chip/设置）+
 * 行星分页（末位「添加」页）+ 圆点指示器 + 右下角加入网络 FAB。
 *
 * 分页职责划分：每页持独立网络列表与状态判定（statusCode 仅当前隧道行星传入，
 * 规避总线遗留状态），开关编排经 VM（软上限确认回传 [limitTarget] 弹窗）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeView(activity: MainActivity) {
    val vm: HomeViewModel = viewModel()
    val state by vm.state.collectAsState()
    val pending by vm.pendingNetworks.collectAsState()
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()

    var showJoin by remember { mutableStateOf(false) }
    var addPreviewName by remember { mutableStateOf("") }
    // 软上限确认：entry + 「仍要开启」时执行的 force 闭包
    var limitTarget by remember { mutableStateOf<Pair<NetworkEntry, () -> Unit>?>(null) }
    var notice by remember { mutableStateOf<HomeEvent.Notice?>(null) }

    // 页数 = 行星数 + 末位添加页；索引 ↔ 行星换算唯一入口
    val pageCount = state.planets.size + 1
    fun planetAtIndex(i: Int): PlanetProfile? = state.planets.getOrNull(i)
    fun indexOfPlanet(id: String): Int = state.planets.indexOfFirst { it.id == id }

    val pagerState = rememberPagerState(pageCount = { pageCount })

    // 首次加载定位到已选行星（无动画，避免首帧从官方行星甩过去）
    var initialAligned by remember { mutableStateOf(false) }
    LaunchedEffect(state.loaded) {
        if (state.loaded && !initialAligned) {
            initialAligned = true
            val idx = indexOfPlanet(state.selectedPlanetId)
            if (idx >= 0 && pagerState.settledPage != idx) pagerState.scrollToPage(idx)
        }
    }

    // 滑动落位 → 浏览上下文切换（只读，不触碰隧道；iOS onChange(currentTab)）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collectLatest { idx ->
            planetAtIndex(idx)?.let { if (it.id != state.selectedPlanetId) vm.selectPlanet(it.id) }
        }
    }
    // 外部选中变化（添加行星落库后选中新行星）→ 定位分页（无动画）
    LaunchedEffect(state.selectedPlanetId, state.loaded) {
        if (!state.loaded) return@LaunchedEffect
        val idx = indexOfPlanet(state.selectedPlanetId)
        if (idx >= 0 && pagerState.settledPage != idx && pagerState.currentPage != idx) {
            pagerState.scrollToPage(idx)
        }
    }

    DisposableEffect(vm) {
        vm.attachPermissionRequester(activity::requestVpnPermission)
        onDispose { }
    }

    // 一次性弹窗事件（无法启动 VPN / 网络状态异常）
    LaunchedEffect(vm) {
        vm.events.collect { notice = it as HomeEvent.Notice }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            HeaderZone(
                state = state,
                browsingActive = planetAtIndex(pagerState.settledPage)?.id == state.activePlanetId,
                onOpenActive = {
                    val idx = indexOfPlanet(state.activePlanetId)
                    if (idx >= 0) scope.launch { pagerState.animateScrollToPage(idx) }
                },
                onOpenPrefs = { navigator.navTo(Page.Prefs) },
            )

            // 圆点指示器：选中 20×6 主色胶囊 / 未选 6×6 灰，44dp 热区（iOS :349-378）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(pageCount) { idx ->
                    val selected = pagerState.settledPage == idx
                    // 颜色在组合期取值，draw lambda 非 composable 上下文
                    val dotColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { scope.launch { pagerState.animateScrollToPage(idx) } }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            Modifier.size(
                                width = if (selected) 20.dp else 6.dp,
                                height = 6.dp,
                            ),
                        ) {
                            drawRoundRect(
                                color = dotColor,
                                cornerRadius = CornerRadius(size.height),
                            )
                        }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                // 保持全部分页组合存活：各页网络列表/开关状态不因滑走而丢失（iOS TabView(.page)）
                beyondViewportPageCount = pageCount,
            ) { idx ->
                val planet = planetAtIndex(idx)
                if (planet == null) {
                    AddPlanetPage(
                        vm = vm,
                        state = state,
                        previewName = addPreviewName,
                        onPreviewName = { addPreviewName = it },
                        isCurrentPage = pagerState.settledPage == idx,
                    )
                } else {
                    PlanetPage(
                        state = state,
                        pending = pending,
                        planet = planet,
                        isActivePage = state.selectedPlanetId == state.activePlanetId,
                        // 整卡点击先 selectPlanet 再入设置（iOS HomeView.swift:372-375 同序；
                        // Moon 页归属与 moon 文件落盘目录都取 selectedPlanetId，此序是一致性前提）
                        onOpenPlanetSettings = {
                            vm.selectPlanet(planet.id)
                            navigator.navTo(Page.PlanetSettings(planet.id))
                        },
                        onJoin = { showJoin = true },
                        onOpenNetwork = { nwid -> navigator.navTo(Page.NetworkDetail(nwid)) },
                        onToggle = { entry, active ->
                            vm.setNetworkActive(planet.id, entry, active) { force ->
                                limitTarget = entry to force
                            }
                        },
                    )
                }
            }
        }

        // FAB：非添加页显示（iOS FabButton：56pt 圆、阴影 black 0.25 r10 y5）
        val onAddPage = planetAtIndex(pagerState.settledPage) == null
        if (!onAddPage) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 22.dp, bottom = 54.dp)
                    .size(56.dp)
                    .shadow(
                        elevation = 10.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.25f),
                        spotColor = Color.Black.copy(alpha = 0.25f),
                    )
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { showJoin = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "加入网络",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    if (showJoin && state.loaded) {
        JoinNetworkSheet(vm = vm, state = state, onDismiss = { showJoin = false })
    }

    // 软上限确认（iOS home.alert.openMore / limitConfirm / openAnyway）
    limitTarget?.let { (entry, force) ->
        AlertDialog(
            onDismissRequest = { limitTarget = null },
            title = { Text("开启更多网络") },
            text = {
                Text(
                    "同时连接多个网络会增加耗电，仍要开启「" +
                        entry.networkName.ifEmpty { entry.networkId } + "」？",
                )
            },
            dismissButton = {
                TextButton(onClick = { limitTarget = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    limitTarget = null
                    force()
                }) { Text("仍要开启", color = MaterialTheme.colorScheme.error) }
            },
        )
    }

    notice?.let { n ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(n.title) },
            text = { Text(n.message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("好") } },
        )
    }
}

/**
 * 行星分页内容（iOS PlanetPage 区段）：行星身份卡（整卡点击进设置）+ 发丝线 +
 * 计数行 + 网络卡列表/空态。状态判定与地址仅当前隧道行星携带（对齐 iOS
 * planetChanged 时清空 networkStatuses 的语义：总线遗留状态不用于非活跃行星）。
 */
@Composable
private fun PlanetPage(
    state: HomeUiState,
    pending: Set<String>,
    planet: PlanetProfile,
    isActivePage: Boolean,
    onOpenPlanetSettings: () -> Unit,
    onJoin: () -> Unit,
    onOpenNetwork: (String) -> Unit,
    onToggle: (NetworkEntry, Boolean) -> Unit,
) {
    val networks = state.planetNetworks[planet.id].orEmpty()
    val running = state.tunnelRunning
    val activeId = state.activePlanetId

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(top = HomeMetrics.pageTopPadding - 24.dp)
            .padding(horizontal = HomeMetrics.pageHMargin),
    ) {
        // 行星身份卡：整卡可点（无涟漪），按压缩放由卡片自持
        val cardSource = remember(planet.id) { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 24.dp)
                .clickable(
                    interactionSource = cardSource,
                    indication = null,
                    onClick = onOpenPlanetSettings,
                ),
        ) {
            PlanetCardView(
                planet = planet,
                nodeId = state.planetNodeIds[planet.id] ?: "-",
                moonCount = state.moonCounts[planet.id] ?: 0,
                showBadge = state.hasActivatedPlanet || planet.id == activeId,
                badgeInUse = planet.id == activeId && running,
                interactionSource = cardSource,
            )
        }

        // 发丝线 + 计数行（iOS networkHeader：网络 · N；连接 >1 时追加已连接数）
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
        )
        val connectedCount = networks.count { it.lastActivated }
        Text(
            text = buildString {
                append("网络 · ${networks.size}")
                if (isActivePage && running && connectedCount > 1) {
                    append(" · 已连接 $connectedCount")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )

        if (networks.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) {
                EmptyStateCard(
                    icon = Icons.Outlined.WifiOff,
                    title = "这颗行星还没有网络",
                    hint = "点击此处或右下角 + 添加",
                    onClick = onJoin,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 12.dp,
                    bottom = HomeMetrics.pageBottomReserve,
                ),
                verticalArrangement = Arrangement.spacedBy(HomeMetrics.cardSpacing),
            ) {
                items(networks, key = { it.networkId }) { entry ->
                    NetworkCardView(
                        entry = entry,
                        status = deriveNetworkCardStatus(
                            vpnRunning = running,
                            lastActivated = entry.lastActivated,
                            lastConflict = entry.lastConflict,
                            // 实时冲突仅当前隧道行星传入（收敛断开由 lastConflict 呈现）
                            liveConflict = if (isActivePage) {
                                state.networkConflicts[entry.networkId]
                            } else {
                                null
                            },
                            // statusCode 仅当前隧道行星传入，规避总线遗留状态
                            statusCode = if (isActivePage) {
                                state.networkStatuses[entry.networkId]
                            } else {
                                null
                            },
                            hasStatus = isActivePage &&
                                state.networkStatuses.containsKey(entry.networkId),
                        ),
                        isPending = entry.networkId in pending,
                        appearsActive = planet.id == activeId && entry.lastActivated,
                        addresses = if (isActivePage) state.assignedAddrs[entry.networkId] else null,
                        onToggle = { active -> onToggle(entry, active) },
                        onTap = { onOpenNetwork(entry.networkId) },
                    )
                }
            }
        }
    }
}

/**
 * 头区（iOS headerZone）：半透明背景渐变盖过状态栏，左 40dp logo + 标题，
 * 非当前隧道行星浏览且隧道在跑时显示「跳回活跃行星」链接 chip，右侧设置入口。
 */
@Composable
private fun HeaderZone(
    state: HomeUiState,
    browsingActive: Boolean,
    onOpenActive: () -> Unit,
    onOpenPrefs: () -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                // 底部渐隐至透明，覆盖状态栏（iOS ultraThinMaterial+渐隐 mask 降级）
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(bg.copy(alpha = 0.85f), bg.copy(alpha = 0f)),
                    ),
                )
            }
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo(Modifier.size(40.dp))
            Spacer(Modifier.size(10.dp))
            Text(
                "Planet Port",
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onOpenPrefs) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (!browsingActive && state.tunnelRunning) {
            val activeName = state.planets.firstOrNull { it.id == state.activePlanetId }?.name
                ?: "ZeroTier 官方"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenActive,
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    activeName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * App logo（iOS 资产 AppLogo：红渐变圆角方块 + 白色轮辐网络图形）。约束不改
 * res 资源结构，以 Canvas 复刻；后续引入 vector 资产可整体替换。
 */
@Composable
private fun AppLogo(modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(HomeMetrics.radiusLogo))) {
        val s = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFF13C31), Color(0xFFBE332B)),
            ),
            cornerRadius = CornerRadius(s * 0.225f),
        )
        // 中心轮辐 + 相邻外节点连线（0-2、1-3 两条对角线示意网状）
        val nodes = (0 until 5).map { i ->
            val angle = Math.toRadians((i * 72 - 90).toDouble())
            Offset(
                center.x + (s * 0.30f * cos(angle)).toFloat(),
                center.y + (s * 0.30f * sin(angle)).toFloat(),
            )
        }
        val stroke = s * 0.045f
        for (node in nodes) {
            drawLine(Color.White, center, node, strokeWidth = stroke, cap = StrokeCap.Round)
        }
        drawLine(Color.White, nodes[0], nodes[2], strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(Color.White, nodes[1], nodes[3], strokeWidth = stroke, cap = StrokeCap.Round)
        for (node in nodes) {
            drawCircle(Color.White, radius = s * 0.045f, center = node)
        }
        drawCircle(Color.White, radius = s * 0.10f, center = center)
    }
}

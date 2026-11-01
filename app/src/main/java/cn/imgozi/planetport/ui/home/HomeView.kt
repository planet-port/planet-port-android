package cn.imgozi.planetport.ui.home

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.BackgroundImageStore
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.store.PlanetKind
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
import kotlin.math.roundToInt

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

    DisposableEffect(vm) {
        vm.attachPermissionRequester(activity::requestVpnPermission)
        onDispose { }
    }

    // 一次性弹窗事件（无法启动 VPN / 网络状态异常）
    LaunchedEffect(vm) {
        vm.events.collect { notice = it as HomeEvent.Notice }
    }

    // 背景图（T7）：首组合读盘刷新内存态；设置页换图/清图后 store 流即时驱动重组
    val background by BackgroundImageStore.state.collectAsState()
    LaunchedEffect(Unit) { BackgroundImageStore.refresh(activity.applicationContext) }

    Box(Modifier.fillMaxSize()) {
        // 最底层背景（图 + 蒙纱 + 重力视差；无图退化为页面底色）
        HomeParallaxBackground(
            image = background.bitmap,
            parallaxEnabled = background.parallax,
        )

        // 数据就绪后才组合分页：以已选行星为 initialPage，首帧即正确页面。
        // 空表先组合会让第 0 页按「无行星」渲染成添加页，冷启动先闪新增页
        // 再跳回已选行星；无选中记录时 selectedPlanetId 回退官方行星（永在
        // 且最前）= 默认第 1 页
        if (state.loaded) {
            val pagerState = rememberPagerState(
                pageCount = { pageCount },
                initialPage = indexOfPlanet(state.selectedPlanetId).coerceAtLeast(0),
            )

            // 滑动落位 → 浏览上下文切换（只读，不触碰隧道；iOS onChange(currentTab)）
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.settledPage }.collectLatest { idx ->
                    planetAtIndex(idx)?.let { if (it.id != state.selectedPlanetId) vm.selectPlanet(it.id) }
                }
            }
            // 外部选中变化（添加行星落库后选中新行星）→ 定位分页（无动画）
            LaunchedEffect(state.selectedPlanetId) {
                val idx = indexOfPlanet(state.selectedPlanetId)
                if (idx >= 0 && pagerState.settledPage != idx && pagerState.currentPage != idx) {
                    pagerState.scrollToPage(idx)
                }
            }

            // 主容器整页（iOS TabView 全屏 + headerZone overlay：内容从头区下方滚过）
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
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
                        // 本页是否隧道行星锚定本页自身（iOS 19f7681：selectedPlanetId 全局
                        // 锚定在滑页过半时先变，旁页仍可见的卡片会瞬时丢实时状态/计数）
                        isActivePage = planet.id == state.activePlanetId,
                        // 整卡点击先 selectPlanet 再入设置（iOS HomeView.swift:372-375 同序；
                        // Moon 页归属与 moon 文件落盘目录都取 selectedPlanetId，此序是一致性前提）
                        onOpenPlanetSettings = {
                            vm.selectPlanet(planet.id)
                            navigator.navTo(Page.PlanetSettings(planet.id))
                        },
                        onJoin = { showJoin = true },
                        onOpenNetwork = { nwid -> navigator.navTo(Page.NetworkDetail(planet.id, nwid)) },
                        onToggle = { entry, active ->
                            vm.setNetworkActive(planet.id, entry, active) { force ->
                                limitTarget = entry to force
                            }
                        },
                    )
                }
            }

            // 顶部 overlay 头区（iOS .overlay(alignment: .top) headerZone）：标题 +
            // chip + 设置 + 圆点指示器，分页内容从其下方滚过
            HeaderZone(
                state = state,
                // chip 可见性锚定 currentPage（页面越过中点确认切页即翻转，与页面滑动
                // 同步起动画，对齐 iOS TabView selection 提交时机）；settledPage 要等
                // 页面完全停稳才翻转，chip 动画会滞后半页产生拖延感
                browsingActive = planetAtIndex(pagerState.currentPage)?.id == state.activePlanetId,
                onOpenActive = {
                    val idx = indexOfPlanet(state.activePlanetId)
                    if (idx >= 0) scope.launch { pagerState.animateScrollToPage(idx) }
                },
                onOpenPrefs = { navigator.navTo(Page.Prefs) },
                pageCount = pageCount,
                currentPage = pagerState.settledPage,
                onSelectPage = { idx -> scope.launch { pagerState.animateScrollToPage(idx) } },
            )

            // FAB：非添加页显示（iOS FabButton：可拖动 + 左右吸附 + 位置记忆）
            val onAddPage = planetAtIndex(pagerState.settledPage) == null
            if (!onAddPage) {
                FabLayer(onClick = { showJoin = true })
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
            title = { Text(stringResource(R.string.home_alert_openMore)) },
            text = {
                Text(
                    stringResource(
                        R.string.home_alert_limitConfirm,
                        entry.networkName.ifEmpty { entry.networkId },
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { limitTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    limitTarget = null
                    force()
                }) {
                    Text(
                        stringResource(R.string.home_alert_openAnyway),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }

    notice?.let { n ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(n.title) },
            text = { Text(n.message) },
            confirmButton = {
                TextButton(onClick = { notice = null }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }

    // 跨行星切换交接弹窗（用户裁定交互，两阶段）：确认阶段提示 + 可取消；
    // 断开阶段无按钮、不可取消、显示断开进度，停机终态确认后由 VM 关闭弹窗
    state.switchDialog?.let { dlg ->
        AlertDialog(
            // 断开阶段不可点外取消（交接已启动，配置已落）
            onDismissRequest = { if (!dlg.disconnecting) vm.cancelSwitchPlanet() },
            title = { Text(stringResource(R.string.home_alert_switchPlanet)) },
            text = {
                if (dlg.disconnecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.home_switch_disconnecting))
                    }
                } else {
                    // 目标网络名 + 当前活跃行星名（iOS 同参：displayName(target) +
                    // currentPlanetName，官方行星走本地化名）；文案内 **加粗** 标记
                    // 由 markdownBold 渲染
                    val targetName =
                        dlg.entry.networkName.ifEmpty { dlg.entry.networkId }
                    val currentPlanet = state.planets
                        .firstOrNull { it.id == state.activePlanetId }
                    Text(
                        markdownBold(
                            stringResource(
                                R.string.home_alert_switchPlanetMessage,
                                targetName,
                                currentPlanet?.displayNameRes()
                                    ?: stringResource(R.string.planet_official_name),
                            ),
                        ),
                    )
                }
            },
            dismissButton = {
                if (!dlg.disconnecting) {
                    TextButton(onClick = { vm.cancelSwitchPlanet() }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            },
            confirmButton = {
                if (!dlg.disconnecting) {
                    TextButton(onClick = { vm.confirmSwitchPlanet() }) {
                        Text(
                            stringResource(R.string.home_alert_openAnyway),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
}

/** 文案内 `**加粗**` 标记 → 粗体区间（iOS SwiftUI 对 LocalizedStringKey 的内联 markdown 同构）。 */
private fun markdownBold(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        if (start < 0) break
        val end = rest.indexOf("**", start + 2)
        if (end < 0) break
        append(rest.substring(0, start))
        val boldPart = rest.substring(start + 2, end)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(boldPart) }
        rest = rest.substring(end + 2)
    }
    append(rest)
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
            // 头区为顶部 overlay（固定 88dp 内容顶，对齐 iOS pageTopPadding；
            // 不叠加 statusBarsPadding——双计导致机型间头区高度漂移，审查移交项）。
            // 走查追加：行星页顶部再预留 40dp 空白，首卡与头区拉开呼吸位
            .padding(top = HomeMetrics.pageTopPadding + 40.dp)
            .padding(horizontal = HomeMetrics.pageHMargin),
    ) {
        // 行星身份卡：整卡可点（无涟漪），按压缩放由卡片自持
        val cardSource = remember(planet.id) { MutableInteractionSource() }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
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
                // iOS :42「isActive && hasActivatedPlanet」：角标只挂隧道绑定行星，
                // 其余行星（含刚添加未激活的）无角标。三态判定锚定活跃行星
                //（非选中页），滑页不扰动角标
                showBadge = planet.id == activeId && state.hasActivatedPlanet,
                badgeInUse = running,
                badgeConnecting = !running &&
                    state.planetNetworks[activeId].orEmpty().any { it.lastActivated },
                interactionSource = cardSource,
            )
        }

        // 发丝线 + 计数行（iOS networkHeader：网络 · N；启用网络按实时状态分桶追加）
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)),
        )
        // header 与分桶文案分开取串（stringResource 是组合期调用，不能进 buildString）；
        // 中缀「 · 」与 iOS 硬拼一致
        val headerText = stringResource(R.string.home_network_header, networks.size)
        // 计数行分桶（iOS b4fb534 networkHeader）：启用网络（lastActivated 且不在实时
        // 冲突表）按核心实时状态分「连接中 X | 已连接 Y」（已连接 = 核心报 OK），
        // 计数为 0 的桶不显示，全 0 不追加
        val enabled = networks.filter {
            it.lastActivated && state.networkConflicts[it.networkId] == null
        }
        val okCount = enabled.count { state.networkStatuses[it.networkId] == 1 }
        val connectingCount = enabled.size - okCount
        val connectingText = stringResource(R.string.home_network_connecting, connectingCount)
        val okText = stringResource(R.string.home_network_connected, okCount)
        val countText = if (isActivePage && running) {
            listOfNotNull(
                if (connectingCount > 0) connectingText else null,
                if (okCount > 0) okText else null,
            ).joinToString(" | ")
        } else {
            null
        }
        Text(
            text = if (countText != null) "$headerText · $countText" else headerText,
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
                    title = stringResource(R.string.home_network_emptyTitle),
                    hint = stringResource(R.string.home_network_emptyHint),
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
                            // 非当前隧道行星一律「未连接」（iOS NetworkCardView 第一级）
                            isActivePlanet = isActivePage,
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
                            nodeOnline = state.nodeOnline,
                            pathSatisfied = state.pathSatisfied,
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

/** 显示名：官方行星显示侧本地化（iOS PlanetProfile.displayName 同构，存储名不翻译）。 */
@Composable
private fun PlanetProfile.displayNameRes(): String =
    if (kind == PlanetKind.official) stringResource(R.string.planet_official_name) else name

/**
 * 头区（iOS headerZone :178-244）：半透明背景渐变盖过状态栏。行结构 = 左 40dp
 * logo + 右列（高锁 40dp 与 logo 等高）：标题行（标题 + 活跃行星 chip + 设置
 * 入口）在顶，圆点指示器沉底（dot 中心 ≈ logo 底上方 4dp）——位于标题正下方、
 * 标题左缘起点处，非屏幕居中（此前居中行样式为视觉偏差）。
 */
/** 热区行「零高度叠加」：本节点占 0 高度（不消耗父 Column 高度预算），
 *  子内容按 [dotCenterOffset]（dot 视觉中心相对本节点原点的 y）绝对放置，
 *  可向上/向下越界绘制与命中（父链无 clip）——iOS overlay/background
 *  热区语义的 Compose 等价物。 */
private fun Modifier.zeroHeightOverlayAt(dotCenterOffset: androidx.compose.ui.unit.Dp): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(
                constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity),
            )
            layout(placeable.width, 0) {
                // 本节点占 0 高、放置在标题行之后；dot 中心 = 本节点原点 + dotCenterOffset
                val top = dotCenterOffset.roundToPx() - placeable.height / 2
                placeable.placeRelative(0, top)
            }
        },
    )

/** chip 转场时长 280ms（iOS .transaction .easeOut(duration: 0.28)，HomeView.swift:227）。 */
private const val ChipAnimMs = 280

/** iOS SwiftUI .easeOut ≈ cubic-bezier(0, 0, 0.58, 1)。 */
private val ChipEaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

private val ChipAnimFloat = tween<Float>(ChipAnimMs, easing = ChipEaseOut)

/**
 * 活跃行星 chip 的出现转场（iOS .flyFromPlanetCard，HomeView.swift:692-706）：
 * 仅纵向位移——整体自下方 10dp 淡入上移、隐藏反向下移淡出，横向位置恒定。
 * 占位宽度按同一进度展开/收起且内容右对齐（占位变化只吃左侧 weight Spacer，
 * chip 左缘不随展开左移——左对齐会呈现「从右下飘入」的斜向位移）；chip 按
 * 自然宽度完整绘制不裁剪（expandHorizontally 的侧边揭幕是遮罩效果）。收尾
 * （progress 归 0）移除内容，避免零宽占位下的越界命中。
 */
@Composable
private fun FlyInChip(visible: Boolean, content: @Composable () -> Unit) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = ChipAnimFloat,
        label = "activePlanetChip",
    )
    // 内容自然宽度（内层 Box 上读，读外层动画占位宽会反馈回环）
    var naturalWidth by remember { mutableIntStateOf(0) }
    Box(
        Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                val width = (naturalWidth * progress).roundToInt()
                layout(width, placeable.height) {
                    // x 为负时越界向左绘制（Compose 默认不裁剪）
                    placeable.placeRelative(width - placeable.width, 0)
                }
            }
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 10.dp.toPx()
            },
    ) {
        if (visible || progress > 0.001f) {
            Box(Modifier.onGloballyPositioned { if (it.size.width > 0) naturalWidth = it.size.width }) {
                content()
            }
        }
    }
}

@Composable
private fun HeaderZone(
    state: HomeUiState,
    browsingActive: Boolean,
    onOpenActive: () -> Unit,
    onOpenPrefs: () -> Unit,
    pageCount: Int,
    currentPage: Int,
    onSelectPage: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // 原为背景色 8% 半透明渐变盖过状态栏（近似 iOS ultraThinMaterial），
            // 已按要求改为全透明（不再绘制）
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
    ) {
        // 行结构对齐 iOS：logo 左，右列高锁 40dp（与 logo 等高）给内部
        // Spacer/offset 确定参照系；标题行在顶、指示器沉底
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo(Modifier.size(40.dp))
            // iOS headerZone HStack spacing 5.5（HomeView.swift:187）
            Spacer(Modifier.size(5.5.dp))
            Column(
                Modifier
                    .weight(1f)
                    .height(40.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Planet Port",
                        style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    // 活跃行星 chip（iOS HomeView.swift:202-228）：出现/隐藏转场见
                    // [FlyInChip]。配色对齐 iOS :211/216/217：次级文字 +
                    // secondarySystemGroupedBackground 胶囊底 + 0.5pt quaternary 描边
                    // ——不随主题主色变化。
                    FlyInChip(visible = !browsingActive && state.tunnelRunning) {
                        val activeName = state.planets.firstOrNull { it.id == state.activePlanetId }
                            ?.displayNameRes()
                            ?: stringResource(R.string.planet_official_name)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .border(
                                    width = 0.5.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(50),
                                )
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
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                activeName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // iOS :218-226：gearshape 20pt medium、36×36 热区、tint 主色。
                    // 不用 IconButton（48dp 最小交互尺寸会撑爆右列 40dp 高度预算，
                    // 把沉底指示器挤出可视区）
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = stringResource(R.string.common_settings),
                            ) { onOpenPrefs() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.common_settings),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // 圆点指示器（iOS indicator :289-312）：leading 起点（与标题左对齐）；
                // iOS 布局沉底后 .offset(y: -2)（HomeView.swift:248）——dot 高 6pt，
                // 视觉中心 = 右列底上方 5pt，dotCenterOffset 即取 -5（居中偏差已修）。
                // 选中 20×6 主色胶囊 / 未选 6×6 灰。
                // iOS 热区 = background 44pt 方形（以点为中心、不参与布局可越界）；
                // Compose 等价 = [zeroHeightOverlayAt]：本节点占 0 高度（不消耗右列
                // 40dp 预算），dot 行以点中心为锚绝对放置，越界绘制与命中皆可。
                // 横向热区 = 间隙均分（相邻各让 3.5dp 相接，视觉间隙 7dp 无死角）
                Row(
                    modifier = Modifier
                        .zeroHeightOverlayAt(dotCenterOffset = (-5).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(pageCount) { idx ->
                        val selected = currentPage == idx
                        // 颜色在组合期取值，draw lambda 非 composable 上下文
                        val dotColor = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        }
                        // 无障碍标签：行星页 = 浏览 X，末位 = 添加行星（iOS dot(isSelected:label:)）
                        val planet = state.planets.getOrNull(idx)
                        val dotLabel = planet
                            ?.let { stringResource(R.string.home_a11y_browsePlanet, it.displayNameRes()) }
                            ?: stringResource(R.string.planet_add_title)
                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(CircleShape)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onSelectPage(idx) }
                                .padding(horizontal = 3.5.dp)
                                .semantics { contentDescription = dotLabel },
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
            }
        }
    }
}

/**
 * App logo：iOS 同款位图资产（planetportlogo.png，256×256，透明圆角已内嵌）。
 * 此前以 Canvas 均分轮辐近似复刻，节点布局/渐变与真图标不符（走查「格式不正确」）；
 * 现直接引用 iOS Assets 的原 PNG，尺寸 40dp + 9dp 圆角裁切与 iOS
 * Image("PlanetPortLogo").frame(40).clipShape(RoundedRectangle(9)) 一致。
 */
@Composable
private fun AppLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.planet_port_logo),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(HomeMetrics.radiusLogo)),
    )
}

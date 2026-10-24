package cn.imgozi.planetport.ui.moon

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.MoonEntry
import cn.imgozi.planetport.ui.components.Badge
import cn.imgozi.planetport.ui.components.EmptyStateCard
import cn.imgozi.planetport.ui.components.SectionCard
import cn.imgozi.planetport.ui.theme.HomeMetrics
import cn.imgozi.planetport.ui.theme.LocalPlanetStatusColors
import cn.imgozi.planetport.ui.theme.PlanetFonts
import kotlinx.coroutines.launch

/**
 * Moon 管理页（对齐 iOS MoonView）：
 * 行 = 32dp tint 实底 moon 徽标 + World ID 16 位 hex 等宽 + 次行 seed（手动入轨才有）
 * + 缓存徽标（已缓存 绿 / 未缓存 灰，文件导入附 doc 图标）。
 * 操作：滑动删除 / 长按菜单（删除入轨、复制地址、删除缓存文件——仅文件导入）；
 * 右上 + 打开添加弹层（文件导入 / 手动入轨）。删除入轨只删记录不 deorbit，
 * 隧道在跑时自动重启以应用变更（Snackbar 说明）。
 *
 * @param planetId 归属行星（导航传入，即当前浏览行星）
 * @param onBack   返回回调（AppRoot 手写页面栈接线；默认空便于独立预览）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoonView(
    planetId: String,
    onBack: () -> Unit = {},
) {
    val vm: MoonViewModel = viewModel(key = planetId, factory = MoonViewModel.factory(planetId))
    val moons by vm.moons.collectAsState()
    val loaded by vm.loaded.collectAsState()
    val planetName by vm.planetName.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    // Snackbar 回调非 Composable 上下文，文案经 context.getString 解析
    val context = LocalContext.current

    var showAdd by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 文件导入：系统文档选择器（对齐 iOS fileImporter [.data] → 任意数据文件）
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                when (val result = vm.importFromFile(uri)) {
                    AddMoonResult.Added -> Unit
                    is AddMoonResult.Failed -> notice = result.message
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.moon_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.moon_addTitle),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (!loaded) {
            // 首次读盘前不渲染，避免「空态 → 列表」闪变
            Box(Modifier.fillMaxSize().padding(padding))
        } else if (moons.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = HomeMetrics.pageHMargin),
            ) {
                // 空态（对齐 iOS：「"X"尚未配置卫星 / 右上角 + 添加」，不可点击）
                EmptyStateCard(
                    icon = Icons.Filled.Nightlight,
                    title = stringResource(R.string.moon_emptyTitle, planetName),
                    hint = stringResource(R.string.moon_emptyHint),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    horizontal = HomeMetrics.pageHMargin,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(HomeMetrics.cardSpacing),
            ) {
                items(moons, key = { it.entry.id }) { row ->
                    MoonRowCard(
                        row = row,
                        onDelete = {
                            scope.launch {
                                runCatching { vm.deleteOrbit(row.entry) }
                                    .onSuccess {
                                        snackbar.showSnackbar(context.getString(R.string.moon_orbitRemoved))
                                    }
                                    .onFailure {
                                        snackbar.showSnackbar(context.getString(R.string.common_operationFailed))
                                    }
                            }
                        },
                        onCopy = {
                            clipboard.setText(AnnotatedString(row.entry.id))
                        },
                        onDeleteCache = {
                            scope.launch {
                                runCatching { vm.deleteCacheFile(row.entry) }
                                    .onFailure {
                                        snackbar.showSnackbar(context.getString(R.string.common_operationFailed))
                                    }
                            }
                        },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddMoonSheet(
            onDismiss = { showAdd = false },
            onFileImport = {
                showAdd = false
                filePicker.launch(arrayOf("*/*"))
            },
            onManualOrbit = { worldId, seed ->
                scope.launch {
                    vm.addManual(worldId, seed)
                        .also { if (it is AddMoonResult.Failed) notice = it.message }
                }
                showAdd = false
            },
        )
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(stringResource(R.string.common_notice)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { notice = null }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}

/**
 * 单条 Moon 卡：滑动删除（红色 delete 底）+ 长按菜单。
 * [onDelete]/[onCopy]/[onDeleteCache] 均为一次性动作回调。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MoonRowCard(
    row: MoonRow,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onDeleteCache: () -> Unit,
) {
    val statusColors = LocalPlanetStatusColors.current
    var menuOpen by remember { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(HomeMetrics.radiusCard))
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = HomeMetrics.cardPadding + 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.moon_deleteOrbit),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
    ) {
        Box {
            SectionCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClickLabel = stringResource(R.string.moon_actions),
                        onClick = {},
                        onLongClick = { menuOpen = true },
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 图标 badge：与首页身份区同语言（tint 实底 + 白符号，圆角 10）
                    Box(
                        modifier = Modifier
                            .size(HomeMetrics.badgeIconSize)
                            .clip(RoundedCornerShape(HomeMetrics.radiusSmall))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Nightlight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = row.entry.id,
                            style = PlanetFonts.MonoBody,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!row.entry.fromFile) {
                                Text(
                                    text = "seed " + MoonEntry.formatWorldId(row.entry.seed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            // 缓存徽标：已缓存（就绪，绿）/ 未缓存（中性）；文件导入附 doc 图标
                            Badge(
                                text = stringResource(
                                    if (row.cached) R.string.moon_cached else R.string.moon_notCached,
                                ),
                                color = if (row.cached) statusColors.good else statusColors.neutral,
                                icon = if (row.entry.fromFile) Icons.Filled.Description else null,
                            )
                        }
                    }
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.moon_deleteOrbit),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.moon_copyAddress)) },
                    leadingIcon = {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onCopy()
                    },
                )
                if (row.entry.fromFile) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.moon_deleteCache)) },
                        leadingIcon = {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                        },
                        onClick = {
                            menuOpen = false
                            onDeleteCache()
                        },
                    )
                }
            }
        }
    }
}

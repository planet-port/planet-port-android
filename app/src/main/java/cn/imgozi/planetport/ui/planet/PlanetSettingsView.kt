package cn.imgozi.planetport.ui.planet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.PlanetImporter
import cn.imgozi.planetport.store.PlanetKind
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.ui.components.ConfirmBubble
import cn.imgozi.planetport.ui.components.CopyableRow
import cn.imgozi.planetport.ui.components.fileSizeText
import cn.imgozi.planetport.ui.form.FormRow
import cn.imgozi.planetport.ui.form.FormSection
import cn.imgozi.planetport.ui.qrtransfer.QRShareSheet
import cn.imgozi.planetport.ui.theme.PlanetFonts
import cn.imgozi.planetport.ui.theme.ThemeManager
import java.io.File
import java.text.DateFormat
import androidx.appcompat.app.AppCompatDelegate
import java.util.Date
import java.util.Locale

/**
 * 行星设置页（iOS PlanetSettingsView 1:1）：名称与图标（官方只读）、只读信息
 * （World ID/节点 ID/类型/文件大小/修订时间）、诊断三入口（Peers/Moon/数据面诊断）、
 * 删除（仅私有，激活中先停 VPN 再级联删除）。
 *
 * @param planetId 行星 ID（自 Page.PlanetSettings 取）
 * @param onBack 页面退栈回调（删除成功后也经此退出）
 * @param navToPeers/navToMoon/navToApplyTrace 诊断三入口的跳转回调（AppRoot 接线）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanetSettingsView(
    planetId: String,
    onBack: () -> Unit,
    navToPeers: () -> Unit,
    navToMoon: () -> Unit,
    navToApplyTrace: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as android.app.Application
    val vm: PlanetSettingsViewModel = viewModel(key = "planet-settings-$planetId") {
        PlanetSettingsViewModel(planetId, app)
    }
    val ui by vm.ui.collectAsState()
    val node by vm.nodeStatus.collectAsState()
    val planet = ui.planet

    var showIconPicker by remember { mutableStateOf(false) }
    var showDeleteBubble by remember { mutableStateOf(false) }
    // 删除行内容顶边（组合根坐标），作为确认气泡锚点
    var deleteAnchorTop by remember { mutableStateOf(0f) }
    var alertText by remember { mutableStateOf<String?>(null) }
    var showQRShare by remember { mutableStateOf(false) }
    var qrShareData by remember { mutableStateOf<ByteArray?>(null) }
    var showFileShare by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // planet 文件（iOS planetFileURL）：非 null 即代表文件存在（并发删除后回退 null）；
    // exists() 轻量 stat，按行星缓存一次避免每次重组都查盘，fileInfo 作 key：
    // 页面停留期间文件被删/重导入会重读出新的 fileInfo，此时重查 exists()
    val planetFile = remember(planet?.fileName, ui.fileInfo) {
        planet?.fileName?.let { File(File(context.filesDir, "planets"), it) }?.takeIf { it.exists() }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        // 官方行星存储名不翻译，显示侧映射本地化文案（iOS PlanetProfile.displayName）
                        Text(
                            planet?.let {
                                if (it.kind == PlanetKind.official) {
                                    stringResource(R.string.planet_official_name)
                                } else {
                                    it.name
                                }
                            } ?: stringResource(R.string.planet_title),
                        )
                    },
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
            if (planet == null) {
                Box(Modifier.fillMaxSize().padding(padding))
                return@Scaffold
            }
            val isOfficial = planet.kind == PlanetKind.official

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                FormSection(
                    footer = if (isOfficial) {
                        stringResource(R.string.planet_info_officialOnly)
                    } else {
                        null
                    },
                ) {
                    NameRow(
                        planet = planet,
                        isOfficial = isOfficial,
                        onSave = { vm.rename(it) { alertText = it } },
                    )
                    IconRow(
                        planet = planet,
                        isOfficial = isOfficial,
                        onClick = { showIconPicker = true },
                    )
                }

                FormSection(footer = stringResource(R.string.planet_info_footer)) {
                    FormRow("World ID", ui.worldId)
                    CopyableRow(
                        stringResource(R.string.planet_info_nodeId),
                        // 激活行星优先实时值，其余/离线回退身份文件派生值
                        //（对齐 iOS：planetNodeIds 由身份文件派生 + 运行时回填）
                        if (planetId == ui.activePlanetId && !node?.nodeId.isNullOrEmpty()) {
                            node!!.nodeId
                        } else {
                            ui.nodeId
                        },
                    )
                    FormRow(
                        stringResource(R.string.common_type),
                        stringResource(
                            if (isOfficial) R.string.planet_info_officialKind else R.string.planet_info_customKind,
                        ),
                        valueStyle = MaterialTheme.typography.bodyLarge,
                    )
                    ui.fileInfo?.let { info ->
                        FileSizeRow(info)
                        FormRow(
                            stringResource(R.string.planet_info_revision),
                            revisionText(info.timestamp),
                        )
                        if (!isOfficial && planetFile != null) {
                            ShareRow(
                                onFile = { showFileShare = true },
                                onQr = {
                                    // 读取 planet 文件并连同名称/图标/底色组成 ZTP 容器进入
                                    // 二维码分享；读取失败(并发删除)不弹页
                                    planetFile?.let { file ->
                                        runCatching { file.readBytes() }.getOrNull()?.let { bytes ->
                                            qrShareData = PlanetPayload.container(
                                                planet = bytes,
                                                name = planet.name,
                                                icon = planet.icon,
                                                iconColor = planet.iconColor,
                                            )
                                            showQRShare = true
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                FormSection(footer = stringResource(R.string.planet_diagnostics_footer)) {
                    NavRow(
                        icon = Icons.Filled.Group,
                        label = stringResource(R.string.planet_diagnostics_peers),
                        enabled = planetId == ui.activePlanetId,
                        onClick = navToPeers,
                    )
                    NavRow(
                        icon = Icons.Filled.Nightlight,
                        label = stringResource(R.string.planet_diagnostics_moons),
                        onClick = navToMoon,
                    )
                    NavRow(
                        icon = Icons.Filled.MonitorHeart,
                        label = stringResource(R.string.planet_diagnostics_trace),
                        onClick = navToApplyTrace,
                    )
                }

                if (!isOfficial) {
                    FormSection(
                        footer = stringResource(R.string.planet_delete_footer),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .onGloballyPositioned { deleteAnchorTop = it.positionInRoot().y }
                                .clickable { showDeleteBubble = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.planet_delete),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
        ConfirmBubble(
            visible = showDeleteBubble,
            anchorTopPx = deleteAnchorTop,
            title = stringResource(R.string.planet_delete_confirmTitle),
            message = stringResource(R.string.planet_delete_confirmMessage),
            confirmLabel = stringResource(R.string.planet_delete_confirmAction),
            onConfirm = {
                showDeleteBubble = false
                vm.delete(onBack)
            },
            onDismiss = { showDeleteBubble = false },
        )
    }

    if (showIconPicker && planet != null) {
        IconPickerSheet(
            animal = PlanetIcon.animalKey(planet),
            color = planet.iconColor,
            onSelect = { animal, color ->
                vm.updateIcon(animal, color) { alertText = it }
            },
            onDismiss = { showIconPicker = false },
        )
    }

    // 分享行「文件」选项：经 FileProvider 拉起系统分享面板（副作用移出组合，
    // 用 LaunchedEffect 保证单次触发）
    LaunchedEffect(showFileShare) {
        if (showFileShare) {
            planetFile?.let { sharePlanetFile(context, it) }
            showFileShare = false
        }
    }

    if (showQRShare && qrShareData != null) {
        QRShareSheet(
            title = stringResource(R.string.planet_share_qr_title),
            data = qrShareData!!,
            onDismiss = {
                showQRShare = false
                qrShareData = null
            },
        )
    }

    alertText?.let { message ->
        AlertDialog(
            onDismissRequest = { alertText = null },
            title = { Text(stringResource(R.string.common_notice)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { alertText = null }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}

/** 名称行：官方只读文本；私有右对齐输入框，失焦/提交即存（对齐 iOS FocusState 语义）。 */
@Composable
private fun NameRow(
    planet: PlanetProfile,
    isOfficial: Boolean,
    onSave: (String) -> Unit,
) {
    var name by remember(planet.id) { mutableStateOf(planet.name) }
    var wasFocused by remember { mutableStateOf(false) }

    fun saveIfChanged() {
        if (name.trim() != planet.name) onSave(name)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.common_name), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        if (isOfficial) {
            Text(
                stringResource(R.string.planet_official_name),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { saveIfChanged() }),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
                    .onFocusChanged { state ->
                        if (wasFocused && !state.isFocused) saveIfChanged()
                        wasFocused = state.isFocused
                    },
            )
        }
    }
}

/** 图标行：官方显示内置磁贴占位（不可点）；私有显示 24dp 磁贴（14dp 白动物）+ chevron（iOS :89-113）。 */
@Composable
private fun IconRow(
    planet: PlanetProfile,
    isOfficial: Boolean,
    onClick: () -> Unit,
) {
    val accentHex by ThemeManager.accent.collectAsState()
    val context = LocalContext.current
    val tint = remember(planet.id, planet.iconColor, accentHex) { PlanetIcon.tint(planet) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .then(if (isOfficial) Modifier else Modifier.clickable(onClick = onClick))
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.common_icon), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        // 官方行星与私有行星同图标体系（iOS 图标行无特判）：动物磁贴 + tint 底；
        // 官方不可改（disabled），故无 chevron
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(
                    PlanetIcon.drawableId(context, PlanetIcon.animalKey(planet)),
                ),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        if (!isOfficial) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
            )
        }
    }
}

/** 诊断入口行：图标 + 标签 + chevron（iOS Label + NavigationLink）。 */
@Composable
private fun NavRow(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            },
            modifier = Modifier.size(20.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            color = if (enabled) {
                Color.Unspecified
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 文件大小行。分享入口已独立成「分享」行（文件/二维码两个选项）。 */
@Composable
private fun FileSizeRow(info: PlanetImporter.PlanetFileInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.planet_info_fileSize), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        Text(
            fileSizeText(info.fileSize),
            style = PlanetFonts.MonoBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 分享行（iOS e4139a9 后）：「分享」标签 + 文件/二维码双选项（iOS shareLabel，
 *  图标 12dp、间距 4dp、颜色跟随正文）。 */
@Composable
private fun ShareRow(onFile: () -> Unit, onQr: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.common_share), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        ShareOption(Icons.Outlined.Description, stringResource(R.string.planet_share_file), onFile)
        Box(
            Modifier
                .padding(horizontal = 10.dp)
                .width(1.dp)
                .height(14.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        ShareOption(Icons.Outlined.QrCode2, stringResource(R.string.planet_share_qr), onQr)
    }
}

/** 分享选项：图标 + 标签，点击整块可点，颜色跟随正文标签。 */
@Composable
private fun ShareOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 经 FileProvider 授权外发 planet 文件（ACTION_SEND 系统分享面板）。 */
private fun sharePlanetFile(context: android.content.Context, file: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(send, context.getString(R.string.common_share)),
    )
}

/** 修订时间（ZeroTier World 时间戳为毫秒；0 = 未知）。 */
private fun revisionText(ts: ULong): String {
    if (ts == 0uL) return "—"
    // 数字短格式（iOS 81cdb07 date .numeric）：简中 2026/10/24 13:31；
    // 格式化器显式取应用 locale（空 = 跟随系统），与页面文案语言一致
    val appLocales = AppCompatDelegate.getApplicationLocales()
    val locale = if (appLocales.isEmpty) Locale.getDefault() else appLocales[0]
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale)
        .format(Date(ts.toLong()))
}

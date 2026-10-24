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
import cn.imgozi.planetport.store.PlanetKind
import cn.imgozi.planetport.store.PlanetProfile
import cn.imgozi.planetport.ui.components.OfficialPlanetIcon
import cn.imgozi.planetport.ui.form.FormRow
import cn.imgozi.planetport.ui.form.FormSection
import cn.imgozi.planetport.ui.theme.ThemeManager
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
    var showDelete by remember { mutableStateOf(false) }
    var alertText by remember { mutableStateOf<String?>(null) }

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
                FormRow(
                    stringResource(R.string.planet_info_nodeId),
                    // 激活行星优先实时值，其余/离线回退档案缓存（对齐 iOS nodeIDText）
                    if (planetId == ui.activePlanetId && !node?.nodeId.isNullOrEmpty()) {
                        node!!.nodeId
                    } else {
                        planet.lastNodeId ?: "—"
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
                    FormRow(
                        stringResource(R.string.planet_info_fileSize),
                        fileSizeText(info.fileSize),
                    )
                    FormRow(
                        stringResource(R.string.planet_info_revision),
                        revisionText(info.timestamp),
                    )
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
                            .clickable { showDelete = true },
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

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.planet_delete_confirmTitle)) },
            text = { Text(stringResource(R.string.common_irreversible)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.delete(onBack)
                }) {
                    Text(
                        stringResource(R.string.planet_delete_confirmAction),
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
        if (isOfficial) {
            // 官方磁贴（与首页共用 ui.components.OfficialPlanetIcon，石绿线稿行星）
            OfficialPlanetIcon(modifier = Modifier.size(24.dp))
        } else {
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

/** 文件大小（十进制 KB=1000，国际单位不随语言变化——对齐 iOS locFileSize）。 */
private fun fileSizeText(bytes: Int): String {
    val units = listOf(
        1_000_000_000_000L to "TB",
        1_000_000_000L to "GB",
        1_000_000L to "MB",
        1_000L to "KB",
    )
    for ((factor, unit) in units) {
        if (bytes >= factor) {
            return String.format(Locale.US, "%.1f %s", bytes.toDouble() / factor, unit)
        }
    }
    return "$bytes B"
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

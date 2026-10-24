package cn.imgozi.planetport.ui.prefs

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.AppPrefs
import cn.imgozi.planetport.store.BackgroundImageStore
import cn.imgozi.planetport.ui.MainActivity
import cn.imgozi.planetport.ui.components.SheetScaffold
import cn.imgozi.planetport.ui.form.FormRow
import cn.imgozi.planetport.ui.form.FormSection
import cn.imgozi.planetport.ui.form.FormToggleRow
import cn.imgozi.planetport.ui.planet.PlanetIcon
import cn.imgozi.planetport.ui.theme.ThemeManager

/**
 * 全局设置页（iOS PrefsView.swift 可适用项移植）：
 * 网络（禁用 IPv6 热重启 / 同时连接上限）、按需连接（app 启动自动恢复隧道；
 * iOS NEOnDemandRuleConnect 的 Android 落地）、语言（本任务只建 UI + 持久化，切换接线归 T7）、
 * 外观（主题色入口首位——弹窗收编预设色板与 HSL 色盘（iOS fe0db0a）；背景图入口壳——
 * 选取器归 T7；视差无图禁用）、关于（Core 版本 / App 版本 +
 * MPL-2.0 归属声明 footer）。
 * iOS 的「允许移动数据」无 Android 等价，不移植；捐赠 Section 以 if(false) 隐藏对齐。
 *
 * @param activity 授权请求宿主（on-demand 自动拉起的 VPN 授权弹窗，HomeView 同范式）
 * @param onBack 页面退栈回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrefsView(activity: MainActivity, onBack: () -> Unit) {
    val app = activity.application
    val vm: PrefsViewModel = viewModel(key = "prefs") { PrefsViewModel(app) }
    vm.attachPermissionRequester(activity::requestVpnPermission)
    val prefs by vm.prefs.collectAsState()
    val node by vm.nodeStatus.collectAsState()
    val coreVersion by vm.coreVersion.collectAsState()

    // 背景图在场判定（走查修复）：以 store 内存位图为单一事实源（对齐 iOS
    // app.backgroundImage != nil 的「图本体在场」语义），AppPrefs.backgroundImage
    // 布尔仅作进入首帧的回显兜底（store 尚未 refresh 时防视差行闪灰）。
    // 旧版只落了图没落布尔的设备（布尔恒 false）由此自愈：清除入口恢复显示、
    // 视差开关恢复可用——此前两处都被假 false 锁死。
    val bgState by BackgroundImageStore.state.collectAsState()
    val hasBackgroundImage = bgState.bitmap != null || prefs.backgroundImage

    // 背景图选取弹层（T7 实装）
    var showBgPicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showLanguagePicker by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            FormSection(
                header = stringResource(R.string.prefs_network_sectionHeader),
                footer = stringResource(R.string.prefs_network_footer),
            ) {
                FormToggleRow(
                    title = stringResource(R.string.prefs_network_disableIPv6),
                    checked = prefs.disableIpv6,
                    onCheckedChange = vm::setDisableIpv6,
                )
                StepperRow(
                    title = stringResource(R.string.prefs_network_multiConnectLimit, prefs.maxNetworks),
                    value = prefs.maxNetworks,
                    range = 2..6,
                    onChange = vm::setMaxNetworks,
                )
            }

            // 按需连接（iOS 独立 Section：网络区与语言区之间，PrefsView.swift:33-42）
            FormSection(footer = stringResource(R.string.prefs_ondemand_footer)) {
                FormToggleRow(
                    title = stringResource(R.string.prefs_ondemand),
                    checked = prefs.connectOnDemand,
                    onCheckedChange = vm::setConnectOnDemand,
                )
            }

            // 语言即时切换：AppCompatDelegate.setApplicationLocales（autoStoreLocales
            // 持久化），AppPrefs.language 仅作展示态同步（审查 M4：占位 footer 已删）。
            // 形态对齐 iOS Form Picker（PrefsView.swift:44-54）：节内单行「语言 +
            // 当前值 + chevron」，点开选择层选完即回；选项以自身名称显示，
            // 「跟随系统」随界面语言本地化
            FormSection {
                PickerRow(
                    title = stringResource(R.string.prefs_language_sectionHeader),
                    value = languageDisplayName(activity, prefs.language),
                    onClick = { showLanguagePicker = true },
                )
            }

            FormSection(
                header = stringResource(R.string.prefs_appearance_sectionHeader),
                footer = stringResource(R.string.prefs_appearance_footer),
            ) {
                // 主题色入口（组内首位，iOS fe0db0a）：预设色板与自定义取色收进弹窗，
                // 外观区只留一行——右侧 24dp 主色圆点 + chevron
                val accentHex by ThemeManager.accent.collectAsState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showThemePicker = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.prefs_theme_sectionHeader),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(Color(accentHex.toInt())),
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 4.dp).size(20.dp),
                    )
                }
                // 背景图行：点击开选取弹层；已设置显示 44dp 缩略图（store 内存位图，
                // 与首页视差层同源，换图/清除后即时刷新）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showBgPicker = true }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.prefs_appearance_background),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    val thumb = bgState.bitmap
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Text(
                            stringResource(R.string.common_notSet),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 4.dp).size(20.dp),
                    )
                }
                FormToggleRow(
                    title = stringResource(R.string.prefs_appearance_parallax),
                    checked = prefs.parallax,
                    onCheckedChange = vm::setParallax,
                    // 无背景图时禁用且标签置灰（对齐 iOS disabled + Color.secondary）；
                    // 门控走 hasBackgroundImage（图本体在场），不再依赖可失真的布尔
                    enabled = hasBackgroundImage,
                    titleColor = if (hasBackgroundImage) {
                        Color.Unspecified
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // 主题色独立分区已收进弹窗（iOS fe0db0a）：入口在外观组首位

            // MPL-2.0 归属声明（P4 终审归档项）：静态 footer 行，样式对齐页内既有
            // section footer；MPL 2.0 要求的源码可得性由 externals/ 源码随仓满足
            FormSection(
                header = stringResource(R.string.prefs_about_sectionHeader),
                footer = stringResource(R.string.about_opensource),
            ) {
                SelectionContainer {
                    FormRow(
                        title = "ZeroTier Core",
                        value = coreVersion.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.common_unknown),
                    )
                }
                SelectionContainer {
                    FormRow(
                        title = stringResource(R.string.prefs_about_version),
                        value = vm.appVersion,
                    )
                }
            }

            // 支持区暂隐藏（内容待补充），代码保留——对齐 iOS PrefsView if (false)
            if (false) {
                FormSection(header = stringResource(R.string.prefs_support_sectionHeader)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.prefs_support_donate),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showBgPicker) {
        BackgroundImagePickerSheet(
            vm = vm,
            hasImage = hasBackgroundImage,
            onApplied = { showBgPicker = false },
            onDismiss = { showBgPicker = false },
        )
    }

    if (showThemePicker) {
        ThemeColorPickerSheet(
            vm = vm,
            prefs = prefs,
            onDismiss = { showThemePicker = false },
        )
    }

    // 语言选择弹层（iOS Picker 推入列表的等价形态）：单选即应用并关闭
    if (showLanguagePicker) {
        LanguagePickerSheet(
            selected = prefs.language,
            onSelect = { code ->
                vm.setLanguage(code)
                AppCompatDelegate.setApplicationLocales(localeListFor(code))
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false },
        )
    }
}

/** 存储码 → 语言选项显示名（null = 跟随系统，随界面语言本地化）。 */
private fun languageDisplayName(context: android.content.Context, code: String?): String =
    if (code == null) {
        context.getString(R.string.prefs_language_system)
    } else {
        // 各选项以自身名称显示（对齐 iOS AppLanguage.nativeName），不本地化
        when (code) {
            "zh" -> "简体中文"
            "zh-rTW" -> "繁體中文"
            "en" -> "English"
            else -> context.getString(R.string.prefs_language_system)
        }
    }

/**
 * 语言选择弹层：标题 + 单选列表，点选即应用并关闭（iOS 推入列表选中即弹回）。
 * 选项集与 iOS AppLanguage.allCases 一致：跟随系统/简体中文/繁體中文/English。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerSheet(
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 统一 large detent（屏高-16dp）：跳过半展开，直接全高
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetScaffold {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.prefs_language_sectionHeader),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                LanguageOptions(selected = selected, onSelect = onSelect)
            }
        }
    }
}

/** Form Picker 行：title 左 + 当前值（次要色）右 + chevron（iOS Picker 行形态）。 */
@Composable
private fun PickerRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(start = 4.dp).size(20.dp),
        )
    }
}

/** iOS Stepper 等价：标签 + 减/加按钮（范围越界禁用）。 */
@Composable
private fun StepperRow(
    title: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) {
            Icon(
                Icons.Filled.Remove,
                contentDescription = stringResource(R.string.prefs_stepperDecrease),
            )
        }
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(R.string.prefs_stepperIncrease),
            )
        }
    }
}

/** 语言单选组：各选项以自身名称显示（对齐 iOS AppLanguage.nativeName），null = 跟随系统。 */
@Composable
private fun LanguageOptions(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val options: List<Pair<String?, String>> = listOf(
        null to stringResource(R.string.prefs_language_system),
        "zh" to "简体中文",
        "zh-rTW" to "繁體中文",
        "en" to "English",
    )
    Column {
        options.forEach { (code, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(code) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == code, onClick = { onSelect(code) })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * 8 预设色板：单行 8 圆点（weight 均分 + 1:1 比例，约 32dp），选中项白色描环。
 * [selected] = (是否预设生效, 当前预设 ARGB)；自定义色生效时无圆点描环（对齐 iOS mainHex 精确比对）。
 */
@Composable
private fun PresetPalette(
    selected: Pair<Boolean, Long>,
    onSelect: (Long) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemeManager.presets.forEach { hex ->
            val isSelected = selected.first && selected.second == hex
            // 审查 M3：色点无文字，组合期解析「主色 %@」作为无障碍标签
            val a11yLabel = presetNames[hex]?.let { nameRes ->
                stringResource(R.string.prefs_theme_a11yPreset, stringResource(nameRes))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(colorOf(hex))
                    .semantics { if (a11yLabel != null) contentDescription = a11yLabel }
                    .clickable(onClickLabel = a11yLabel) { onSelect(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .padding(2.dp)
                            .border(2.dp, Color.White, CircleShape),
                    )
                }
            }
        }
    }
}

/** ARGB Long → Compose Color。 */
private fun colorOf(argb: Long): Color = Color(argb.toInt())

/**
 * 预设主色 → 名称资源（与 ThemeManager.presets 同序；审查 M3：色板圆点
 * 需无障碍标签「主色 %@」，纯色块无文字）。
 */
private val presetNames: Map<Long, Int> = mapOf(
    0xFFD44037 to R.string.theme_preset_defaultRed, // 默认红
    0xFFFFB61E to R.string.theme_preset_gamboge, // 藤黄
    0xFF789262 to R.string.theme_preset_bamboo, // 竹青
    0xFF57C3C2 to R.string.theme_preset_malachite, // 石绿
    0xFF4C8DAE to R.string.theme_preset_ultramarine, // 群青
    0xFF003472 to R.string.theme_preset_indigo, // 花青
    0xFF4A4266 to R.string.theme_preset_dark, // 黛
    0xFF9D2933 to R.string.theme_preset_carmine, // 胭脂
)

/**
 * 主题色选择弹窗（iOS ThemeColorPickerSheet 1:1）：示例色块（随机小动物叠加）
 * + 预置色板（同组无标题）→ HSL 色盘 → 状态色派生开关。单一数据源
 * ThemeManager.accent 驱动，预设与色盘双向同步（预设点击落盘 custom=null，
 * 色盘拖动中只写内存、抬手落盘——写路径与旧三滑杆一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeColorPickerSheet(
    vm: PrefsViewModel,
    prefs: AppPrefs,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 叠加在示例色块上的随机小动物（行星图标同套资源），每次打开弹窗随机一只
    val animalKey = remember { PlanetIcon.catalog.random() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 统一 large detent（屏高-16dp）：跳过半展开，直接全高
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetScaffold {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // 标题行：prefs_theme_sectionHeader + 右侧「完成」（对齐 iOS theme sheet
                // 的 toolbar Done——背景图 sheet 同范式，此前主题色 sheet 缺这一出口）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.prefs_theme_sectionHeader),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
                }

                // 示例色块 + 预置色板（iOS 同组无标题 Section）
                FormSection {
                    val accentHex by ThemeManager.accent.collectAsState()
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 4.dp)
                            .size(120.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(accentHex.toInt())),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(PlanetIcon.drawableId(context, animalKey)),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                    PresetPalette(
                        selected = (prefs.themeColorCustom == null) to
                            (prefs.themeColor ?: ThemeManager.presets.first()),
                        onSelect = vm::setThemePreset,
                    )
                }

                FormSection(header = stringResource(R.string.prefs_theme_wheel)) {
                    HslColorPicker(
                        onPreview = vm::previewThemeCustom,
                        onCommit = vm::setThemeCustom,
                    )
                }

                FormSection(footer = stringResource(R.string.prefs_theme_footer)) {
                    FormToggleRow(
                        title = stringResource(R.string.prefs_theme_deriveStatus),
                        checked = prefs.successDerived,
                        onCheckedChange = vm::setSuccessDerived,
                    )
                }
            }
        }
    }
}

/**
 * 自绘 HSL 取色器（iOS HSLColorPicker 1:1）：饱和度（横向 白→纯色）× 亮度
 * （纵向 白→透明→黑）平面 + 色相彩虹条，三值一个控件。不用系统 ColorPicker——
 * Android 侧同样有二次弹层与不可隐藏最近颜色的问题。读写直接走
 * ThemeManager.accent 单一数据源，与预置色板自动互相同步。
 */
@Composable
private fun HslColorPicker(
    onPreview: (Long) -> Unit,
    onCommit: (Long) -> Unit,
) {
    val accentHex by ThemeManager.accent.collectAsState()
    val hsl = ThemeManager.hsl(accentHex)

    // 增量合成：未给的维度实时读 accent（单一数据源）——拖平面只改 s/l（hue 取
    // 实时），拖色相条只改 h（s/l 取实时）。若用组合帧捕获的 hsl，跨帧拖动的
    // 后续帧会拿陈旧值把另一维度打回原点。
    fun apply(h: Double? = null, s: Double? = null, l: Double? = null) {
        val cur = ThemeManager.hsl(ThemeManager.accent.value and 0xFFFFFFFFL)
        val argb = ThemeManager.hslToColor(
            ThemeManager.Hsl(h ?: cur.h, s ?: cur.s, l ?: cur.l),
        ).toArgb().toLong() and 0xFFFFFFFFL
        onPreview(argb)
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 抬手落盘读 accent.value 实时值——最后一帧取色后组合帧 accentHex 可能未及刷新
        SatLightField(
            hue = hsl.h,
            saturation = hsl.s,
            lightness = hsl.l,
            onChanged = { s, l -> apply(s = s, l = l) },
            onFinished = { onCommit(ThemeManager.accent.value and 0xFFFFFFFFL) },
        )
        HueBar(
            hue = hsl.h,
            onChanged = { h -> apply(h = h) },
            onFinished = { onCommit(ThemeManager.accent.value and 0xFFFFFFFFL) },
        )
    }
}

/** 饱和度×亮度平面：横向 白→纯色（s: 0→1），纵向 白→透明→黑（l: 1→0.5→0）。 */
@Composable
private fun SatLightField(
    hue: Double,
    saturation: Double,
    lightness: Double,
    onChanged: (Double, Double) -> Unit,
    onFinished: () -> Unit,
) {
    val hueColor = ThemeManager.hslToColor(ThemeManager.Hsl(hue, s = 1.0, l = 0.5))
    // 手势回调走 rememberUpdatedState + pointerInput(Unit)：若以 hue 作 key，
    // 拖动中 hsl 往返舍入的微漂会让 block 重启、进行中的 drag 被静默取消
    // （onDragEnd 不回调 → 不落盘，剩余 move 漏给父级——sheet 被拽走）
    val currentOnChanged by rememberUpdatedState(onChanged)
    val currentOnFinished by rememberUpdatedState(onFinished)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
            // 点按即取色并落盘（iOS DragGesture(minimumDistance: 0) 的单击路径）
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    currentOnChanged(
                        (pos.x / size.width.toDouble()).coerceIn(0.0, 1.0),
                        1.0 - (pos.y / size.height.toDouble()).coerceIn(0.0, 1.0),
                    )
                    currentOnFinished()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { currentOnFinished() },
                    onDragCancel = { currentOnFinished() },
                ) { change, _ ->
                    change.consume()
                    currentOnChanged(
                        (change.position.x / size.width.toDouble()).coerceIn(0.0, 1.0),
                        1.0 - (change.position.y / size.height.toDouble()).coerceIn(0.0, 1.0),
                    )
                }
            },
    ) {
        // 亮度层单独叠放：白→透明→黑与底层的水平渐变叠加
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(listOf(Color.White, Color.Transparent, Color.Black)),
                ),
        )
        CursorCircle(
            xFraction = saturation.toFloat(),
            yFraction = (1.0 - lightness).toFloat(),
        )
    }
}

/** 色相条：彩虹轨道（高 10dp 胶囊）+ 可拖圆钮。 */
@Composable
private fun HueBar(
    hue: Double,
    onChanged: (Double) -> Unit,
    onFinished: () -> Unit,
) {
    // 同 SatLightField：Unit key + 最新回调，避免闭包捕获首帧 lambda 致 s/l 陈旧
    val currentOnChanged by rememberUpdatedState(onChanged)
    val currentOnFinished by rememberUpdatedState(onFinished)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    currentOnChanged((pos.x / size.width.toDouble()).coerceIn(0.0, 1.0) * 360.0)
                    currentOnFinished()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { currentOnFinished() },
                    onDragCancel = { currentOnFinished() },
                ) { change, _ ->
                    change.consume()
                    currentOnChanged(
                        (change.position.x / size.width.toDouble()).coerceIn(0.0, 1.0) * 360.0,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        // 七段彩虹：段序号 ×60° 折算度数（Hsl.h 语义是 0..<360，
                        // 传比例会恒落 h<60° 红区）
                        (0..6).map { ThemeManager.hslToColor(ThemeManager.Hsl(it * 60.0, 1.0, 0.5)) },
                    ),
                ),
        )
        CursorCircle(xFraction = (hue / 360.0).toFloat(), yFraction = 0.5f)
    }
}

/** 取色板/色相条上的游标：22dp 白心 + 黑 25% 描边圆，中心锚定比例坐标。 */
@Composable
private fun BoxScope.CursorCircle(xFraction: Float, yFraction: Float) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        Box(
            Modifier
                .offset(
                    x = maxWidth * xFraction - 11.dp,
                    y = maxHeight * yFraction - 11.dp,
                )
                .size(22.dp)
                .shadow(1.dp, CircleShape)
                .background(Color.White, CircleShape)
                .border(1.5.dp, Color.Black.copy(alpha = 0.25f), CircleShape),
        )
    }
}

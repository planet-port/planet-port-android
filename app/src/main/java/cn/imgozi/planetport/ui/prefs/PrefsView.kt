package cn.imgozi.planetport.ui.prefs

import android.app.Application
import android.graphics.Color as AndroidColor
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.BackgroundImageStore
import cn.imgozi.planetport.ui.MainActivity
import cn.imgozi.planetport.ui.form.FormRow
import cn.imgozi.planetport.ui.form.FormSection
import cn.imgozi.planetport.ui.form.FormToggleRow
import cn.imgozi.planetport.ui.theme.PlanetFonts
import cn.imgozi.planetport.ui.theme.ThemeManager

/**
 * 全局设置页（iOS PrefsView.swift 可适用项移植）：
 * 网络（禁用 IPv6 热重启 / 同时连接上限）、按需连接（app 启动自动恢复隧道；
 * iOS NEOnDemandRuleConnect 的 Android 落地）、语言（本任务只建 UI + 持久化，切换接线归 T7）、
 * 外观（背景图入口壳——选取器归 T7；视差无图禁用）、主题色（8 预设色板 + 自定义拾色 +
 * 派生开关，双写 ThemeManager 与 AppPrefs）、关于（Core 版本 / App 版本 +
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

    // 背景图选取弹层（T7 实装）
    var showBgPicker by remember { mutableStateOf(false) }

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
            // 持久化），AppPrefs.language 仅作展示态同步（审查 M4：占位 footer 已删）
            FormSection(header = stringResource(R.string.prefs_language_sectionHeader)) {
                LanguageOptions(
                    selected = prefs.language,
                    onSelect = { code ->
                        vm.setLanguage(code)
                        AppCompatDelegate.setApplicationLocales(localeListFor(code))
                    },
                )
            }

            FormSection(
                header = stringResource(R.string.prefs_appearance_sectionHeader),
                footer = stringResource(R.string.prefs_appearance_footer),
            ) {
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
                    val bgBitmap by BackgroundImageStore.state.collectAsState()
                    val thumb = bgBitmap.bitmap
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
                    // 无背景图时禁用且标签置灰（对齐 iOS disabled + Color.secondary）
                    enabled = prefs.backgroundImage,
                    titleColor = if (prefs.backgroundImage) {
                        Color.Unspecified
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            FormSection(
                header = stringResource(R.string.prefs_theme_sectionHeader),
                footer = stringResource(R.string.prefs_theme_footer),
            ) {
                PresetPalette(
                    selected = (prefs.themeColorCustom == null) to
                        (prefs.themeColor ?: ThemeManager.presets.first()),
                    onSelect = vm::setThemePreset,
                )
                Spacer(Modifier.height(6.dp))
                ThemeColorPickerRow(
                    initial = prefs.themeColorCustom ?: prefs.themeColor ?: ThemeManager.presets.first(),
                    onPreview = vm::previewThemeCustom,
                    onCommit = vm::setThemeCustom,
                )
                FormToggleRow(
                    title = stringResource(R.string.prefs_theme_deriveStatus),
                    checked = prefs.successDerived,
                    onCheckedChange = vm::setSuccessDerived,
                )
            }

            // MPL-2.0 归属声明（P4 终审归档项）：静态 footer 行，样式对齐页内既有
            // section footer；MPL 2.0 要求的源码可得性由 externals/ 源码随仓满足
            FormSection(
                header = stringResource(R.string.prefs_about_sectionHeader),
                footer = stringResource(R.string.about_opensource),
            ) {
                SelectionContainer {
                    FormRow(
                        title = "ZeroTier Core",
                        value = node?.coreVersion?.takeIf { it.isNotBlank() }
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
            hasImage = prefs.backgroundImage,
            onApplied = { showBgPicker = false },
            onDismiss = { showBgPicker = false },
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
 * 自定义主色拾取（iOS ColorPicker 等价，色相/饱和度/亮度三滑杆 + 预览圆点）：
 * 拖动中仅更新 ThemeManager 内存态（即时生效不落盘），拖动结束一次性持久化——
 * 避免滑杆高频写盘。
 */
@Composable
private fun ThemeColorPickerRow(
    initial: Long,
    onPreview: (Long) -> Unit,
    onCommit: (Long) -> Unit,
) {
    var hsv by remember(initial) {
        mutableStateOf(FloatArray(3).also { AndroidColor.colorToHSV(initial.toInt(), it) })
    }

    fun argb(): Long = AndroidColor.HSVToColor(hsv).toLong() and 0xFFFFFFFFL

    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.prefs_theme_custom),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colorOf(argb())),
            )
        }
        HslSlider(
            label = stringResource(R.string.prefs_theme_hue),
            value = hsv[0],
            range = 0f..360f,
            display = { it.toInt().toString() },
            onValueChange = {
                hsv = floatArrayOf(it, hsv[1], hsv[2])
                onPreview(argb())
            },
            onFinished = { onCommit(argb()) },
        )
        HslSlider(
            label = stringResource(R.string.prefs_theme_saturation),
            value = hsv[1],
            range = 0f..1f,
            display = { "%d%%".format((it * 100).toInt()) },
            onValueChange = {
                hsv = floatArrayOf(hsv[0], it, hsv[2])
                onPreview(argb())
            },
            onFinished = { onCommit(argb()) },
        )
        HslSlider(
            label = stringResource(R.string.prefs_theme_brightness),
            value = hsv[2],
            range = 0f..1f,
            display = { "%d%%".format((it * 100).toInt()) },
            onValueChange = {
                hsv = floatArrayOf(hsv[0], hsv[1], it)
                onPreview(argb())
            },
            onFinished = { onCommit(argb()) },
        )
    }
}

@Composable
private fun HslSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 56.dp, height = 20.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            display(value),
            style = PlanetFonts.MonoCaption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

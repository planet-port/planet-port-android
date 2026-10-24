package cn.imgozi.planetport.ui.prefs

import android.app.Application
import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.imgozi.planetport.ui.form.FormRow
import cn.imgozi.planetport.ui.form.FormSection
import cn.imgozi.planetport.ui.form.FormToggleRow
import cn.imgozi.planetport.ui.theme.PlanetFonts
import cn.imgozi.planetport.ui.theme.ThemeManager

/**
 * 全局设置页（iOS PrefsView.swift 可适用项移植）：
 * 网络（禁用 IPv6 热重启 / 同时连接上限）、语言（本任务只建 UI + 持久化，切换接线归 T7）、
 * 外观（背景图入口壳——选取器归 T7；视差无图禁用）、主题色（8 预设色板 + 自定义拾色 +
 * 派生开关，双写 ThemeManager 与 AppPrefs）、关于（Core 版本 / App 版本）。
 * iOS 的「允许移动数据」「按需连接」无 Android 等价，不移植；捐赠 Section 以 if(false) 隐藏对齐。
 *
 * @param onBack 页面退栈回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrefsView(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: PrefsViewModel = viewModel(key = "prefs") { PrefsViewModel(app) }
    val prefs by vm.prefs.collectAsState()
    val node by vm.nodeStatus.collectAsState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("设置") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            FormSection(
                header = "网络",
                footer = "同时连接多个网络会增加耗电；超出上限时会提示确认，仍可强制开启。",
            ) {
                FormToggleRow(
                    title = "禁用 IPv6",
                    checked = prefs.disableIpv6,
                    onCheckedChange = vm::setDisableIpv6,
                )
                StepperRow(
                    title = "同时连接上限：${prefs.maxNetworks}",
                    value = prefs.maxNetworks,
                    range = 2..6,
                    onChange = vm::setMaxNetworks,
                )
            }

            FormSection(
                header = "语言",
                // per-app locale 切换接线归 T7（AppCompatDelegate.setApplicationLocales），
                // 本任务先落 UI 与持久化，占位说明生效时机
                footer = "语言切换需重启应用后生效。",
            ) {
                LanguageOptions(
                    selected = prefs.language,
                    onSelect = vm::setLanguage,
                )
            }

            FormSection(
                header = "外观",
                footer = "背景图可从相册选择或通过 URL 下载；视差动画仅在设置了背景图时生效。",
            ) {
                // 背景图入口壳：选取器（BackgroundImagePickerSheet）实现在 T7，
                // 本任务只渲染「未设置」态；AppPrefs.backgroundImage 持久化字段由 T7 写
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 占位点击：T7 在此打开背景图选取弹窗
                        .clickable(enabled = false) {}
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("背景图", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    if (prefs.backgroundImage) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        )
                    } else {
                        Text(
                            "未设置",
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
                    title = "视差动画",
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
                header = "主题色",
                footer = "主色决定按钮、指示器与行星装饰色，深浅模式自动适配；状态色默认保持绿/橙/红语义，" +
                    "打开开关后成功色随主色三角派生。",
            ) {
                PresetPalette(
                    selected = (prefs.themeColorCustom == null) to
                        (prefs.themeColor ?: ThemeManager.presets.first()),
                    onSelect = vm::setThemePreset,
                )
                Spacer(Modifier.height(6.dp))
                ThemeColorPickerRow(
                    initial = prefs.themeColorCustom ?: prefs.themeColor ?: ThemeManager.presets.first(),
                    onPreview = vm::setThemeCustom,
                    onCommit = vm::setThemeCustom,
                )
                FormToggleRow(
                    title = "状态色跟随主色派生",
                    checked = prefs.successDerived,
                    onCheckedChange = vm::setSuccessDerived,
                )
            }

            FormSection(header = "关于") {
                SelectionContainer {
                    FormRow(
                        title = "ZeroTier Core",
                        value = node?.coreVersion?.takeIf { it.isNotBlank() } ?: "未知",
                    )
                }
                SelectionContainer {
                    FormRow(title = "版本", value = vm.appVersion)
                }
            }

            // 支持区暂隐藏（内容待补充），代码保留——对齐 iOS PrefsView if (false)
            if (false) {
                FormSection(header = "支持") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("捐赠", style = MaterialTheme.typography.bodyLarge)
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
            Icon(Icons.Filled.Remove, contentDescription = "减小")
        }
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
            Icon(Icons.Filled.Add, contentDescription = "增大")
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
        null to "跟随系统",
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(colorOf(hex))
                    .clickable { onSelect(hex) },
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
            Text("自定义主色", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(colorOf(argb())),
            )
        }
        HslSlider(
            label = "色相",
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
            label = "饱和度",
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
            label = "亮度",
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

package cn.imgozi.planetport.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.PlanetImporter
import cn.imgozi.planetport.ui.planet.IconPickerSheet
import cn.imgozi.planetport.ui.planet.PlanetIcon
import cn.imgozi.planetport.ui.theme.HomeMetrics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 添加页占位 tag（iOS HomeView.addTag，末位分页非行星）。 */
internal const val ADD_PAGE_TAG = "__add__"

/**
 * 末位「添加」分页（对齐 iOS AddPlanetPage.swift）：虚线预览身份区 + 大标题 +
 * 内嵌表单，与行星页的布局语言刻意区分。预览名称由本页生成/去重，
 * 经 [onPreviewName] 与父级（HomeView）共享；到达本页时表单落位弹簧入场。
 */
@Composable
internal fun AddPlanetPage(
    vm: HomeViewModel,
    state: HomeUiState,
    previewName: String,
    onPreviewName: (String) -> Unit,
    isCurrentPage: Boolean,
) {
    val context = LocalContext.current
    val formShown = remember { Animatable(0f) }
    // 身份状态：动物 key + 底色 hex。名称未被用户手动编辑前跟随动物本地化名
    var animal by remember { mutableStateOf(PlanetIcon.randomAnimal(context)) }
    var colorHex by remember { mutableStateOf(PlanetIcon.randomColorHex()) }
    var nameEditedByUser by remember { mutableStateOf(false) }
    var showIconSettings by remember { mutableStateOf(false) }

    /** 重掷身份：随机动物（本地化名与现有行星去重）+ 随机底色；名称未手动编辑过则跟随。 */
    fun rollIdentity() {
        animal = PlanetIcon.randomAnimal(context, existingNames(state))
        colorHex = PlanetIcon.randomColorHex()
        nameEditedByUser = false
        onPreviewName(PlanetIcon.localizedName(context, animal))
    }

    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            // 落位后再入场（iOS 延迟 0.15s 防快甩时动画跑一半）
            delay(150)
            formShown.animateTo(
                1f,
                spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow),
            )
        } else {
            formShown.snapTo(0f)
        }
    }
    LaunchedEffect(Unit) {
        if (previewName.isEmpty()) rollIdentity()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = HomeMetrics.pageTopPadding)
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PreviewIdentity(
            animal = animal,
            colorHex = colorHex,
            name = previewName,
            onNameChange = { value ->
                onPreviewName(value)
                if (value != PlanetIcon.localizedName(context, animal)) nameEditedByUser = true
            },
            onRandom = { rollIdentity() },
            onOpenPicker = { showIconSettings = true },
            modifier = Modifier
                .padding(horizontal = HomeMetrics.pageHMargin)
                .padding(top = 4.dp),
        )
        Column(
            modifier = Modifier.padding(horizontal = HomeMetrics.pageHMargin),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.planet_add_title),
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            )
            Text(
                stringResource(R.string.addplanet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AddPlanetForm(
            vm = vm,
            state = state,
            name = previewName,
            onNameChange = { value ->
                onPreviewName(value)
                if (value != PlanetIcon.localizedName(context, animal)) nameEditedByUser = true
            },
            animal = animal,
            colorHex = colorHex,
            onImported = {
                // 添加成功：重掷身份，避免与刚添加的行星同名/同图
                rollIdentity()
            },
            modifier = Modifier
                .padding(horizontal = HomeMetrics.pageHMargin)
                .graphicsLayer {
                    alpha = formShown.value
                    translationY = (1f - formShown.value) * 120.dp.toPx()
                },
        )
    }

    if (showIconSettings) {
        IconPickerSheet(
            animal = animal,
            color = colorHex,
            onSelect = { newAnimal, newColor ->
                animal = newAnimal
                colorHex = newColor
                // 手动选图形后：名称未手动编辑过则跟随（不去重，名称冲突由用户改名）
                if (!nameEditedByUser) onPreviewName(PlanetIcon.localizedName(context, newAnimal))
            },
            onDismiss = { showIconSettings = false },
        )
    }
}

private fun existingNames(state: HomeUiState): Set<String> =
    state.planets.map { it.name }.toSet()

/**
 * 身份预览区（iOS :67-124）：72dp 图标磁贴（点击弹图标设置）+ 行内名称编辑 +
 * 随机按钮（重掷图形+底色）。虚线边框沿用原预览语义：「尚未创建」的占位形态。
 */
@Composable
private fun PreviewIdentity(
    animal: String,
    colorHex: String,
    name: String,
    onNameChange: (String) -> Unit,
    onRandom: () -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 组合期取色（drawBehind 非 composable 上下文）
    val tileBg = PlanetIcon.parseHex(colorHex) ?: Color(0xFF6B7280)
    val dashColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val fieldBg = MaterialTheme.colorScheme.surfaceContainerHigh

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f))
            .drawBehind {
                drawRoundRect(
                    color = dashColor,
                    cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 72dp 磁贴（38dp 白动物）：点击进图标设置
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(tileBg)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenPicker,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(PlanetIcon.drawableId(context, animal)),
                contentDescription = stringResource(R.string.common_icon),
                tint = Color.White,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Row(
            modifier = Modifier.widthIn(max = 260.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormField(
                value = name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.addplanet_namePlaceholder),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(fieldBg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onRandom,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.addplanet_randomIcon),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 添加行星表单（对齐 iOS AddPlanetForm.swift）：名称 + 随机换名 / 选择行星文件
 * （头部字节 0x01 校验，就绪态「已就绪 · 大小」）/ URL 导入（下载中转圈）/
 * 主按钮「添加行星服务器」（重复 World ID 报错）。文件/URL 先就绪，再点添加落库。
 */
@Composable
private fun AddPlanetForm(
    vm: HomeViewModel,
    state: HomeUiState,
    name: String,
    onNameChange: (String) -> Unit,
    animal: String,
    colorHex: String,
    onImported: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var pendingData by remember { mutableStateOf<ByteArray?>(null) }
    var alert by remember { mutableStateOf<String?>(null) }
    // 回调非 Composable 上下文：文案先在组合期解析
    val fileUnreadable = stringResource(R.string.addplanet_fileUnreadable)
    val badHeader = stringResource(R.string.addplanet_badHeader)

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
            }.getOrNull()
            when {
                data == null -> alert = fileUnreadable
                data.isEmpty() || data[0] != PlanetImporter.PLANET_TYPE_BYTE -> alert = badHeader
                else -> pendingData = data
            }
        }
    }

    val trimmedName = name.trim()
    val canAdd = pendingData != null && trimmedName.isNotEmpty()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // 信息录入卡（圆角 20 + 内边距 18）；名称编辑在身份区行内（iOS b7bba22）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(HomeMetrics.radiusLarge))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(HomeMetrics.formCardPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 选择行星文件：整块按钮（含背景）都是热区
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(HomeMetrics.radiusSmall))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { filePicker.launch(arrayOf("*/*")) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.addplanet_chooseFile),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.weight(1f))
                val ready = pendingData
                if (ready != null) {
                    Text(
                        stringResource(R.string.addplanet_ready, fileSizeLabel(ready.size)),
                        style = MaterialTheme.typography.bodySmall,
                        color = cn.imgozi.planetport.ui.theme.LocalPlanetStatusColors.current.good,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            // URL 输入 + 导入按钮（56×42，下载中转圈）
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = stringResource(R.string.addplanet_urlPlaceholder),
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 42.dp)
                        .clip(RoundedCornerShape(HomeMetrics.radiusSmall))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = url.isNotEmpty() && !downloading,
                        ) {
                            downloading = true
                            vm.downloadPlanet(url) { data, error ->
                                downloading = false
                                when {
                                    error != null -> alert = error
                                    data != null && (data.isEmpty() || data[0] != PlanetImporter.PLANET_TYPE_BYTE) ->
                                        alert = badHeader
                                    data != null -> pendingData = data
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (downloading) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                    } else {
                        Text(
                            stringResource(R.string.common_import),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (url.isEmpty() || downloading) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        // 主按钮：全宽 tint 填充圆角 14，禁用 0.4 透明度，宽度 84%
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(HomeMetrics.radiusCard))
                .background(if (canAdd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = canAdd,
                ) {
                    val data = pendingData ?: return@clickable
                    vm.importPlanetFile(data, trimmedName, animal, colorHex) { _, error ->
                        if (error != null) {
                            alert = error
                        } else {
                            pendingData = null
                            url = ""
                            onImported()
                        }
                    }
                }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.addplanet_action),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
        }
    }

    if (alert != null) {
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(stringResource(R.string.addplanet_importFailed)) },
            text = { Text(alert ?: "") },
            confirmButton = {
                TextButton(onClick = { alert = null }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }
}

/** 表单输入（iOS field()：quaternary 底圆角 10，无自动大写/纠错）。 */
@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(HomeMetrics.radiusSmall))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = TextStyle(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
        modifier = modifier,
    )
}

/** 文件大小可读化（iOS locFileSize 同构）。 */
private fun fileSizeLabel(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

package cn.imgozi.planetport.ui.planet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.R
import cn.imgozi.planetport.ui.theme.ThemeManager

/**
 * 图标设置（iOS IconPickerSheet.swift 1:1）：分段「图形 / 底色」。图形页 4 列
 * 动物网格（磁贴底色 = 当前选中底色，白色图形可见）+ 本地化名 + 选中标记；
 * 底色页当前预览 + 随机按钮 + 20 预设色板。点「完成」统一生效（onSelect +
 * 关闭）。无「恢复自动」——图标一经选择永久使用，自动算法仅作未设置时兜底。
 *
 * @param animal 当前动物 key（传有效解析结果，如 PlanetIcon.animalKey）
 * @param color 当前底色 hex（null = 未自定义，此时随机色作为初始预选）
 * @param onSelect (选中动物 key, 选中底色 hex)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPickerSheet(
    animal: String,
    color: String?,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(0) } // 0=图形 1=底色
    var selectedAnimal by remember { mutableStateOf(animal) }
    var selectedColor by remember { mutableStateOf(color ?: PlanetIcon.randomColorHex()) }

    val accentHex by ThemeManager.accent.collectAsState()
    val accent = remember(accentHex) { Color(ThemeManager.accent.value.toInt()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.icon_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    onSelect(selectedAnimal, selectedColor)
                    onDismiss()
                }) { Text(stringResource(R.string.common_done)) }
            }

            // 分段：图形 / 底色（iOS Picker segmented）
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.icon_graphic)) }
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.icon_color)) }
            }

            if (tab == 0) {
                AnimalGrid(
                    selectedAnimal = selectedAnimal,
                    selectedColor = selectedColor,
                    accent = accent,
                    onSelect = { selectedAnimal = it },
                )
            } else {
                ColorGrid(
                    selectedAnimal = selectedAnimal,
                    selectedColor = selectedColor,
                    onSelect = { selectedColor = it },
                    onRandom = { selectedColor = PlanetIcon.randomColorHex() },
                )
            }
        }
    }
}

/** 预设底色：中低亮度、白图形对比度可靠的色板（iOS 逐字）。 */
private val palette = listOf(
    "#b03a3a", "#c0392b", "#d44037", "#c05621",
    "#b45309", "#a16207", "#854d0e", "#15803d",
    "#166534", "#0f766e", "#0e7490", "#155e75",
    "#1d4ed8", "#1e40af", "#3730a3", "#6d28d9",
    "#a21caf", "#9d174d", "#475569", "#334155",
)

/** 图形页：92 动物 4 列网格；磁贴底色跟当前选中底色（白色图形需深底才可见）。 */
@Composable
private fun AnimalGrid(
    selectedAnimal: String,
    selectedColor: String,
    accent: Color,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val tileBg = PlanetIcon.parseHex(selectedColor) ?: Color(0xFF6B7280) // 深灰兜底
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(max = 440.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(PlanetIcon.catalog) { key ->
            val selected = key == selectedAnimal
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (selected) {
                            accent.copy(alpha = 0.1f)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                    )
                    .clickable { onSelect(key) }
                    .padding(vertical = 8.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(tileBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(PlanetIcon.drawableId(context, key)),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    PlanetIcon.localizedName(context, key),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                // 选中标记行（未选中占位对齐，iOS circle 透明）
                if (selected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.icon_current),
                        tint = accent,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Spacer(Modifier.height(14.dp))
                }
            }
        }
    }
}

/** 底色页：72dp 预览磁贴 + 随机底色按钮 + 20 色板（右上白勾选中标记）。 */
@Composable
private fun ColorGrid(
    selectedAnimal: String,
    selectedColor: String,
    onSelect: (String) -> Unit,
    onRandom: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(PlanetIcon.parseHex(selectedColor) ?: Color(0xFF6B7280)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(PlanetIcon.drawableId(context, selectedAnimal)),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onRandom) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.icon_randomColor))
        }
        Spacer(Modifier.height(14.dp))
        // 20 色板 4 列（非 Lazy：数量固定，外层已可滚）
        palette.chunked(4).forEach { rowHexes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowHexes.forEach { hex ->
                    Box(Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PlanetIcon.parseHex(hex) ?: Color(0xFF6B7280))
                                .clickable { onSelect(hex) },
                        ) {
                            if (hex == selectedColor) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = stringResource(R.string.icon_current),
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(14.dp),
                                )
                            }
                        }
                    }
                }
                    // 行不满 4 个时补位保持列宽一致
                    repeat(4 - rowHexes.size) { Spacer(Modifier.weight(1f)) }
                }
        }
    }
}

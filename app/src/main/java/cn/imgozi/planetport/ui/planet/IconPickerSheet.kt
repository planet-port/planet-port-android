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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.ui.theme.ThemeManager

/**
 * 图标选择弹层（iOS IconPickerSheet）：4 列网格、当前图标勾选、右上「恢复自动」。
 * [onSelect] 传 null = 恢复自动生成；选择后由调用方关闭弹层（对齐 iOS onSelect + dismiss）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconPickerSheet(
    current: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.weight(1f))
                Text(
                    "选择图标",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    onSelect(null)
                    onDismiss()
                }) { Text("恢复自动") }
            }

            val accentHex by ThemeManager.accent.collectAsState()
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            val tint = remember(accentHex, dark) { ThemeManager.accentFor(dark) }

            // 12 个固定项直接用行/列布局：不用 Lazy 网格（弹层内嵌套滚动且会撑满高度）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlanetIconCatalog.chunked(4).forEach { rowSymbols ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        rowSymbols.forEach { symbol ->
                            Box(Modifier.weight(1f)) {
                                Column(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .clickable {
                                            onSelect(symbol)
                                            onDismiss()
                                        }
                                        .padding(vertical = 10.dp)
                                        .fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = planetIconVector(symbol),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(26.dp),
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    if (symbol == current) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = "当前图标",
                                            tint = tint,
                                            modifier = Modifier.size(14.dp),
                                        )
                                    } else {
                                        Spacer(Modifier.height(15.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

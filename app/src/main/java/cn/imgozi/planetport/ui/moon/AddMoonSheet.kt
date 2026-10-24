package cn.imgozi.planetport.ui.moon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import cn.imgozi.planetport.R
import cn.imgozi.planetport.ui.components.SectionCard
import cn.imgozi.planetport.ui.theme.PlanetFonts

/**
 * 添加卫星弹层（对齐 iOS AddMoonSheet：文件导入 / 手动入轨二选一）。
 *
 * 手动入轨：World ID + Seed 两个 16 位 hex 输入，均合法才可「入轨」；
 * 同 worldId 条目为替换语义（对齐 iOS addMoon removeAll + append），不做查重提示。
 * 文件导入回调交由页面打开系统文件选择器（sheet 先关闭，对齐 iOS 0.3s 延迟语义）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMoonSheet(
    onDismiss: () -> Unit,
    onFileImport: () -> Unit,
    onManualOrbit: (worldId: ULong, seed: ULong) -> Unit,
) {
    var worldIdText by remember { mutableStateOf("") }
    var seedText by remember { mutableStateOf("") }
    val moonChooseFile = stringResource(R.string.moon_chooseFile)

    val valid = isHex16(worldIdText) && isHex16(seedText)
    val worldId = if (valid) worldIdText.toULongOrNull(16) else null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.moon_addTitle),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }

            // —— 从文件导入 ——
            Text(
                stringResource(R.string.moon_importFileHeader),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = moonChooseFile) { onFileImport() },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(moonChooseFile, style = MaterialTheme.typography.labelLarge)
                }
            }

            // —— 手动入轨 ——
            Text(
                stringResource(R.string.moon_manualHeader),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = worldIdText,
                onValueChange = { worldIdText = it },
                label = { Text(stringResource(R.string.moon_worldIdPlaceholder)) },
                singleLine = true,
                textStyle = PlanetFonts.MonoBody,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = seedText,
                onValueChange = { seedText = it },
                label = { Text(stringResource(R.string.moon_seedPlaceholder)) },
                singleLine = true,
                textStyle = PlanetFonts.MonoBody,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    capitalization = KeyboardCapitalization.Characters,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val id = worldId ?: return@Button
                    val seed = seedText.toULongOrNull(16) ?: return@Button
                    onManualOrbit(id, seed)
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.moon_orbit))
            }
        }
    }
}

/** 16 位十六进制校验（对齐 iOS hex16Valid：长度 16 且全为 hex 字符，大小写均可）。 */
private fun isHex16(value: String): Boolean =
    value.length == 16 && value.all(::isHexChar)

private fun isHexChar(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

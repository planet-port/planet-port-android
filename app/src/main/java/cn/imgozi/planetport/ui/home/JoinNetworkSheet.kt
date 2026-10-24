package cn.imgozi.planetport.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.ui.components.DnsConfigSection
import cn.imgozi.planetport.ui.components.isDnsConfigValid
import cn.imgozi.planetport.ui.theme.PlanetFonts

/**
 * 加入网络弹窗（对齐 iOS JoinNetworkSheet.swift）：16 位 hex 网络 ID（尾部扫码
 * 按钮，实相 T7 接入，当前占位禁用）+ routeVia 开关（默认关）+ DNS 三模式。
 * 「加入」= 仅保存不连接，由用户在网络卡上手动打开开关（iOS 既定启动模型）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JoinNetworkSheet(
    vm: HomeViewModel,
    state: HomeUiState,
    onDismiss: () -> Unit,
) {
    var networkId by remember { mutableStateOf("") }
    var routeVia by remember { mutableStateOf(false) }
    var dnsMode by remember { mutableStateOf(DnsMode.none) }
    var dnsServers by remember { mutableStateOf(listOf<String>()) }
    var duplicateAlert by remember { mutableStateOf(false) }
    var limitAlert by remember { mutableStateOf(false) }

    val idValid = networkId.length == 16 && networkId.all { c ->
        c.isDigit() || c in 'a'..'f' || c in 'A'..'F'
    }
    val dnsValid = isDnsConfigValid(dnsMode, dnsServers)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.weight(1f))
                Text("加入网络", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = idValid && dnsValid,
                    onClick = {
                        // 软上限：达上限时阻断加入并提示（控制方裁决 #5，判定在保存前）
                        val existing = state.planetNetworks[state.selectedPlanetId].orEmpty()
                        if (existing.size >= state.maxNetworks) {
                            limitAlert = true
                            return@TextButton
                        }
                        val newId = networkId.lowercase()
                        // 网络 ID 查重：已存在视为同一网络，禁止重复添加（iOS join()）
                        if (existing.any { it.networkId == newId }) {
                            duplicateAlert = true
                            return@TextButton
                        }
                        vm.joinNetwork(
                            NetworkEntry(
                                networkId = newId,
                                networkName = newId,
                                // 仅保存不自动连接
                                lastActivated = false,
                                routeViaZeroTier = routeVia,
                                dnsMode = dnsMode,
                                customDns = if (dnsMode == DnsMode.custom) dnsServers else emptyList(),
                            ),
                        ) { duplicateAlert = true }
                        onDismiss()
                    },
                ) { Text("加入") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = networkId,
                    onValueChange = { networkId = it },
                    label = { Text("16 位十六进制") },
                    singleLine = true,
                    textStyle = PlanetFonts.MonoBody.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                    trailingIcon = {
                        // 扫码回填（iOS QRScannerView）T7 接 CameraX + zxing，先占位禁用
                        IconButton(onClick = {}, enabled = false) {
                            Icon(
                                imageVector = Icons.Outlined.QrCodeScanner,
                                contentDescription = "扫码识别网络 ID",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (networkId.isNotEmpty() && !idValid) {
                    Text(
                        "网络 ID 必须为 16 位十六进制字符",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("默认路由经 ZeroTier", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = routeVia, onCheckedChange = { routeVia = it })
                }
                Text(
                    "开启后所有流量经 ZeroTier 隧道路由",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DNS", style = MaterialTheme.typography.labelLarge)
                DnsConfigSection(
                    mode = dnsMode,
                    dns = dnsServers,
                    enabled = true,
                    onChange = { mode, servers ->
                        dnsMode = mode
                        dnsServers = servers
                    },
                    showNetworkDns = null,
                )
            }
        }
    }

    if (duplicateAlert) {
        AlertDialog(
            onDismissRequest = { duplicateAlert = false },
            title = { Text("网络已存在") },
            text = { Text("网络 ${networkId.lowercase()} 已在列表中，不能重复添加。") },
            confirmButton = {
                TextButton(onClick = { duplicateAlert = false }) { Text("好") }
            },
        )
    }

    if (limitAlert) {
        AlertDialog(
            onDismissRequest = { limitAlert = false },
            title = { Text("已达网络数量上限") },
            text = {
                Text(
                    "每个行星最多加入 ${state.maxNetworks} 个网络，如需添加请先移除已有网络。"
                )
            },
            confirmButton = {
                TextButton(onClick = { limitAlert = false }) { Text("好") }
            },
        )
    }
}

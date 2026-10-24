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
import androidx.compose.ui.res.stringResource
import cn.imgozi.planetport.R
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
    var showScanner by remember { mutableStateOf(false) }

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
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.join_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = idValid && dnsValid,
                    onClick = {
                        // 加入=仅保存不连接，无数量上限（iOS join() 同构）——maxNetworks
                        // 软上限只作用于激活路径（HomeView 开关处，可强制开启）
                        val newId = networkId.lowercase()
                        // 网络 ID 查重：已存在视为同一网络，禁止重复添加（iOS join()）
                        val existing = state.planetNetworks[state.selectedPlanetId].orEmpty()
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
                        )
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.join_action)) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = networkId,
                    onValueChange = { networkId = it },
                    label = { Text(stringResource(R.string.join_idPlaceholder)) },
                    singleLine = true,
                    textStyle = PlanetFonts.MonoBody.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                    trailingIcon = {
                        // 扫码回填（iOS QRScannerView）：识别到 16 位 hex 即填入
                        IconButton(onClick = { showScanner = true }) {
                            Icon(
                                imageVector = Icons.Outlined.QrCodeScanner,
                                contentDescription = stringResource(R.string.join_a11yScan),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (networkId.isNotEmpty() && !idValid) {
                    Text(
                        stringResource(R.string.join_idInvalid),
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
                    Text(
                        stringResource(R.string.join_routeVia),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(checked = routeVia, onCheckedChange = { routeVia = it })
                }
                Text(
                    stringResource(R.string.join_routeViaFooter),
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

    // 扫码弹层：识别回填 ID 并关闭（对齐 iOS JoinNetworkSheet sheet(QRScannerView) 接线）
    if (showScanner) {
        QrScannerSheet(
            onScanned = { id ->
                networkId = id
                showScanner = false
            },
            onDismiss = { showScanner = false },
        )
    }

    if (duplicateAlert) {
        AlertDialog(
            onDismissRequest = { duplicateAlert = false },
            title = { Text(stringResource(R.string.join_duplicateTitle)) },
            text = {
                Text(
                    stringResource(R.string.join_duplicateMessage, networkId.lowercase()),
                )
            },
            confirmButton = {
                TextButton(onClick = { duplicateAlert = false }) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}

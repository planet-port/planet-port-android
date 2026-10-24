package cn.imgozi.planetport.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import cn.imgozi.planetport.R
import cn.imgozi.planetport.store.DnsMode
import cn.imgozi.planetport.ui.theme.PlanetFonts

/**
 * DNS 四槽位编辑模型（对齐 iOS DNSConfig）：v4×2 + v6×2，
 * 持久化时合并为 [String]（v4 在前，v4/v6 按 ":" 区分）。
 */
data class DnsSlots(
    val v4a: String = "",
    val v4b: String = "",
    val v6a: String = "",
    val v6b: String = "",
) {
    /** 要保存的服务器列表（空槽位丢弃，v4 在前；与 iOS customServers 一致，含未通过校验的非空值）。 */
    fun toServers(): List<String> = listOf(v4a, v4b, v6a, v6b).filter { it.isNotBlank() }

    companion object {
        /** 从持久化列表展开（v4/v6 按 ":" 区分，对齐 iOS DNSConfig.init）。 */
        fun from(servers: List<String>): DnsSlots {
            val v4 = servers.filter { !it.contains(':') }
            val v6 = servers.filter { it.contains(':') }
            return DnsSlots(
                v4a = v4.getOrElse(0) { "" },
                v4b = v4.getOrElse(1) { "" },
                v6a = v6.getOrElse(0) { "" },
                v6b = v6.getOrElse(1) { "" },
            )
        }
    }
}

/** 手写 IPv4 校验：点分四段、每段 0..255、拒绝前导零（较传统 inet_pton 更严格——
 *  后者把 010 视作十进制 10 接受；无功能危害，取向更严）。 */
fun isValidIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false
    return parts.all { p ->
        p.isNotEmpty() && p.length <= 3 && p.all(Char::isDigit) &&
            (p.length == 1 || p[0] != '0') && p.toInt() <= 255
    }
}

/**
 * 手写 IPv6 校验（对齐 inet_pton(AF_INET6)）：1..4 位十六进制冒分组、
 * 至多一处 "::" 压缩（压缩时总组数 1..7，否则恰 8 组）、支持内嵌 IPv4 尾段
 * （如 `::ffff:192.0.2.1`）；禁止缩写边界冗余冒号与 zone id（%eth0）。
 */
fun isValidIpv6(value: String): Boolean {
    if (value.isEmpty()) return false
    if (value.any { !it.isHexDigit() && it != ':' && it != '.' }) return false

    // 内嵌 IPv4 尾段等价替换为两个十六进制组再统一判定
    var body = value
    if (value.contains('.')) {
        val tail = value.substringAfterLast(':')
        if (tail == value || !tail.contains('.') || !isValidIpv4(tail)) return false
        body = value.substringBeforeLast(':') + ":0:0"
    }

    val halves = body.split("::")
    return when {
        halves.size > 2 -> false
        halves.size == 2 -> {
            val head = hexGroups(halves[0]) ?: return false
            val tail = hexGroups(halves[1]) ?: return false
            (head.size + tail.size) in 1..7
        }
        else -> hexGroups(body)?.size == 8
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** 按冒号切组并校验；空组（压缩语法外）或超长组返回 null。 */
private fun hexGroups(part: String): List<String>? {
    if (part.isEmpty()) return emptyList()
    val items = part.split(':')
    if (items.any { it.isEmpty() || it.length > 4 || it.any { c -> !c.isHexDigit() } }) return null
    return items
}

/**
 * DNS 配置合法性口径（供 T3「加入」按钮 / T4「应用 DNS 配置」按钮门控），
 * 对齐 iOS DNSConfig.isValid：非空槽位必须合法，且至少一项非空（IPv4 DNS 1
 * 可空——只配 IPv6 DNS 是合法用法）；其余模式恒合法。
 */
fun isDnsConfigValid(mode: DnsMode, dns: List<String>): Boolean {
    if (mode != DnsMode.custom) return true
    val s = DnsSlots.from(dns)
    fun slotOk(v: String, valid: (String) -> Boolean) = v.isEmpty() || valid(v)
    return slotOk(s.v4a, ::isValidIpv4) && slotOk(s.v4b, ::isValidIpv4) &&
        slotOk(s.v6a, ::isValidIpv6) && slotOk(s.v6b, ::isValidIpv6) &&
        (s.v4a.isNotEmpty() || s.v4b.isNotEmpty() || s.v6a.isNotEmpty() || s.v6b.isNotEmpty())
}

/**
 * DNS 三模式配置区（iOS DNSConfigSection 等价，加入网络弹窗与详情页共用）：
 * 三段分段选择「不使用 / 使用网络 DNS / 自定义」；custom 展开四个输入槽位，
 * 非空且非法时红色 caption「DNS 地址格式不正确」（空槽不标红，按钮门控由
 * [isDnsConfigValid] 统一判断「至少一项非空」）。
 *
 * @param dns 持久化的 customDns 列表（与 NetworkEntry.customDns 同布局）
 * @param onChange 任一槽位/模式变更即上报（列表含未通过校验的非空值，与 iOS 一致，
 *                 由 [isDnsConfigValid] 门控保存）
 * @param showNetworkDns 非 null 即追加只读「网络下发 DNS」区（详情页模式）；
 *                       列表为空显示「等待网络下发…」
 */
@Composable
fun DnsConfigSection(
    mode: DnsMode,
    dns: List<String>,
    enabled: Boolean,
    onChange: (DnsMode, List<String>) -> Unit,
    showNetworkDns: List<String>? = null,
    modifier: Modifier = Modifier,
) {
    var slots by remember { mutableStateOf(DnsSlots.from(dns)) }
    // 本组件最近一次上报的列表：区分「父级回写回声」与「外部真变更」
    var lastEmitted by remember { mutableStateOf<List<String>?>(null) }

    // 外部列表变更（如详情页载入另一网络）→ 重新展开为槽位；回写回声不动，避免编辑态被打断
    LaunchedEffect(dns) {
        if (dns != slots.toServers() && dns != lastEmitted) {
            slots = DnsSlots.from(dns)
        }
    }

    fun emit(newMode: DnsMode, newSlots: DnsSlots) {
        val servers = newSlots.toServers()
        lastEmitted = servers
        slots = newSlots
        onChange(newMode, servers)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val modes = listOf(
            DnsMode.none to stringResource(R.string.dns_mode_none),
            DnsMode.network to stringResource(R.string.dns_mode_network),
            DnsMode.custom to stringResource(R.string.dns_mode_custom),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, (segmentMode, label) ->
                // 分段文案（如简中「使用网络 DNS」）在默认 labelLarge(14sp) + 12dp 水平
                // 内边距下必换行：字号 14→11（3dp 步进仍清晰的最小值）并收窄水平内边距，
                // 简中/繁中/英文三语均单行放下
                SegmentedButton(
                    selected = mode == segmentMode,
                    onClick = { emit(segmentMode, slots) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    Text(
                        label,
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    )
                }
            }
        }

        if (mode == DnsMode.custom) {
            DnsField(
                value = slots.v4a, label = stringResource(R.string.dns_v4a), ipv4 = true, enabled = enabled,
                onValueChange = { emit(mode, slots.copy(v4a = it)) },
            )
            DnsField(
                value = slots.v4b, label = stringResource(R.string.dns_v4b), ipv4 = true, enabled = enabled,
                onValueChange = { emit(mode, slots.copy(v4b = it)) },
            )
            DnsField(
                value = slots.v6a, label = stringResource(R.string.dns_v6a), ipv4 = false, enabled = enabled,
                onValueChange = { emit(mode, slots.copy(v6a = it)) },
            )
            DnsField(
                value = slots.v6b, label = stringResource(R.string.dns_v6b), ipv4 = false, enabled = enabled,
                onValueChange = { emit(mode, slots.copy(v6b = it)) },
            )
            if (!isDnsConfigValid(mode, slots.toServers())) {
                Text(
                    stringResource(R.string.dns_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (showNetworkDns != null) {
            if (showNetworkDns.isEmpty()) {
                Text(
                    stringResource(R.string.network_dnsPending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    showNetworkDns.forEach { server ->
                        Text(
                            server,
                            style = PlanetFonts.MonoCaption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DnsField(
    value: String,
    label: String,
    ipv4: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = value.isNotBlank() && !(if (ipv4) isValidIpv4(value) else isValidIpv6(value)),
        keyboardOptions = KeyboardOptions(
            // Decimal 含点号（Number 面板无「.」无法输入合法 IPv4，对齐 iOS .decimalPad）
            keyboardType = if (ipv4) KeyboardType.Decimal else KeyboardType.Text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

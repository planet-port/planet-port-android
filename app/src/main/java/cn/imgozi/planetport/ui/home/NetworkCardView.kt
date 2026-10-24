package cn.imgozi.planetport.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.store.NetworkEntry
import cn.imgozi.planetport.ui.NetworkCardStatus
import cn.imgozi.planetport.ui.components.StatusDot
import cn.imgozi.planetport.ui.theme.HomeMetrics
import cn.imgozi.planetport.ui.theme.LocalPlanetStatusColors
import cn.imgozi.planetport.ui.theme.PlanetFonts
import cn.imgozi.planetport.ui.theme.tertiaryTextColor
import kotlinx.coroutines.delay

/**
 * 网络卡（纯展示组件，对齐 iOS NetworkCardView.swift:73-138）：状态、开关值、
 * 地址均由外部传入；开关/点击经回调交还 HomeView 编排（超限确认、隧道启停
 * 均在上层）。行内导航用单击（无涟漪），长按 = 复制 ID（iOS contextMenu）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NetworkCardView(
    entry: NetworkEntry,
    status: NetworkCardStatus,
    isPending: Boolean,
    appearsActive: Boolean,
    addresses: List<String>?,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tertiary = tertiaryTextColor(isSystemInDarkTheme())
    val statusColor = LocalPlanetStatusColors.current[status.color]
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(HomeMetrics.radiusCard))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(HomeMetrics.cardPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                    onLongClick = { clipboard.setText(AnnotatedString(entry.networkId)) },
                ),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                entry.networkName.ifEmpty { entry.networkId },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.networkId,
                style = PlanetFonts.MonoCaption,
                color = tertiary,
                maxLines = 1,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                StatusDot(color = statusColor)
                Text(status.label, style = MaterialTheme.typography.bodySmall, color = statusColor)
                IpGroup(addresses, tertiary)
            }
        }

        if (isPending) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp))
            }
        } else {
            Switch(
                checked = appearsActive,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics {
                    contentDescription = "启用网络 ${entry.networkName.ifEmpty { entry.networkId }}"
                },
            )
        }

        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = tertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * 已分配 IP（前 2 个）+ 复制图标（iOS :142-173）：点文字或图标均复制第一个 IP，
 * 图标成功后短暂变对勾；无数据（未连接/非活跃行星）以「-」占位。
 */
@Composable
private fun IpGroup(addresses: List<String>?, tertiary: androidx.compose.ui.graphics.Color) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val success = LocalPlanetStatusColors.current.good
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(800)
            copied = false
        }
    }

    if (addresses.isNullOrEmpty()) {
        Text("-", style = PlanetFonts.MonoCaption2, color = tertiary)
        return
    }
    val copyFirst = {
        clipboard.setText(AnnotatedString(addresses.first()))
        copied = true
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            addresses.take(2).joinToString("  "),
            style = PlanetFonts.MonoCaption2,
            color = tertiary,
            maxLines = 1,
            modifier = Modifier.clickableNoIndication(onClick = copyFirst),
        )
        Icon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            contentDescription = "复制 IP",
            tint = if (copied) success else tertiary,
            modifier = Modifier
                .size(10.dp)
                .clickableNoIndication(onClick = copyFirst),
        )
    }
}

/** 无涟漪点击（iOS onTapGesture 形态：无高亮反馈）。 */
@Composable
private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null, onClick = onClick)
}

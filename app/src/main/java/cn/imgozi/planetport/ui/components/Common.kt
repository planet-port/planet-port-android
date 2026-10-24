package cn.imgozi.planetport.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import cn.imgozi.planetport.R
import cn.imgozi.planetport.ui.theme.HomeMetrics
import cn.imgozi.planetport.ui.theme.LocalPlanetStatusColors
import cn.imgozi.planetport.ui.theme.PlanetFonts
import kotlinx.coroutines.delay

/** 语义状态圆点（iOS NetworkCardView 状态行，默认 6pt）。 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = HomeMetrics.statusDotSize,
) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/**
 * 徽标胶囊（iOS 徽标纪律：caption2 semibold + 0.12 同色底，如「已缓存/使用中」）。
 * [icon] 可选（文件导入的 doc 标记等）。
 */
@Composable
fun Badge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(11.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 卡片容器（iOS 卡片纪律：圆角 14 + padding 14 + secondarySystemGroupedBackground
 * 底色，无阴影、靠底色分层）。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(HomeMetrics.radiusCard),
    contentPadding: PaddingValues = PaddingValues(HomeMetrics.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * 可复制值文本（iOS CopyableValue 1:1）：点击复制到剪贴板，图标位置短暂变
 * 对勾（0.8s 还原）；[showsIcon] 为 false 时无常驻图标，仅复制成功瞬间显示。
 * 空串与「—」「-」占位不响应。iOS 以透明热区外扩 5pt 补命中区，Compose 布局
 * 不可越界，热区 = 文本 + 图标内容本身（行场景另有 44dp 行高兜底）。
 */
@Composable
fun CopyableValue(
    text: String,
    modifier: Modifier = Modifier,
    showsIcon: Boolean = true,
    style: TextStyle = PlanetFonts.MonoBody,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconSize: Dp = 11.dp,
) {
    // LocalClipboardManager.setText：现代 LocalClipboard 为 suspend API，
    // 此处同步拷贝即可（小文本，无需协程），deprecation 由 T7 资源化时一并处理
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val successColor = LocalPlanetStatusColors.current.good
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(800)
            copied = false
        }
    }

    Row(
        modifier = modifier.clickable(
            onClickLabel = stringResource(R.string.common_copy),
            enabled = text.isNotEmpty() && text != "—" && text != "-",
        ) {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        if (showsIcon || copied) {
            Icon(
                imageVector = if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.common_copy),
                tint = if (copied) {
                    successColor
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** 详情页「标题 + 可复制值」行（iOS CopyableRow）：标题左、值右，值整体可点复制。 */
@Composable
fun CopyableRow(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    showsIcon: Boolean = true,
    style: TextStyle = PlanetFonts.MonoBody,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        CopyableValue(text = text, showsIcon = showsIcon, style = style)
    }
}

/**
 * 空态卡（首页/Peers/Moon 三处共用形态：图标 32 + 主文案 + 指引，卡片化；
 * iOS HomeView.swift:403-421）。整卡可点击（如打开加入网络弹窗）。
 */
@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    SectionCard(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

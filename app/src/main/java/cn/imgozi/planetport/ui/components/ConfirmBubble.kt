package cn.imgozi.planetport.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.max

// foundation 1.10 无 Capsule 形状，胶囊等价于完全圆角（50% 圆角）
private val CapsuleShape = RoundedCornerShape(percent = 50)

/**
 * 锚定在触发行上方的确认气泡（自绘，对齐 iOS ConfirmBubble）。
 *
 * 双端交互一致：不做居中模态，气泡底边贴触发行卡片可视顶边（含呼吸间距）、
 * 尾尖向下探入行内指向该行；点气泡外任意处关闭。视觉参数与 iOS 版一致：
 * 宽 260 / 内边距 16 / 圆角 20 / 标题正文间距 4 / 正文按钮间距 14 / 按钮垂直
 * 内边距 9（胶囊）/ 尾尖 18×9 下探 9 / 呼吸间距 10 / 动画 0.15s scale0.95。
 *
 * [anchorTopPx] 是触发行内容顶边在组合根坐标系的 y（页面删除行用
 * `onGloballyPositioned { it.positionInRoot().y }` 持续上报）。本组件铺满
 * 全屏且自身无平移，根坐标可直接使用。滚动时 onGloballyPositioned 重新
 * 上报，气泡跟随。
 */
@Composable
fun ConfirmBubble(
    visible: Boolean,
    anchorTopPx: Float,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
) {
    val density = LocalDensity.current
    // SectionCard 内容垂直内边距 4：锚点量到行内容顶边，上提后贴卡片可视顶边
    val cardInsetPx = with(density) { 4.dp.toPx() }
    // 气泡底边与卡片顶边之间的呼吸间距
    val gapPx = with(density) { 10.dp.toPx() }
    // 顶部对齐盒子高度 = 容器顶到卡片可视顶边；下限 60 防触发行贴屏顶时塌缩
    val boxHeight = with(density) {
        max(60.dp.toPx(), anchorTopPx - cardInsetPx - gapPx).toDp()
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(150)) +
            scaleIn(
                animationSpec = tween(150),
                initialScale = 0.95f,
                transformOrigin = TransformOrigin(0.5f, 1f),
            ),
        exit = fadeOut(tween(150)),
    ) {
        // 全屏点击 catcher：气泡外任意点关闭
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            // 高度 = 容器顶到触发行卡片可视顶边的盒子，气泡底对齐盒底：
            // 气泡底边（尾尖根部）贴触发行卡片顶边
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(boxHeight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                BubbleContent(title, message, confirmLabel, onConfirm)
            }
        }
    }
}

@Composable
private fun BubbleContent(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .width(260.dp)
                .shadow(10.dp, RoundedCornerShape(20.dp), clip = false)
                .background(surface, RoundedCornerShape(20.dp))
                // 消费气泡本体点击，避免穿透到全屏 catcher 误触关闭
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            // 确认胶囊：红字 + 次级底色（iOS quaternary 填充的等价）
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(CapsuleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onConfirm)
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    confirmLabel,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
        // 尾尖：从气泡底边向下探入触发行内，指向该行（iOS ArrowDown 18×9）
        Canvas(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 9.dp)
                .width(18.dp)
                .height(9.dp),
        ) {
            val path = Path().apply {
                moveTo(size.width / 2f, size.height)
                lineTo(0f, 0f)
                lineTo(size.width, 0f)
                close()
            }
            drawPath(path, surface)
        }
    }
}

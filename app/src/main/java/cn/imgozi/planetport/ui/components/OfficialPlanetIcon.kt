package cn.imgozi.planetport.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/**
 * 官方行星磁贴图标（iOS 资产 OfficialPlanetIcon：线稿带环行星 · 石绿底，浅深自适应）。
 * 首页磁贴与行星设置页图标行共用（iOS 两处引用同一资产）。约束不改 res 资源
 * 结构，故以 Canvas 复刻（圆角矩形石绿底 + 白描边球体/高光/环带）；后续若引入
 * vector 资产可整体替换本实现。
 */
@Composable
fun OfficialPlanetIcon(modifier: Modifier = Modifier) {
    val bg = if (isSystemInDarkTheme()) Color(0xFF4FC0BF) else Color(0xFF2FA8A7)
    // 圆角按百分比随实际渲染尺寸等比缩放（内部 s*0.24f 同比例）——绝对 dp 在
    // 24dp 小尺寸调用点会被 Skia 压到正圆，丢失 iOS 资产的圆角方贴形态
    Canvas(modifier.clip(RoundedCornerShape(percent = 24))) {
        val s = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        drawRoundRect(color = bg, cornerRadius = CornerRadius(s * 0.24f))
        drawCircle(
            color = Color.White,
            radius = s * 0.26f,
            center = center,
            style = Stroke(width = s * 0.036f),
        )
        drawArc(
            color = Color.White.copy(alpha = 0.35f),
            startAngle = -55f,
            sweepAngle = 105f,
            useCenter = false,
            topLeft = Offset(center.x - s * 0.19f, center.y - s * 0.19f),
            size = Size(s * 0.38f, s * 0.38f),
            style = Stroke(width = s * 0.055f, cap = StrokeCap.Round),
        )
        rotate(-20f, pivot = center) {
            drawOval(
                color = Color.White,
                topLeft = Offset(center.x - s * 0.46f, center.y - s * 0.15f),
                size = Size(s * 0.92f, s * 0.30f),
                style = Stroke(width = s * 0.030f),
            )
        }
    }
}

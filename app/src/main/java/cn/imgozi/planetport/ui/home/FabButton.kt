package cn.imgozi.planetport.ui.home

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.R
import kotlinx.coroutines.launch

/**
 * FAB（iOS FabButton 1:1）：可拖动 + 松手左右吸附 + 位置记忆。
 *
 * - 默认右下（边距 22 / 底距 54，与原固定位置一致）；垂直活动范围头区以下
 *   （topLimit 88）到底部安全距以上；半径 28。
 * - 跟手拖动即时更新；松手吸附较近一侧（spring 阻尼 0.7 的等效口径）。
 * - 位置持久化 SharedPreferences（iOS UserDefaults 等价），x>0 且 y>0 才恢复。
 * - 拖动时缩小 0.95 + 阴影增强（0.25→0.3 / 10→14dp），拿起/放下手感。
 *
 * @param onClick 点击回调（打开加入网络弹层）
 */
@Composable
internal fun FabLayer(onClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fabLabel = stringResource(R.string.fab_addNetwork)

    // 中心点（dp，相对导航栏内层 Box）；null = 未拖动且未恢复 → 默认右下
    var centerX by remember { mutableStateOf<Float?>(null) }
    var centerY by remember { mutableStateOf<Float?>(null) }
    var dragStartX by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    val radius = 28f
    val sideMargin = 22f
    val bottomMargin = 54f
    val topLimit = 88f

    // 拖起/放下缩放（iOS scaleEffect 0.95 + spring 阻尼 0.7）
    val scale by animateFloatAsState(
        targetValue = if (dragging) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
        label = "fabScale",
    )
    // 吸附动画驱动（iOS spring response 0.35 damping 0.7 等效：低刚度阻尼弹簧）
    val animX = remember { Animatable(0f) }
    val animating = remember { mutableStateOf(false) }

    fun clampX(x: Float, w: Float) = x.coerceIn(sideMargin + radius, w - sideMargin - radius)
    fun clampY(y: Float, h: Float) = y.coerceIn(topLimit + radius, h - bottomMargin - radius)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        val boxW = maxWidth.value
        val boxH = maxHeight.value
        if (boxW <= 0f || boxH <= 0f) return@BoxWithConstraints

        // 尺寸就绪后恢复一次（iOS restoreIfNeeded；x>0 且 y>0 才视为有记录）
        if (centerX == null) {
            val sx = readSaved(context, KEY_X)
            val sy = readSaved(context, KEY_Y)
            if (sx > 0f && sy > 0f) {
                centerX = clampX(sx, boxW)
                centerY = clampY(sy, boxH)
            }
        }
        val px = centerX ?: (boxW - sideMargin - radius)
        val py = centerY ?: (boxH - bottomMargin - radius)

        // 拖动跟手：直接用拖动值；吸附动画进行中：用动画值
        val renderX = if (animating.value) animX.value else px
        val renderY = py

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (renderX - radius).dp.roundToPx(),
                        (renderY - radius).dp.roundToPx(),
                    )
                }
                .size(56.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .shadow(
                    elevation = if (dragging) 14.dp else 10.dp,
                    shape = CircleShape,
                    ambientColor = Color.Black.copy(alpha = if (dragging) 0.3f else 0.25f),
                    spotColor = Color.Black.copy(alpha = if (dragging) 0.3f else 0.25f),
                )
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(boxW, boxH) {
                    detectDragGestures(
                        onDragStart = {
                            dragStartX = renderX
                            dragStartY = renderY
                            animating.value = false
                            dragging = true
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            // amount 是单帧增量，必须累加（iOS DragGesture.translation 累计口径）
                            dragStartX += amount.x / density
                            dragStartY += amount.y / density
                            centerX = clampX(dragStartX, boxW)
                            centerY = clampY(dragStartY, boxH)
                        },
                        onDragEnd = {
                            dragging = false
                            val from = centerX
                            val to = centerY
                            if (from != null && to != null) {
                                // 松手水平吸附较近一侧（iOS onEnded 同判定）
                                val snappedX = if (from < boxW / 2) {
                                    sideMargin + radius
                                } else {
                                    boxW - sideMargin - radius
                                }
                                scope.launch {
                                    animating.value = true
                                    animX.snapTo(from)
                                    animX.animateTo(
                                        snappedX,
                                        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
                                    )
                                    animating.value = false
                                }
                                centerX = snappedX
                                save(context, snappedX, to)
                            }
                        },
                        onDragCancel = { dragging = false },
                    )
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .semantics { contentDescription = fabLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

private const val KEY_X = "HomeView.fab.x"
private const val KEY_Y = "HomeView.fab.y"
private const val STORE = "planetport_ui"

/** 位置记忆（iOS UserDefaults 等价）：dp 坐标，无记录返回 0。 */
private fun readSaved(context: Context, key: String): Float =
    context.getSharedPreferences(STORE, Context.MODE_PRIVATE).getFloat(key, 0f)

private fun save(context: Context, x: Float, y: Float) {
    context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        .edit()
        .putFloat(KEY_X, x)
        .putFloat(KEY_Y, y)
        .apply()
}

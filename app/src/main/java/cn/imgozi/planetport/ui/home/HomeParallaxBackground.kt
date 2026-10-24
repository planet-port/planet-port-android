package cn.imgozi.planetport.ui.home

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 首页背景（iOS HomeParallaxBackground 1:1）：自定义背景图 + 页面底色蒙纱
 * （浅 0.4 / 深 0.78，保证身份区文字对比度）+ 重力视差——手机倾斜时背景在
 * 出血余量内平移（最大 ±20dp），前景不动，形成「背景在深处」的层次。
 * 无加速度计的设备（模拟器）自动退化为静止背景。
 *
 * @param image 背景位图（null = 未设置，仅页面底色）
 * @param parallaxEnabled 视差开关（仅 image != null 时有意义）
 */
@Composable
fun HomeParallaxBackground(
    image: android.graphics.Bitmap?,
    parallaxEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (image == null) {
        Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val motion = remember { GravityParallax() }
    val density = LocalDensity.current
    val parallaxRangePx = with(density) { HomeMetrics.parallaxRange.toPx() }

    Box(modifier.fillMaxSize()) {
        // 出血 1.12：图片预放大 12%，偏移在余量内裁剪（最小屏每边 6% ≥ 20dp 上限）
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = HomeMetrics.bleed
                    scaleY = HomeMetrics.bleed
                    translationX = motion.offsetX * parallaxRangePx
                    translationY = motion.offsetY * parallaxRangePx
                },
        )
        // 蒙纱：systemGroupedBackground 等价的页面底色（iOS :38-42）
        val isDark = isSystemInDarkTheme()
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.background
                        .copy(alpha = if (isDark) 0.78f else 0.4f),
                ),
        )
    }

    // 生命周期挂钩：ON_RESUME 起 ON_PAUSE 停（对应 iOS onAppear/onDisappear），
    // 开关变化实时生效（iOS onChange(of: parallaxEnabled)）
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, parallaxEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (parallaxEnabled) motion.start(context)
                Lifecycle.Event.ON_PAUSE -> motion.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            motion.stop()
        }
    }
}

private object HomeMetrics {
    /** 视差最大偏移（iOS parallaxRange 20pt）。 */
    val parallaxRange = 20.dp

    /** 出血比例（iOS bleed 1.12）。 */
    const val bleed = 1.12f
}

/**
 * 重力向量 → 平滑偏移（-1..1）：TYPE_GRAVITY 30Hz，低通滤波 α=0.1，
 * 手机回正时偏移自然回零。仅 start/stop 之间采样，避免后台耗电（iOS GravityMotionModel）。
 */
private class GravityParallax : SensorEventListener {

    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    private var manager: SensorManager? = null
    private var started = false

    fun start(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: return
        if (started) return
        started = true
        manager = sm
        // 采样周期 ≈33ms（30Hz，对齐 iOS deviceMotionUpdateInterval 1/30）
        sm.registerListener(this, sensor, 33_333)
    }

    fun stop() {
        if (!started) return
        manager?.unregisterListener(this)
        manager = null
        started = false
        offsetX = 0f
        offsetY = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GRAVITY) return
        // 手机右倾（gravity.x 增大）→ 背景向左移；y 直接取重力分量（iOS :69-71）
        val targetX = -event.values[0]
        val targetY = event.values[1]
        val alpha = 0.1f
        offsetX += (targetX - offsetX) * alpha
        offsetY += (targetY - offsetY) * alpha
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

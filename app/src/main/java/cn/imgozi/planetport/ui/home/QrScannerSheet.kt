package cn.imgozi.planetport.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.imgozi.planetport.R
import cn.imgozi.planetport.ui.components.SheetScaffold
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码弹层（iOS QRScannerView 等价）：CameraX PreviewView 预览 +
 * ImageAnalysis 逐帧 zxing 解码，[extract] 提取成功即回调关闭
 * （默认 16 位 hex 网络 ID，URL 导入场景传 [QrHttpUrl.extract]）。
 * 相机权限被拒 → photo_camera 图标 + 引导文案 +「前往设置」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScannerSheet(
    onScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    extract: (String) -> String? = QrNetworkId::extract,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
    }

    // 进入即请求（iOS .task checkPermission 同机）；曾拒绝 → 引导态
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 统一 large detent（屏高-16dp）：跳过半展开，直接全高
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetScaffold {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            ) {
                // 头部：左「取消」+ 居中标题（对齐 iOS navigationTitle + cancellationAction）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.qr_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(64.dp))
                }

                if (granted) {
                    ScannerContent(
                        onScanned = onScanned,
                        extract = extract,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                    )
                } else {
                    DeniedGuide(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                    )
                }
            }
        }
    }
}

/** 权限被拒引导态（iOS denied 分支：图标 40 + 标题 + 说明 + 前往设置）。 */
@Composable
private fun DeniedGuide(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.qr_deniedTitle),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.qr_deniedMessage),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                // 应用详情页（iOS openSettingsURLString 等价）；无法处理则忽略
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
            },
        ) { Text(stringResource(R.string.common_goSettings)) }
    }
}

/** 预览 + 逐帧解码；识别到即回调（一次即停）。 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun ScannerContent(
    onScanned: (String) -> Unit,
    extract: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // 「识别一次即停」（iOS stopScanning）：analyzer 线程置位，命中后不再上报
    val stopped = remember { AtomicBoolean(false) }
    // 相机资源句柄（provider 异步就绪后写入）：弹层离组统一解绑 + 回收线程
    val camera = remember { ScannerCamera() }

    DisposableEffect(Unit) {
        onDispose {
            stopped.set(true)
            camera.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = Executors.newSingleThreadExecutor()
            camera.executor = executor
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                        DecodeHintType.TRY_HARDER to true,
                    ),
                )
            }

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                if (stopped.get()) {
                    proxy.close()
                    return@setAnalyzer
                }
                val text = decodeQr(reader, proxy)?.let { extract(it) }
                proxy.close()
                if (text != null && stopped.compareAndSet(false, true)) {
                    mainHandler.post { onScanned(text) }
                }
            }

            ProcessCameraProvider.getInstance(ctx).addListener({
                try {
                    val provider = ProcessCameraProvider.getInstance(ctx).get()
                    // 弹层先于 provider 就绪关闭：已释放则不再绑定（否则绑上后无人解绑，
                    // 隐私指示灯常亮直到下次绑定）
                    if (camera.released) return@addListener
                    camera.provider = provider
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                    // 相机被占/不可用：保持黑底，用户可取消
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}

/**
 * 扫码相机资源句柄：provider 经 getInstance 异步回调持有（不在主线程阻塞 get），
 * 解码 executor 与组合同生命周期——弹层离组 [release] 解绑相机并停线程，
 * 重开扫码层时随组合新建实例，无线程残留。onDispose 与 getInstance 回调
 * 均投递主线程执行，无需加锁。
 */
private class ScannerCamera {
    var released = false
    var provider: ProcessCameraProvider? = null
    var executor: ExecutorService? = null

    fun release() {
        released = true
        provider?.unbindAll()
        provider = null
        executor?.shutdown()
        executor = null
    }
}

/**
 * 从扫码文本提取 16 位十六进制网络 ID（iOS QRScannerRepresentable.extractNetworkId
 * 同构）：内容本身合法直接用（小写），否则找子串——二维码里常是
 * `https://join.zerotier.com/xxxxxxxxxxxxxxxx` 之类。提取不到继续扫。
 */
internal object QrNetworkId {
    private val HEX16 = Regex("[0-9a-f]{16}")

    fun extract(value: String): String? {
        val lowered = value.lowercase()
        if (lowered.length == 16 && lowered.all { it.isDigit() || it in 'a'..'f' }) return lowered
        return HEX16.find(lowered)?.value
    }
}

/**
 * URL 模式提取（iOS QRScannerView.extractHttpUrl 同构）：内容必须是 http(s)
 * 链接，去空白后原样返回；其余内容继续扫。
 */
internal object QrHttpUrl {
    fun extract(value: String): String? {
        val trimmed = value.trim()
        val uri = Uri.parse(trimmed)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        return trimmed
    }
}

/**
 * 单帧解码（zxing）：Y 平面亮度 + 全幅源；传感器帧为横屏行序，竖屏
 * （rotation 90/270）手动旋转 Y 阵转正后解码，未命中返回 null。
 *
 * 注意：[PlanarYUVLuminanceSource] 不支持旋转（isRotateSupported=false，
 * BinaryBitmap.rotateCounterClockwise 会抛 UnsupportedOperationException），
 * 必须自己旋转数据，不能用 BinaryBitmap 的旋转入口。
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun decodeQr(reader: MultiFormatReader, proxy: ImageProxy): String? {
    val plane = proxy.planes[0] ?: return null
    val buffer = plane.buffer ?: return null
    val rotation = proxy.imageInfo.rotationDegrees
    val rotated = rotation == 90 || rotation == 270

    // ByteBuffer → ByteArray（Y 平面亮度）
    var data = ByteArray(buffer.remaining()).also {
        buffer.position(0)
        buffer.get(it)
    }
    var frameW = proxy.width
    var frameH = proxy.height
    if (plane.rowStride != frameW) {
        // 行距含填充（部分设备 rowStride > width）：逐行压缩为紧凑行序
        val packed = ByteArray(frameW * frameH)
        for (row in 0 until frameH) {
            System.arraycopy(data, row * plane.rowStride, packed, row * frameW, frameW)
        }
        data = packed
    }
    if (rotated) {
        // 旋转后宽高互换（90 顺时针 / 270 逆时针，均转至自然竖直方向）
        data = rotateYPlane(data, frameW, frameH, frameW, clockwise = rotation == 90)
        val w = frameW
        frameW = frameH
        frameH = w
    }

    val bitmap = BinaryBitmap(
        HybridBinarizer(
            PlanarYUVLuminanceSource(data, frameW, frameH, 0, 0, frameW, frameH, false),
        ),
    )
    return try {
        reader.decodeWithState(bitmap).text
    } catch (_: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}

/**
 * Y 平面旋转：源宽 [w]、高 [h]（行距 [rowStride]），[clockwise] 为真顺时针
 * 90°，否则逆时针 90°；输出为宽 h、高 w 的紧凑行序数据。
 *
 * 映射推导（源坐标 (x,y)，目标坐标 (x',y')，目标宽 h、高 w）：
 * - 顺时针：dest[y'*h+x'] = src[(h-1-x')*rowStride + y']
 * - 逆时针：dest[y'*h+x'] = src[x'*rowStride + (w-1-y')]
 */
private fun rotateYPlane(
    data: ByteArray,
    w: Int,
    h: Int,
    rowStride: Int,
    clockwise: Boolean,
): ByteArray {
    val out = ByteArray(w * h)
    for (yp in 0 until w) {
        val dstRow = yp * h
        for (xp in 0 until h) {
            val srcIdx = if (clockwise) {
                (h - 1 - xp) * rowStride + yp
            } else {
                xp * rowStride + (w - 1 - yp)
            }
            out[dstRow + xp] = data[srcIdx]
        }
    }
    return out
}

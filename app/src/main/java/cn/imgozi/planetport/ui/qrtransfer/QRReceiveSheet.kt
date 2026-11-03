package cn.imgozi.planetport.ui.qrtransfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.imgozi.planetport.R
import cn.imgozi.planetport.qrtransfer.QRFrame
import cn.imgozi.planetport.qrtransfer.QRTransfer
import cn.imgozi.planetport.qrtransfer.QRTransferException
import cn.imgozi.planetport.ui.components.SheetScaffold
import cn.imgozi.planetport.ui.components.rememberAlbumQrPicker
import cn.imgozi.planetport.ui.home.AlbumErrorBar
import cn.imgozi.planetport.ui.home.DeniedGuide
import cn.imgozi.planetport.ui.home.decodeQr
import cn.imgozi.planetport.ui.theme.LocalPlanetStatusColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 接收端（iOS QRReceiveView）：连续扫码聚合分片。不区分单帧/多帧——解码任意
 * 帧 → 按 index 聚合 → 齐全后整体校验（Base45 → 解压 → CRC）→ 通过即回调一次。
 * total/checksum 与已收集集不一致（扫到新会话）时自动清空重来；CRC 失败同样清空。
 * 相册多选识别的帧文本喂入同一聚合逻辑（相机被拒时相册通路保留）。
 * [onPlainFrame]：非分片帧交给调用方处理（如 URL 码回填导入），接受即结束接收。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QRReceiveSheet(
    onComplete: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
    onPlainFrame: ((String) -> Boolean)? = null,
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
    ) { ok -> granted = ok }

    // 进入即请求（iOS .task checkPermission 同机）；曾拒绝 → 引导态
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 回前台重查权限：「前往设置」授权返回后弹层仍在组合，无此观察者会
    // 一直停在引导态（与 QrScannerSheet 同步补上）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var total by remember { mutableStateOf<Int?>(null) }
    var checksum by remember { mutableStateOf<UInt?>(null) }
    var chunks by remember { mutableStateOf(mapOf<Int, String>()) }
    var corrupt by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    // 相册识别失败原因：无码 / 序列不完整 / 数据格式不正确
    var albumError by remember { mutableStateOf<AlbumScanError?>(null) }

    /** 聚合一帧；返回是否为有效协议帧（相册批次按此计数判定失败原因）。 */
    fun handle(text: String): Boolean {
        if (finished) return false
        val frame = QRTransfer.parse(text) ?: run {
            // 非分片帧：让调用方按普通码处理（URL 回填导入），接受即结束接收
            if (onPlainFrame?.invoke(text) == true) finished = true
            return false
        }
        // 收到有效帧：清除相册失败提示（相机续扫时错误文案不该悬着）
        albumError = null
        // 换了传输会话（total 或 CRC 不同）：丢弃旧集合
        if (total != null && (total != frame.total || checksum != frame.checksum)) {
            chunks = emptyMap()
        }
        total = frame.total
        checksum = frame.checksum
        chunks = chunks + (frame.index to frame.payload)
        corrupt = false
        if (chunks.size != frame.total) return true
        val all = (1..frame.total).mapNotNull { i ->
            chunks[i]?.let { QRFrame(frame.total, i, frame.checksum, frame.length, it) }
        }
        try {
            val data = QRTransfer.decode(all)
            finished = true
            onComplete(data)
        } catch (_: QRTransferException) {
            chunks = emptyMap()
            corrupt = true
        }
        return true
    }

    val albumPicker = rememberAlbumQrPicker { texts ->
        // 逐帧喂现有聚合：分片/校验/会话切换逻辑与相机路径完全同构
        if (texts.isEmpty()) {
            albumError = AlbumScanError.NO_QR
            return@rememberAlbumQrPicker
        }
        // 按本批帧计数判定失败原因，不受相机残留态（已收分片/校验失败）污染
        var parsed = 0
        for (text in texts) if (handle(text)) parsed++
        if (finished) return@rememberAlbumQrPicker
        // 解码失败（corrupt）→ 格式错误；一张协议帧都没有 → 有码但不是本协议
        // 内容；有协议帧但没凑齐 → 序列不完整
        albumError = if (corrupt || parsed == 0) AlbumScanError.INVALID else AlbumScanError.INCOMPLETE
    }
    val pickAlbum = {
        albumError = null
        albumPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                // 头部：左「取消」+ 居中标题 + 右「相册」
                //（对齐 iOS cancellationAction + navigationTitle + confirmationAction）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_cancel))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.qrtransfer_receiveTitle),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = pickAlbum) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = stringResource(R.string.qrtransfer_album),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (granted) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                    ) {
                        ContinuousScanner(onFrame = ::handle, modifier = Modifier.fillMaxSize())
                        // 底部聚合状态面板（iOS ultraThinMaterial 覆盖层）
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                                )
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val t = total
                            if (t != null) {
                                ShardGrid(
                                    total = t,
                                    received = chunks.keys,
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                )
                                Text(
                                    stringResource(
                                        R.string.qrtransfer_receivedProgress,
                                        chunks.size,
                                        t,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // 相册失败提示优先于 corrupt/waiting（iOS albumError 同优先级）
                            val error = albumError
                            if (error != null) {
                                Text(
                                    stringResource(error.res),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalPlanetStatusColors.current.warn,
                                )
                            } else {
                                Text(
                                    if (corrupt) {
                                        stringResource(R.string.qrtransfer_corrupt)
                                    } else {
                                        stringResource(R.string.qrtransfer_waiting)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (corrupt) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                } else {
                    DeniedGuide(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                        onAlbum = pickAlbum,
                    )
                    // 被拒态相册恰是主通路，失败反馈不能只挂在取景分支
                    albumError?.let { AlbumErrorBar(it.res) }
                }
            }
        }
    }
}

/** 相册识别失败原因（iOS QRReceiveView.AlbumScanError 同构）。 */
private enum class AlbumScanError(@StringRes val res: Int) {
    NO_QR(R.string.qrtransfer_albumNoQr),
    INCOMPLETE(R.string.qrtransfer_albumIncomplete),
    INVALID(R.string.qrtransfer_albumInvalid),
}

/**
 * 连续扫码（iOS ContinuousScannerView/Controller）：多帧聚合接收用，不一次即停。
 * 同一内容 800ms 内只回调一次（对准静止的一帧不会刷屏，轮播自然交替放行）。
 * 相机生命周期与 QrScannerSheet.ScannerContent 同构：离组解绑 + 回收线程。
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun ContinuousScanner(
    onFrame: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // 800ms 同值去重（iOS lastValue/lastTime）；仅 analyzer 单线程访问
    val lastValue = remember { arrayOfNulls<String>(1) }
    val lastTime = remember { longArrayOf(0L) }
    // 相机资源句柄（provider 异步就绪后写入）：弹层离组统一解绑 + 回收线程
    val camera = remember { ReceiveCamera() }

    DisposableEffect(Unit) {
        onDispose { camera.release() }
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
            analysis.setAnalyzer(executor) { proxy: ImageProxy ->
                // decodeQr 内部已捕获 NotFoundException 返回 null，未命中即继续
                val text = decodeQr(reader, proxy)
                proxy.close()
                if (text != null) {
                    val now = SystemClock.elapsedRealtime()
                    val dedupe = text == lastValue[0] && now - lastTime[0] < 800
                    lastValue[0] = text
                    lastTime[0] = now
                    if (!dedupe && !camera.released) {
                        mainHandler.post { onFrame(text) }
                    }
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
 * 连续扫码相机资源句柄（QrScannerSheet.ScannerCamera 同构）：provider 经
 * getInstance 异步回调持有（不在主线程阻塞 get），解码 executor 与组合同生命
 * 周期——弹层离组 [release] 解绑相机并停线程。onDispose 与 getInstance 回调
 * 均投递主线程执行，无需加锁。
 */
private class ReceiveCamera {
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

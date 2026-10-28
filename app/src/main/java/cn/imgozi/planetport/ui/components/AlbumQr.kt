package cn.imgozi.planetport.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import cn.imgozi.planetport.store.readBounded
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 相册选图识别二维码（iOS AlbumQRPicker 等价）：photo picker 多选（按选择顺序，
 * 进程外选择器无需相册权限）→ zxing 逐张解码（一张图可能多个码）→ 汇总回调
 * 全部识别文本。相机流识别器（CameraX 逐帧）吃不了静态图片，静态图走
 * 全幅像素解码；两条路径在这里汇合后，识别文本交调用方各自的聚合/提取逻辑
 * （与相机扫到完全同构）。
 *
 * 取消选择不回调（iOS 把取消与无码同报「图片中没有二维码」，Android 侧
 * 优化为静默返回）。
 */
@Composable
fun rememberAlbumQrPicker(onTexts: (List<String>) -> Unit): ManagedActivityResultLauncher<PickVisualMediaRequest, List<Uri>> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 不限选：分片传输协议需要一次选多张凑齐（iOS selectionLimit = 0 同语义）
    return rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // 逐张串行解码（iOS PHPicker 逐张 loadObject 同语义）：一张图解码期间
        // 同时驻留原始字节 + 降采样位图 + 像素数组（峰值数十 MB），全并发多选
        // 直接击穿默认堆，串行即天然限流；顺序汇总与用户选择顺序一致
        scope.launch {
            val texts = uris.map { uri ->
                withContext(Dispatchers.IO) { decodeImageQrs(context, uri) }
            }.flatten()
            onTexts(texts)
        }
    }
}

/** 原始文件读取上限：相册图为不可信来源，超限即断（防超大文件 OOM）。 */
private const val MAX_IMAGE_BYTES = 64 * 1024 * 1024

/** 解码最长边上限：大图降采样后再解（远小码降采样过度会丢失，2048 取平衡）。 */
private const val MAX_DECODE_DIM = 2048

/**
 * 静态图解码全部二维码文本（iOS Vision VNDetectBarcodesRequest 等价，
 * zxing 多码读取器）：有界读入 → 两段式解码（bounds 算 inSampleSize 降采样）
 * → 像素转灰度源 → 多码扫描。QR 取景器特征检测对 90° 倍数旋转天然免疫，
 * 无需额外旋转重试。
 *
 * 整体兜底返回空列表：解码链任何一环失败（读入超限/位图分配 OOM/解码器
 * 意外异常）都不上抛——异常沿 awaitAll 进主线程协程会直接崩溃，空列表由
 * 调用方按「无码」提示（超大图被报成无码属可接受的语义损失）。
 */
private fun decodeImageQrs(context: Context, uri: Uri): List<String> = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBounded(MAX_IMAGE_BYTES) }
        ?: return emptyList()

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return emptyList()

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_DIM) sample = sample shl 1
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return emptyList()

    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    bitmap.recycle()

    val reader = GenericMultipleBarcodeReader(
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        },
    )
    reader.decodeMultiple(
        BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))),
    ).map { it.text }
}.getOrDefault(emptyList())

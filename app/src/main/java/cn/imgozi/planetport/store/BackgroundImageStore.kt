package cn.imgozi.planetport.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首页自定义背景图存储（对齐 iOS BackgroundImageStore）：文件落在 filesDir，
 * 单文件整存整取；内存态经 [state] 发布，首页视差层与设置页缩略图同源消费。
 *
 * 校验口径（iOS :26-33）：≤20MB 且可解码为图片，否则抛 [Error] 子类，
 * 文案由 UI 层映射资源（store 层不依赖 R）。
 */
object BackgroundImageStore {

    /** 图片字节数上限（防超大图占满存储/内存，iOS maxBytes 同值）。 */
    const val MAX_BYTES = 20 * 1024 * 1024

    /** store 层校验失败类型（文案归 UI 层）。 */
    sealed class Error(message: String) : Exception(message) {
        data object TooLarge : Error("too large")
        data object NotImage : Error("not an image")
    }

    /** 首页消费的内存态：位图（null = 未设置）+ 视差开关。 */
    data class State(val bitmap: Bitmap?, val parallax: Boolean)

    private val _state = MutableStateFlow(State(bitmap = null, parallax = true))
    val state: StateFlow<State> = _state.asStateFlow()

    /** 串行化文件读写与位图发布（相册连点/下载竞态）。 */
    private val mutex = Mutex()

    fun file(context: Context): File = File(context.filesDir, "background.img")

    /**
     * 重读磁盘 → 刷新内存态（首页/设置页进入时调用；幂等）。
     * 解码按屏幕尺寸降采样（inSampleSize），全尺寸位图在低端机易 OOM。
     */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val prefs = StoreRepo.store.readPrefs()
            _state.value = State(
                bitmap = decodeFile(context, file(context)),
                parallax = prefs.parallax,
            )
        }
    }

    /**
     * 校验并保存（相册/URL 字节统一走这里），随后刷新内存态。
     * AppPrefs.backgroundImage 布尔由调用方（PrefsViewModel）统一落盘，
     * 本类不写 prefs——单一写方，避免双写分歧。
     */
    suspend fun save(context: Context, data: ByteArray): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (data.size > MAX_BYTES) throw Error.TooLarge
            decodeBytes(context, data) ?: throw Error.NotImage
            file(context).parentFile?.mkdirs()
            file(context).writeBytes(data)
            _state.value = State(bitmap = decodeFile(context, file(context)), parallax = _state.value.parallax)
        }
    }

    /** 清除（无文件时静默成功，对齐 iOS try? removeItem）。 */
    suspend fun clear(context: Context): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            file(context).delete()
            _state.value = State(bitmap = null, parallax = _state.value.parallax)
        }
    }

    /** 视差开关内存态同步（设置页 toggle；落盘仍归 PrefsViewModel），不动文件。 */
    fun syncParallax(on: Boolean) {
        _state.value = _state.value.copy(parallax = on)
    }

    /** 文件 → 降采样位图（缺失/损坏返回 null，等价 iOS UIImage?(data:)）。 */
    private fun decodeFile(context: Context, file: File): Bitmap? =
        if (file.isFile && file.length() > 0 && file.length() <= MAX_BYTES) {
            decodeBytes(context, file.readBytes())
        } else {
            null
        }

    /**
     * 字节 → 按屏幕尺寸降采样的位图：两趟解码（inJustDecodeBounds 先取尺寸，
     * inSampleSize 取不小于目标的最小 2 的幂），任意大图内存封顶约一屏 ARGB_8888。
     */
    private fun decodeBytes(context: Context, data: ByteArray): Bitmap? {
        val metrics = context.resources.displayMetrics
        val targetW = metrics.widthPixels.coerceAtLeast(1)
        val targetH = metrics.heightPixels.coerceAtLeast(1)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetW || bounds.outHeight / (sample * 2) >= targetH) {
            sample *= 2
        }
        return BitmapFactory.decodeByteArray(
            data,
            0,
            data.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }
}

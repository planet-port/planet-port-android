package cn.imgozi.planetport.qrtransfer

import android.graphics.Bitmap
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * zxing 封装（iOS CIQRCodeGenerator 等价）。M 纠错与 iOS 一致；模块数由
 * Encoder 选定的 version 推出（17 + 4v，quiet zone 不计），不依赖内嵌容量表。
 */
internal object QRRender {
    /** 生成位图（scale 放大保证清晰）。返回模块数供密度判断。
     *  黑白码与 iOS 默认渲染一致（深浅色主题均白底黑码）。 */
    fun generate(text: String, scale: Int = 10): Pair<Bitmap, Int>? = try {
        val qr = Encoder.encode(text, ErrorCorrectionLevel.M, null)
        val w = qr.matrix.width
        val px = w * scale
        val pixels = IntArray(px * px) { i ->
            val x = i % px
            val y = i / px
            if (qr.matrix.get(x / scale, y / scale) == 1.toByte()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888) to w
    } catch (_: WriterException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** 文本能否生成模块数 ≤ maxModules 的二维码（探测用，不出位图）。 */
    fun fits(text: String, maxModules: Int): Boolean = try {
        Encoder.encode(text, ErrorCorrectionLevel.M, null).version.versionNumber * 4 + 17 <= maxModules
    } catch (_: WriterException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}

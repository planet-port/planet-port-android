package cn.imgozi.planetport.qrtransfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * iOS 跨端金标向量：由 iOS 仓库 QRTransfer 模块源码经 swiftc 在 macOS 编译运行生成
 * （sampleData(300) = ByteArray { ((it * 31 + 7) % 256) }，encode(maxModules = 117)）。
 * 锁死两端协议互通：iOS 产的帧与压缩容器 Android 必须能解，且 Android 编码结果逐帧一致。
 */
class GoldenVectorTest {

    private fun sampleData(size: Int): ByteArray =
        ByteArray(size) { ((it * 31 + 7) % 256).toByte() }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((Character.digit(hex[it * 2], 16) shl 4) or Character.digit(hex[it * 2 + 1], 16)).toByte() }

    /** iOS QRTransfer.encode(sampleData(300), maxModules: 117) 的原始帧输出 */
    private val iosFrames = listOf(
        "ZTQR1|01|01|226c6629|00419|FP50006PC.PEJEDBZEZKO6E7-EJSCBA4E:-2*T1%2KFPU5QFR\$BK-TN%I\$2KPKL1Q4FW5\$CEG8KSZ9C1RYY3S9O8LJP.22*RS5WW8M+3F9CT:ZSN7BXZJJD3\$-T4CVV2PTOL*6P\$PDTZ8LPGV05+38-:I\$VRNUMLPV16L+PPUEH+\$U*2SOL4KV2OB6RZRV164-83Y0L T+5O 7R%\$P33CD%UPVMUOCL3W%.NL/F*CBWXIQXRX1TGBI+68LDS1O6RINV 79YRG%38*P8FTU1CQ6FH0U3LIBMSBQA1YB5R4%HSCCR*8TGL5KY1Y%MMX4K.3XI5\$BPX6QF-F:7LEFF NTQ:P.\$7IKTA3T*YL12V3Q81Z5.*C-IKTS1A0FMZ6+6FF*VC-D5IB2IF2-OE-V72O21S/.907CSNO00"
    )

    /** iOS Deflate.compress(sampleData(300)) 的容器 hex（4 字节小端长度 + raw deflate） */
    private val iosContainerHex =
        "2c0100006357734d695e74f0c17f39db98ea593b6f7c17330d299eb4f1c27b3e5d9fecae95279eb3a9ba24372d3c70ff9fac4d74d5cc1dd7bf899a04174ddc70fe1daf8e7756e78ae3cf58559c931a17ecbff757c63aaa72c6f66b5f458c830a27ac3ff79647db2bb363f9b1a72cca4e890df3f7ddfd236d1559317ddbd52fc2468105fdebcebee1d6f2cc685f76f409b3926342fdbcbd777e4b5946944fdb7ae5b39061407edfda33afb9343dd2db961e79cca4e8105f3777cfed5f9216e16553b75cfe2468e09fd7bbe6f42b4e0df7b4d625871f312ad8c7d5ced97deba7847958e994cd973e0ae8fbe5f6ac3ef59243dd2db565f1a1870cf276b135b377ddfc216e165a3279d3c50ffc7abe39ddab4ebe6027c1ff00"

    @Test
    fun iosFramesDecodeOnAndroid() {
        val parsed = iosFrames.mapNotNull { QRTransfer.parse(it) }
        assertEquals(iosFrames.size, parsed.size)
        assertArrayEquals(sampleData(300), QRTransfer.decode(parsed))
    }

    @Test
    fun iosCompressedContainerDecompressOnAndroid() {
        assertArrayEquals(sampleData(300), Deflate.decompress(hexToBytes(iosContainerHex)))
    }

    @Test
    fun androidEncodeMatchesIosGoldenFrameByFrame() {
        val android = QRTransfer.encode(sampleData(300), maxModules = 117)
        assertEquals(iosFrames, android)
    }
}

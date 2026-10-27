# iOS QRTransfer 等新增功能全量迁移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 planet-port-ios dev `60d49f7` 相对 Android 同步点（iOS `02a901b`）的全部新增功能 1:1 迁移到 Android dev 分支。

**Architecture:** 协议层为纯 Kotlin 包 `cn.imgozi.planetport.qrtransfer`（Base45/CRC32/Deflate/ZTQR1 帧协议/zxing 渲染探测），与 iOS 字节兼容；UI 层 `ui/qrtransfer` 三个组件（分享弹层/接收弹层/分片方块阵列），呈现沿用 ModalBottomSheet 惯例；接入行星设置页分享行、新增页动态二维码导入与 5 分钟表单清空；另补关于页构建信息行与托管路由行切换两项漏同步。

**Tech Stack:** Kotlin 2.2 / Jetpack Compose（BOM 2025.12.01）/ CameraX 1.4.2 + zxing 3.5.3（均在依赖内，零新增运行时依赖）/ JUnit 4（新增 testImplementation）

**设计文档：** `docs/superpowers/specs/2026-10-27-ios-qrtransfer-migration-design.md`
**iOS 参考源码：** `/Users/zivyuan/GoziLib/Zerotier/planet-port-ios/Modules/QRTransfer/Sources/`（只读）

## Global Constraints

- 协议字节兼容是底线：Base45 字母表 `0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:`、CRC-32（IEEE，反射 0xEDB88320）、压缩容器 = 4 字节小端原始长度 + raw deflate（无 zlib 头）、帧格式 `ZTQR1|total|index|crc32|len|chunk`（定长头 27 字符）
- 二维码纠错级别 M（与 iOS 一致）；帧数上限 32；len ≤ 99999
- 提交信息遵循 Conventional Commits 中文规范，禁止 Co-authored-by 尾注
- 三语文案：values（zh-Hans）/ values-en / values-zh-rTW 同步新增
- 不新增运行时依赖；仅新增 JUnit 测试依赖
- 提交时机：每个 Task 完成且验证通过后一次提交

---

### Task 1: 测试基座 + 编解码原语（Base45 / CRC32 / Deflate）

**Files:**
- Modify: `gradle/libs.versions.toml`（junit 版本与库条目）
- Modify: `app/build.gradle.kts`（testImplementation）
- Create: `app/src/main/java/cn/imgozi/planetport/qrtransfer/Base45.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/qrtransfer/CRC32.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/qrtransfer/Deflate.kt`
- Test: `app/src/test/java/cn/imgozi/planetport/qrtransfer/CodecTest.kt`

**Interfaces:**
- Produces: `internal object Base45 { fun encode(data: ByteArray): String; fun decode(s: String): ByteArray? }`
- Produces: `internal object CRC32 { fun checksum(data: ByteArray): UInt }`
- Produces: `internal object Deflate { fun compress(data: ByteArray): ByteArray?; fun decompress(container: ByteArray): ByteArray? }`

- [ ] **Step 1: 添加 JUnit 依赖**

`gradle/libs.versions.toml` 的 `[versions]` 末尾加：

```toml
junit = "4.13.2"
```

`[libraries]` 末尾加：

```toml
# 协议层单元测试（QRTransfer 迁移）
junit = { group = "junit", name = "junit", version.ref = "junit" }
```

`app/build.gradle.kts` 的 `dependencies` 末尾加：

```kotlin
    testImplementation(libs.junit)
```

- [ ] **Step 2: 写失败测试**

创建 `app/src/test/java/cn/imgozi/planetport/qrtransfer/CodecTest.kt`：

```kotlin
package cn.imgozi.planetport.qrtransfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS QRTransferCodecTests 1:1 移植：编解码往返与已知向量。 */
class CodecTest {

    @Test
    fun base45KnownVectors() {
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69", Base45.encode("He".toByteArray()))
    }

    @Test
    fun base45RoundTrip() {
        // 覆盖奇偶长度与较长数据
        for (size in intArrayOf(1, 2, 3, 31, 32, 100, 1024)) {
            val data = ByteArray(size) { ((it * 7 + 13) % 256).toByte() }
            assertArrayEquals(data, Base45.decode(Base45.encode(data)))
        }
    }

    @Test
    fun base45RejectsBadInput() {
        assertNull(Base45.decode("abc|")) // '|' 不在字母表
        assertNull(Base45.decode("A"))    // 落单 1 字符不合法
        assertNull(Base45.decode("::::")) // 解出值 > 0xFFFF
    }

    @Test
    fun crc32KnownVectors() {
        assertEquals(0u, CRC32.checksum(ByteArray(0)))
        assertEquals(0xCBF43926u, CRC32.checksum("123456789".toByteArray()))
    }

    @Test
    fun deflateRoundTripAndContainer() {
        val data = "planet".repeat(200).toByteArray()
        val container = Deflate.compress(data)!!
        // 容器头 4 字节 = 原始长度小端
        val len = (container[0].toInt() and 0xFF) or
            ((container[1].toInt() and 0xFF) shl 8) or
            ((container[2].toInt() and 0xFF) shl 16) or
            ((container[3].toInt() and 0xFF) shl 24)
        assertEquals(data.size, len)
        assertArrayEquals(data, Deflate.decompress(container))
        assertNull(Deflate.decompress(ByteArray(4)))
    }
}
```

- [ ] **Step 3: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.imgozi.planetport.qrtransfer.CodecTest" 2>&1 | tail -20`
Expected: 编译失败（unresolved reference Base45/CRC32/Deflate）

- [ ] **Step 4: 实现三个原语**

`Base45.kt`：

```kotlin
package cn.imgozi.planetport.qrtransfer

/** Base45（RFC 9285 字母表）。字母表恰好是 QR alphanumeric 全集且不含 "|"，
 * 分片协议可以安全用 "|" 分隔。编解码两端都是本模块，自洽即可。 */
internal object Base45 {
    val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:".toCharArray()
    private val index = HashMap<Char, Int>(alphabet.size).apply {
        alphabet.forEachIndexed { i, c -> put(c, i) }
    }

    fun encode(data: ByteArray): String {
        val sb = StringBuilder(data.size * 3 / 2 + 2)
        var i = 0
        while (i < data.size) {
            if (data.size - i == 1) {
                // 落单字节：两位
                val v = data[i].toInt() and 0xFF
                sb.append(alphabet[v % 45])
                sb.append(alphabet[v / 45])
                i += 1
            } else {
                val v = (data[i].toInt() and 0xFF shl 8) or (data[i + 1].toInt() and 0xFF)
                sb.append(alphabet[v % 45])
                sb.append(alphabet[v / 45 % 45])
                sb.append(alphabet[v / 45 / 45])
                i += 2
            }
        }
        return sb.toString()
    }

    fun decode(s: String): ByteArray? {
        val chars = s.toCharArray()
        if (chars.isEmpty()) return null
        val out = ArrayList<Byte>(chars.size * 2 / 3)
        var i = 0
        while (i < chars.size) {
            val remaining = chars.size - i
            if (remaining >= 3) {
                val c = index[chars[i]] ?: return null
                val d = index[chars[i + 1]] ?: return null
                val e = index[chars[i + 2]] ?: return null
                val v = c + d * 45 + e * 2025
                if (v > 0xFFFF) return null
                out.add((v shr 8).toByte())
                out.add((v and 0xFF).toByte())
                i += 3
            } else if (remaining == 2) {
                val c = index[chars[i]] ?: return null
                val d = index[chars[i + 1]] ?: return null
                val v = c + d * 45
                if (v > 0xFF) return null
                out.add(v.toByte())
                i += 2
            } else {
                return null // 编码不可能产生落单 1 字符
            }
        }
        return out.toByteArray()
    }
}
```

`CRC32.kt`：

```kotlin
package cn.imgozi.planetport.qrtransfer

/** 标准 CRC-32（IEEE 802.3，反射多项式 0xEDB88320）。查表由代码生成，不硬编码 256 项。 */
internal object CRC32 {
    private val table = IntArray(256).also { tbl ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 1 == 1) 0xEDB88320 xor (c ushr 1) else c ushr 1 }
            tbl[i] = c
        }
    }

    fun checksum(data: ByteArray): UInt {
        var crc = -1
        for (b in data) {
            crc = table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
        }
        return (crc xor -1).toUInt()
    }
}
```

`Deflate.kt`：

```kotlin
package cn.imgozi.planetport.qrtransfer

import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 压缩容器：4 字节小端原始长度 + raw deflate 流（iOS COMPRESSION_ZLIB 同为
 * 无 zlib 头的 raw deflate，跨端字节互解）。自带长度使得解压侧可以直接分配
 * 目标缓冲，不依赖外部传尺寸。
 */
internal object Deflate {
    fun compress(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            // 不可压缩数据的膨胀上界（块开销）给足余量
            val buf = ByteArray(data.size + data.size / 64 + 64)
            val n = deflater.deflate(buf)
            if (n <= 0) return null
            val len = data.size
            return ByteArray(4 + n).also {
                it[0] = (len and 0xFF).toByte()
                it[1] = ((len shr 8) and 0xFF).toByte()
                it[2] = ((len shr 16) and 0xFF).toByte()
                it[3] = ((len shr 24) and 0xFF).toByte()
                System.arraycopy(buf, 0, it, 4, n)
            }
        } finally {
            deflater.end()
        }
    }

    fun decompress(container: ByteArray): ByteArray? {
        if (container.size <= 4) return null
        val len = (container[0].toInt() and 0xFF) or
            ((container[1].toInt() and 0xFF) shl 8) or
            ((container[2].toInt() and 0xFF) shl 16) or
            ((container[3].toInt() and 0xFF) shl 24)
        if (len <= 0 || len >= 16 * 1024 * 1024) return null
        val inflater = Inflater(true)
        return try {
            inflater.setInput(container, 4, container.size - 4)
            val out = ByteArray(len)
            val n = inflater.inflate(out)
            if (n != len || !inflater.finished()) null else out
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.imgozi.planetport.qrtransfer.CodecTest" 2>&1 | tail -5`
Expected: 5 个测试全 PASS

- [ ] **Step 6: 提交**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/cn/imgozi/planetport/qrtransfer/ app/src/test/
git commit -m "feat(qrtransfer): 新增编解码原语 Base45/CRC32/Deflate

ZTQR1 传输协议的三个原语层，与 iOS QRTransfer 模块逐行对齐：
Base45（RFC 9285 字母表）、CRC-32（IEEE 反射 0xEDB88320）、压缩
容器（4 字节小端原始长度 + raw deflate，java.util.zip 实现，iOS
COMPRESSION_ZLIB 同格式跨端互解）。新建项目首个单元测试源集，
移植 iOS 编解码测试 5 例。"
```

---

### Task 2: ZTQR1 帧协议 + 二维码渲染探测（QRFrame / QRTransfer / ChunkPlanner / QRRender）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/qrtransfer/QRFrame.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/qrtransfer/QRTransfer.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/qrtransfer/ChunkPlanner.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/qrtransfer/QRRender.kt`
- Test: `app/src/test/java/cn/imgozi/planetport/qrtransfer/FrameTest.kt`

**Interfaces:**
- Consumes: Task 1 的 Base45/CRC32/Deflate
- Produces:
  - `internal data class QRFrame(total: Int, index: Int, checksum: UInt, length: Int, payload: String)`
  - `internal sealed class QRTransferException(message: String) : Exception(message)`，子类 `TooLarge`/`Incomplete`/`Corrupt`（object 单例）
  - `internal object QRTransfer { const val magic; const val headerLen; fun recommendedMaxModules(sideDp: Float): Int; fun encode(data: ByteArray, maxModules: Int = 117, maxFrames: Int = 32): List<String>; fun parse(text: String): QRFrame?; fun decode(frames: List<QRFrame>): ByteArray }`
  - `internal object ChunkPlanner { const val probePrefix: String; fun capacity(payloadCount: Int, headerLen: Int, maxModules: Int): Int? }`
  - `internal object QRRender { fun generate(text: String, scale: Int = 10): Pair<android.graphics.Bitmap, Int>?; fun fits(text: String, maxModules: Int): Boolean }`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/cn/imgozi/planetport/qrtransfer/FrameTest.kt`：

```kotlin
package cn.imgozi.planetport.qrtransfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS QRTransferFrameTests 1:1 移植：帧协议编解码与容量探测。 */
class FrameTest {

    private fun sampleData(size: Int): ByteArray =
        ByteArray(size) { ((it * 31 + 7) % 256).toByte() }

    /** xorshift 伪随机字节，近不可压缩。sampleData 以 256 字节为周期，
     * deflate 可压到几百字节，无法触发 tooLarge。 */
    private fun noisyData(size: Int): ByteArray {
        var state = 0x9E3779B97F4A7C15uL
        return ByteArray(size) {
            state = state xor (state shl 13)
            state = state xor (state shr 7)
            state = state xor (state shl 17)
            state.toByte()
        }
    }

    @Test
    fun probePrefixMatchesHeaderLen() {
        assertEquals(QRTransfer.headerLen, ChunkPlanner.probePrefix.length)
    }

    @Test
    fun singleFrameRoundTrip() {
        val data = sampleData(300)
        val frames = QRTransfer.encode(data, maxModules = 117)
        assertEquals(1, frames.size)
        assertTrue(QRRender.fits(frames[0], maxModules = 117))
        assertTrue(data.contentEquals(QRTransfer.decode(frames.mapNotNull { QRTransfer.parse(it) })))
    }

    @Test
    fun multiFrameUniformPaddedAndRestored() {
        val data = sampleData(2000)
        // 53 模块（v9）小容量强制多帧
        val frames = QRTransfer.encode(data, maxModules = 53)
        assertTrue(frames.size > 1)
        // 每帧数据段字符数严格相等（无法整除时末帧补 '0' 对齐）
        val chunkLens = frames.map { it.split("|", limit = 6)[5].length }.toSet()
        assertEquals(1, chunkLens.size)
        // 每一帧真实形态都不得超过容量上限（探测失真回归防护）
        frames.forEach {
            assertTrue("帧超出 53 模块：长度 ${it.length} 字符", QRRender.fits(it, maxModules = 53))
        }
        // 剥补位后完整还原
        assertTrue(data.contentEquals(QRTransfer.decode(frames.mapNotNull { QRTransfer.parse(it) })))
    }

    @Test
    fun tooLargeThrows() {
        var thrown: QRTransferException? = null
        try {
            QRTransfer.encode(noisyData(200_000), maxModules = 53)
        } catch (e: QRTransferException) {
            thrown = e
        }
        assertTrue(thrown is QRTransferException.TooLarge)
    }

    @Test
    fun parseRejectsBadInput() {
        assertNull(QRTransfer.parse("hello world"))
        assertNull(QRTransfer.parse("ZTQR1|02|03|deadbeef|00123|xx")) // index > total
        assertNull(QRTransfer.parse("ZTQR1|2|01|deadbeef|00123|xx"))  // 位数不足
        assertNull(QRTransfer.parse("ZTQR1|02|01|deadbeef|0123|xx"))  // len 位数不足
    }

    @Test
    fun decodeDetectsMissingAndCorrupt() {
        val data = sampleData(500)
        val frames = QRTransfer.encode(data, maxModules = 53)
        val parsed = frames.mapNotNull { QRTransfer.parse(it) }
        // 缺帧 → incomplete
        var thrown: QRTransferException? = null
        try {
            QRTransfer.decode(parsed.dropLast(1))
        } catch (e: QRTransferException) {
            thrown = e
        }
        assertTrue(thrown is QRTransferException.Incomplete)
        // 篡改校验和 → corrupt（CRC 兜底拦截）
        val tampered = QRFrame(parsed[0].total, parsed[0].index, 0u, parsed[0].length, parsed[0].payload)
        thrown = null
        try {
            QRTransfer.decode(parsed.drop(1) + tampered)
        } catch (e: QRTransferException) {
            thrown = e
        }
        assertTrue(thrown is QRTransferException.Corrupt)
    }
}
```

- [ ] **Step 2: 跑测试确认编译失败**

Run: `./gradlew :app:testDebugUnitTest --tests "cn.imgozi.planetport.qrtransfer.FrameTest" 2>&1 | tail -10`
Expected: 编译失败（unresolved reference QRTransfer/ChunkPlanner/QRRender）

- [ ] **Step 3: 实现帧协议与渲染**

`QRFrame.kt`：

```kotlin
package cn.imgozi.planetport.qrtransfer

/** 单帧结构。text 即二维码内容：ZTQR1|total|index|crc32|len|chunk
 * total/index 为两位十进制（1..99，index 从 1 起），crc32 为原始数据的 CRC、
 * len 为 Base45 全长（真实字符数，每帧重复携带），均用于接收端自描述恢复。 */
internal data class QRFrame(
    val total: Int,
    val index: Int,
    val checksum: UInt,
    val length: Int,
    /** Base45 分片字符串（末帧含补 '0' 对齐） */
    val payload: String,
)

internal sealed class QRTransferException(message: String) : Exception(message) {
    data object TooLarge : QRTransferException("too large")
    data object Incomplete : QRTransferException("incomplete")
    data object Corrupt : QRTransferException("corrupt")
}
```

`QRTransfer.kt`：

```kotlin
package cn.imgozi.planetport.qrtransfer

/** 传输编解码入口。数据 → 压缩容器 → Base45 → 均匀分片（末帧补 '0' 对齐）→ 帧字符串数组。 */
internal object QRTransfer {
    const val magic = "ZTQR1"
    /** "ZTQR1|" + 2 + "|" + 2 + "|" + 8 + "|" + 5 + "|" = 27 */
    const val headerLen = magic.length + 1 + 2 + 1 + 2 + 1 + 8 + 1 + 5 + 1

    /** 屏幕可用边长（dp）→ 二维码模块数上限。模块 ≥ 2.5dp、quiet zone 4 模块、
     * 硬顶 v25（117 模块）、下限 v9（53 模块）防极小屏。 */
    fun recommendedMaxModules(sideDp: Float): Int =
        maxOf(53, minOf(117, (sideDp / 2.5f).toInt() - 8))

    fun encode(data: ByteArray, maxModules: Int = 117, maxFrames: Int = 32): List<String> {
        val container = Deflate.compress(data) ?: throw QRTransferException.Corrupt
        val encoded = Base45.encode(container)
        val capacity = ChunkPlanner.capacity(encoded.length, headerLen, maxModules)
            ?.takeIf { it >= 1 } ?: throw QRTransferException.TooLarge
        val frameCount = (encoded.length + capacity - 1) / capacity
        if (frameCount > maxFrames) throw QRTransferException.TooLarge
        // 均匀分片：等宽 chunk；无法整除时补 '0' 对齐，
        // 全序列每帧字符数严格相等 → 二维码模块数一致
        val chunkSize = (encoded.length + frameCount - 1) / frameCount
        val crc = CRC32.checksum(data)
        val frames = ArrayList<String>(frameCount)
        var start = 0
        for (i in 1..frameCount) {
            val end = minOf(start + chunkSize, encoded.length)
            var chunk = encoded.substring(start, end)
            if (chunk.length < chunkSize) chunk += "0".repeat(chunkSize - chunk.length)
            frames.add(
                "$magic|${"%02d".format(frameCount)}|${"%02d".format(i)}|" +
                    "${"%08x".format(crc)}|${"%05d".format(encoded.length)}|$chunk",
            )
            start = end
        }
        return frames
    }

    fun parse(text: String): QRFrame? {
        val parts = text.split("|", limit = 6)
        if (parts.size != 6 || parts[0] != magic) return null
        if (parts[1].length != 2 || parts[2].length != 2 ||
            parts[4].length != 5 || parts[3].length != 8
        ) return null
        val total = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        val length = parts[4].toIntOrNull() ?: return null
        val crc = parts[3].toUIntOrNull(16) ?: return null
        if (total < 1 || index < 1 || index > total || length < 1 || length > 99_999) return null
        return QRFrame(total, index, crc, length, parts[5])
    }

    /** 帧集合齐全且 CRC 通过才返回数据。 */
    fun decode(frames: List<QRFrame>): ByteArray {
        val first = frames.firstOrNull() ?: throw QRTransferException.Incomplete
        val total = first.total
        if (frames.size != total ||
            frames.map { it.index }.toSet().size != total ||
            frames.any { it.total != total }
        ) throw QRTransferException.Incomplete
        // 校验和不一致说明帧混自不同文档，属数据损坏而非缺帧
        if (frames.any { it.checksum != first.checksum }) throw QRTransferException.Corrupt
        val joined = frames.sortedBy { it.index }.joinToString("") { it.payload }
        // 先按 len 剥掉末帧补位，再进 Base45/解压/CRC 三重校验
        if (joined.length < first.length) throw QRTransferException.Corrupt
        val trimmed = joined.take(first.length)
        val container = Base45.decode(trimmed) ?: throw QRTransferException.Corrupt
        val data = Deflate.decompress(container) ?: throw QRTransferException.Corrupt
        if (CRC32.checksum(data) != first.checksum) throw QRTransferException.Corrupt
        return data
    }
}
```

`ChunkPlanner.kt`：

```kotlin
package cn.imgozi.planetport.qrtransfer

/**
 * 每帧容量探测：二分查找最大的「分片字符数」，使 真实形态定长头+该长度探测串
 * 生成的二维码模块数 ≤ maxModules。探测串必须携带真实帧头（含 '|'，byte mode）：
 * 纯 "0" 会走 numeric mode（3.33 bit/字符），导致容量高估近一倍。
 */
internal object ChunkPlanner {
    /** 与真实帧头等形等长（length == QRTransfer.headerLen == 27）
     * internal 而非 private：FrameTest 需断言两者长度一致（帧格式失配防护） */
    const val probePrefix = "ZTQR1|99|99|ffffffff|00000|"

    fun capacity(payloadCount: Int, headerLen: Int, maxModules: Int): Int? {
        var lo = 1
        var hi = maxOf(payloadCount, 1)
        var best: Int? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            // 尾部用小写 'z' 填充：裸数字会被编码器切 numeric segment 再次低估，
            // 非字母数字字符强制 byte mode（8 bit/字符）即最坏情况上界，
            // 真实分片的任何分段优化只会比探测更省模块
            val probe = probePrefix + "z".repeat(mid)
            if (QRRender.fits(probe, maxModules)) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }
}
```

`QRRender.kt`：

```kotlin
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
            if (qr.matrix.get(x / scale, y / scale) == 1) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888) to w
    } catch (_: WriterException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** 文本能否生成模块数 ≤ maxModules 的二维码（探测用，不出位图）。 */
    fun fits(text: String, maxModules: Int): Boolean = try {
        Encoder.encode(text, ErrorCorrectionLevel.M, null).version * 4 + 17 <= maxModules
    } catch (_: WriterException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
}
```

说明：帧串必含 `|`（不在 QR alphanumeric 字符集），zxing 与 CoreImage 一样整串走 byte mode，探测与真实帧编码路径一致。

- [ ] **Step 4: 跑测试确认通过**

Run: `./gradlew :app:testDebugUnitTest 2>&1 | tail -5`
Expected: CodecTest 5 例 + FrameTest 6 例全 PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/cn/imgozi/planetport/qrtransfer/ app/src/test/
git commit -m "feat(qrtransfer): 实现 ZTQR1 帧协议与自适应分片规划

数据 → 压缩容器 → Base45 → 均匀分片（末帧补 0 对齐保证每帧模块
数一致）→ 帧字符串数组；接收端齐全后剥补位 + 三重校验（Base45/
解压/CRC）。每帧容量二分探测，探测串携带真实帧头并以小写 z 填充
强制 byte mode 最坏情形。二维码渲染与容量探测复用 zxing Encoder
（M 纠错，模块数 = 17+4v），等价 iOS CIQRCodeGenerator extent 探
测。移植 iOS 帧协议测试 6 例。"
```

---

### Task 3: 分片方块阵列 + 二维码分享弹层（ShardGrid / QRShareSheet）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/ui/qrtransfer/ShardGrid.kt`
- Create: `app/src/main/java/cn/imgozi/planetport/ui/qrtransfer/QRShareSheet.kt`
- Modify: `app/src/main/res/values/strings.xml`、`values-en/strings.xml`、`values-zh-rTW/strings.xml`（qrtransfer 分享侧文案）

**Interfaces:**
- Consumes: Task 2 的 `QRTransfer.encode/recommendedMaxModules`、`QRRender.generate`、`QRTransferException`
- Produces: `@Composable internal fun ShardGrid(total: Int, current: Int? = null, received: Set<Int> = emptySet(), onTap: ((Int) -> Unit)? = null, modifier: Modifier = Modifier)`；`@Composable internal fun QRShareSheet(title: String, data: ByteArray, onDismiss: () -> Unit)`

- [ ] **Step 1: 添加分享侧文案**

三语 strings.xml 各加（保持文件内按键分组惯例，放在合适位置）：

values/strings.xml:
```xml
    <string name="qrtransfer_done">完成</string>
    <string name="qrtransfer_generating">正在生成二维码…</string>
    <string name="qrtransfer_generatingProgress">正在生成二维码… %1$d/%2$d</string>
    <string name="qrtransfer_manual">手动模式</string>
    <string name="qrtransfer_speed">切换速度</string>
    <string name="qrtransfer_tooLarge">数据过大</string>
    <string name="qrtransfer_tooLargeHint">超出二维码传输上限，请改用文件或 URL 分享</string>
```
values-en/strings.xml:
```xml
    <string name="qrtransfer_done">Done</string>
    <string name="qrtransfer_generating">Generating QR code…</string>
    <string name="qrtransfer_generatingProgress">Generating QR code… %1$d/%2$d</string>
    <string name="qrtransfer_manual">Manual</string>
    <string name="qrtransfer_speed">Speed</string>
    <string name="qrtransfer_tooLarge">Data too large</string>
    <string name="qrtransfer_tooLargeHint">Exceeds the QR transfer limit. Share via file or URL instead.</string>
```
values-zh-rTW/strings.xml:
```xml
    <string name="qrtransfer_done">完成</string>
    <string name="qrtransfer_generating">正在產生二維碼…</string>
    <string name="qrtransfer_generatingProgress">正在產生二維碼… %1$d/%2$d</string>
    <string name="qrtransfer_manual">手動模式</string>
    <string name="qrtransfer_speed">切換速度</string>
    <string name="qrtransfer_tooLarge">資料過大</string>
    <string name="qrtransfer_tooLargeHint">超出二維碼傳輸上限，請改用檔案或 URL 分享</string>
```

- [ ] **Step 2: 实现 ShardGrid**

`ShardGrid.kt`：

```kotlin
package cn.imgozi.planetport.ui.qrtransfer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 分片方块阵列（iOS ShardGridView）。两种形态：
 * 发送端：[current] 高亮当前帧，[onTap] 非空时可点跳转（手动模式）。
 * 接收端：[received] 填充已收格，未收格描边强调缺片。
 * 块上直接写分片号，替代独立的数字分片提示。
 * 布局为流式换行、每行居中，方块定宽不撑满整行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShardGrid(
    total: Int,
    current: Int? = null,
    received: Set<Int> = emptySet(),
    onTap: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        for (i in 1..total) {
            val isCurrent = current == i
            val isReceived = i in received
            // current==null 即接收端形态：未收格描边
            val isMissing = current == null && !isReceived
            val bg = when {
                isCurrent -> MaterialTheme.colorScheme.primary
                isReceived -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(width = 30.dp, height = 22.dp)
                    .background(bg, RoundedCornerShape(6.dp))
                    .then(
                        if (isMissing) {
                            Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp),
                            )
                        } else Modifier,
                    )
                    .then(if (onTap != null) Modifier.clickable { onTap(i) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$i",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
```

- [ ] **Step 3: 实现 QRShareSheet**

`QRShareSheet.kt`：

```kotlin
package cn.imgozi.planetport.ui.qrtransfer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.imgozi.planetport.R
import cn.imgozi.planetport.qrtransfer.QRRender
import cn.imgozi.planetport.qrtransfer.QRTransfer
import cn.imgozi.planetport.qrtransfer.QRTransferException
import cn.imgozi.planetport.ui.components.SheetScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 分享端（iOS QRShareView）：展示分片二维码序列。单帧退化为静态码（无控件）；
 * 多帧提供速度四档与手动模式（点方块阵列直接跳帧）。编码与渲染均在后台
 * 线程逐张完成，首张就绪即显示，避免长时间空白等待。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QRShareSheet(
    title: String,
    data: ByteArray,
    onDismiss: () -> Unit,
) {
    var frames by remember { mutableStateOf(listOf<String>()) }
    var images by remember { mutableStateOf(listOf<Bitmap>()) }
    var tooLarge by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf(0) }
    var speed by remember { mutableStateOf(0.5) }
    var manual by remember { mutableStateOf(false) }
    val speeds = remember { listOf(0.3, 0.5, 0.8, 1.2) }
    // 屏宽 dp（iOS UIScreen.main.bounds 等价）
    val sideDp = LocalConfiguration.current.screenWidthDp * 0.72f

    // 后台编码分片，再逐张后台渲染、逐张回填显示
    LaunchedEffect(data) {
        val maxModules = QRTransfer.recommendedMaxModules(sideDp)
        try {
            val encoded = withContext(Dispatchers.Default) { QRTransfer.encode(data, maxModules) }
            frames = encoded
            index = 0
            for (frame in encoded) {
                val image = withContext(Dispatchers.Default) { QRRender.generate(frame)?.first }
                if (image != null) images = images + image
            }
        } catch (_: QRTransferException) {
            tooLarge = true
        }
    }

    // 渲染全部就绪后才允许自动播放启动（key 与 iOS .task(id:) 一致）
    LaunchedEffect(manual, speed, frames.size, images.size) {
        if (manual || frames.size <= 1 || images.size != frames.size) return@LaunchedEffect
        while (true) {
            delay((speed * 1000).toLong())
            index = (index + 1) % frames.size
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetScaffold {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // 头部：居中标题 + 右「完成」（对齐 iOS navigationTitle + confirmationAction）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.qrtransfer_done))
                    }
                }

                if (frames.isEmpty()) {
                    if (tooLarge) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.height(40.dp),
                        )
                        Text(
                            stringResource(R.string.qrtransfer_tooLarge),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.qrtransfer_tooLargeHint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.qrtransfer_generating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // 已渲染图片按 index 取；该帧未就绪时取最后一张已生成的
                    if (images.isNotEmpty()) {
                        val current = images[minOf(index, images.size - 1)]
                        Image(
                            bitmap = current.asImageBitmap(),
                            contentDescription = null,
                            filterQuality = FilterQuality.None, // iOS interpolation(.none)
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .aspectRatio(1f),
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                    if (images.size < frames.size) {
                        Text(
                            stringResource(
                                R.string.qrtransfer_generatingProgress,
                                images.size, frames.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (frames.size > 1) {
                        // 方块阵列顶替原数字分片提示的位置
                        ShardGrid(
                            total = frames.size,
                            current = index + 1,
                            onTap = if (manual) {
                                { oneBased -> index = (oneBased - 1 + frames.size) % frames.size }
                            } else null,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        // 控件区：手动开关 + 速度档（手动时隐藏）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(checked = manual, onCheckedChange = { manual = it })
                            Text(
                                stringResource(R.string.qrtransfer_manual),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (!manual) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.qrtransfer_speed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(12.dp))
                                SingleChoiceSegmentedButtonRow {
                                    speeds.forEachIndexed { i, s ->
                                        SegmentedButton(
                                            selected = speed == s,
                                            onClick = { speed = s },
                                            shape = SegmentedButtonDefaults.itemShape(i, speeds.size),
                                        ) {
                                            Text("%.1fs".format(s))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/cn/imgozi/planetport/ui/qrtransfer/ app/src/main/res/
git commit -m "feat(qrtransfer): 新增二维码分享弹层与分片方块阵列

ShardGrid 流式换行居中阵列，发送端当前帧高亮可点跳、接收端已收
格主题色 35% 填充未收格描边；QRShareSheet 后台编码逐张渲染逐张
回填，首张就绪即显示，多帧支持四档速度轮播与手动模式点块跳帧，
单帧退化为静态码。"
```

---

### Task 4: 连续扫码 + 二维码接收弹层（QRReceiveSheet）

**Files:**
- Create: `app/src/main/java/cn/imgozi/planetport/ui/qrtransfer/QRReceiveSheet.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/home/QrScannerSheet.kt`（`decodeQr`、`DeniedGuide` 改 internal 供复用）
- Modify: 三语 strings.xml（qrtransfer 接收侧文案）

**Interfaces:**
- Consumes: Task 2 的 `QRTransfer.parse/decode`、`QRFrame`、`QRTransferException`；QrScannerSheet 的 `internal fun decodeQr(reader: MultiFormatReader, proxy: ImageProxy): String?`、`@Composable internal fun DeniedGuide(modifier: Modifier = Modifier)`
- Produces: `@Composable internal fun QRReceiveSheet(onComplete: (ByteArray) -> Unit, onDismiss: () -> Unit)`

- [ ] **Step 1: 添加接收侧文案**

values/strings.xml:
```xml
    <string name="qrtransfer_receiveTitle">二维码接收</string>
    <string name="qrtransfer_receivedProgress">已接收 %1$d/%2$d</string>
    <string name="qrtransfer_waiting">对准二维码，自动识别分片</string>
    <string name="qrtransfer_corrupt">数据校验失败，请重新扫描</string>
    <string name="qrtransfer_cameraDenied">相机权限被拒</string>
    <string name="qrtransfer_cameraDeniedHint">请在系统设置中允许访问相机后重试。</string>
```
values-en/strings.xml:
```xml
    <string name="qrtransfer_receiveTitle">QR Receive</string>
    <string name="qrtransfer_receivedProgress">Received %1$d of %2$d</string>
    <string name="qrtransfer_waiting">Point at the QR code</string>
    <string name="qrtransfer_corrupt">Checksum failed, please rescan</string>
    <string name="qrtransfer_cameraDenied">Camera access denied</string>
    <string name="qrtransfer_cameraDeniedHint">Allow camera access in Settings, then try again.</string>
```
values-zh-rTW/strings.xml:
```xml
    <string name="qrtransfer_receiveTitle">二維碼接收</string>
    <string name="qrtransfer_receivedProgress">已接收 %1$d/%2$d</string>
    <string name="qrtransfer_waiting">對準二維碼，自動識別分片</string>
    <string name="qrtransfer_corrupt">資料校驗失敗，請重新掃描</string>
    <string name="qrtransfer_cameraDenied">相機權限被拒</string>
    <string name="qrtransfer_cameraDeniedHint">請在系統設定中允許存取相機後重試。</string>
```

复用已有键：取消 `common_cancel`、去设置 `common_goSettings`（均已存在，QrScannerSheet 在用）。

- [ ] **Step 2: 放开 QrScannerSheet 两处可见性**

`QrScannerSheet.kt` 中：
- `private fun decodeQr(...)` → `internal fun decodeQr(...)`
- `private fun DeniedGuide(...)` → `internal fun DeniedGuide(...)`
（kdoc 注释同步「连续扫码弹层复用」说明。）

- [ ] **Step 3: 实现 QRReceiveSheet**

`QRReceiveSheet.kt`：

```kotlin
package cn.imgozi.planetport.ui.qrtransfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.imgozi.planetport.R
import cn.imgozi.planetport.qrtransfer.QRFrame
import cn.imgozi.planetport.qrtransfer.QRTransfer
import cn.imgozi.planetport.qrtransfer.QRTransferException
import cn.imgozi.planetport.ui.components.SheetScaffold
import cn.imgozi.planetport.ui.home.decodeQr
import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import java.util.concurrent.Executors

/**
 * 接收端（iOS QRReceiveView）：连续扫码聚合分片。不区分单帧/多帧——解码任意
 * 帧 → 按 index 聚合 → 齐全后整体校验（Base45 → 解压 → CRC）→ 通过即回调一次。
 * total/checksum 与已收集集不一致（扫到新会话）时自动清空重来；CRC 失败同样清空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QRReceiveSheet(
    onComplete: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
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

    var total by remember { mutableStateOf<Int?>(null) }
    var checksum by remember { mutableStateOf<UInt?>(null) }
    var chunks by remember { mutableStateOf(mapOf<Int, String>()) }
    var corrupt by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun handle(text: String) {
        if (finished) return
        val frame = QRTransfer.parse(text) ?: return
        // 换了传输会话（total 或 CRC 不同）：丢弃旧集合
        if (total != null && (total != frame.total || checksum != frame.checksum)) {
            chunks = emptyMap()
        }
        total = frame.total
        checksum = frame.checksum
        chunks = chunks + (frame.index to frame.payload)
        corrupt = false
        if (chunks.size != frame.total) return
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
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetScaffold {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 头部：左「取消」+ 居中标题（对齐 iOS cancellationAction + navigationTitle）
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
                    Spacer(Modifier.height(32.dp))
                }

                if (granted) {
                    Box(modifier = Modifier.fillMaxWidth().height(420.dp)) {
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
                                    stringResource(R.string.qrtransfer_receivedProgress, chunks.size, t),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (corrupt) stringResource(R.string.qrtransfer_corrupt)
                                else stringResource(R.string.qrtransfer_waiting),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (corrupt) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                } else {
                    cn.imgozi.planetport.ui.home.DeniedGuide(
                        modifier = Modifier.fillMaxWidth().height(420.dp),
                    )
                }
            }
        }
    }
}

/**
 * 连续扫码（iOS ContinuousScannerView/Controller）：多帧聚合接收用，不一次即停。
 * 同一内容 800ms 内只回调一次（对准静止的一帧不会刷屏，轮播自然交替放行）。
 * 相机生命周期与 QrScannerSheet.ScannerContent 同构：离组解绑 + 回收线程。
 */
@OptIn(ExperimentalGetImage::class)
@Composable
private fun ContinuousScanner(
    onFrame: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // 800ms 同值去重（iOS lastValue/lastTime）
    val lastValue = remember { arrayOfNulls<String>(1) }
    val lastTime = remember { longArrayOf(0L) }

    class CameraHandle {
        var released = false
        var provider: ProcessCameraProvider? = null
        var executor: java.util.concurrent.ExecutorService? = null
        fun release() {
            released = true
            provider?.unbindAll()
            provider = null
            executor?.shutdown()
            executor = null
        }
    }
    val camera = remember { CameraHandle() }

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
                val text = try {
                    decodeQr(reader, proxy)
                } catch (_: NotFoundException) {
                    null
                }
                proxy.close()
                if (text != null) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val dedupe = synchronized(lastValue) {
                        (text == lastValue[0] && now - lastTime[0] < 800).also {
                            lastValue[0] = text
                            lastTime[0] = now
                        }
                    }
                    if (!dedupe && !camera.released) {
                        mainHandler.post { onFrame(text) }
                    }
                }
            }
            ProcessCameraProvider.getInstance(ctx).addListener({
                try {
                    val provider = ProcessCameraProvider.getInstance(ctx).get()
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
```

注意：`decodeQr` 内部已捕获 NotFoundException 返回 null（见 QrScannerSheet.kt:321），外层 try/catch 为防御性保留可去掉——**执行时以 QrScannerSheet 中 decodeQr 实际签名为准**：若其已处理异常则去掉 try/catch 直接调用。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/cn/imgozi/planetport/ui/qrtransfer/QRReceiveSheet.kt app/src/main/java/cn/imgozi/planetport/ui/home/QrScannerSheet.kt app/src/main/res/
git commit -m "feat(qrtransfer): 新增二维码接收弹层与连续扫码控制器

连续扫码复用 CameraX+zxing 逐帧解码栈（QrScannerSheet 的
decodeQr/DeniedGuide 放开 internal），800ms 同值去重对准静止帧
不刷屏；任意帧进聚合，total/checksum 变化即换会话清空，齐全后
Base45/解压/CRC 三重校验通过回调一次，失败清空提示重扫。相机
权限未决弹系统框、被拒走引导页。"
```

---

### Task 5: 行星设置页分享行（文件 / 二维码双选项）

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/planet/PlanetSettingsView.kt`
- Modify: 三语 strings.xml

**Interfaces:**
- Consumes: Task 3 的 `QRShareSheet`；现有 `sharePlanetFile`
- Produces: 无对外接口

- [ ] **Step 1: 添加文案**

values/strings.xml:
```xml
    <string name="planet_share_file">文件</string>
    <string name="planet_share_qr">二维码</string>
    <string name="planet_share_qr_title">二维码分享</string>
```
values-en/strings.xml:
```xml
    <string name="planet_share_file">File</string>
    <string name="planet_share_qr">QR Code</string>
    <string name="planet_share_qr_title">QR Code Sharing</string>
```
values-zh-rTW/strings.xml:
```xml
    <string name="planet_share_file">檔案</string>
    <string name="planet_share_qr">二維碼</string>
    <string name="planet_share_qr_title">二維碼分享</string>
```

（`common_share` 已存在，直接复用。）

- [ ] **Step 2: 改造 PlanetSettingsView**

1. 页面状态区加：

```kotlin
    var showQRShare by remember { mutableStateOf(false) }
    var qrShareData by remember { mutableStateOf<ByteArray?>(null) }
    var showFileShare by remember { mutableStateOf(false) }
```

2. 信息 FormSection（footer = planet_info_footer 的那个，第 168 行附近）：`FileSizeRow(...)` 之后、修订时间行之后加分享行（iOS：非官方且文件存在才显示）：

```kotlin
                    if (!isOfficial && planet.fileName != null) {
                        ShareRow(
                            onFile = { showFileShare = true },
                            onQr = {
                                // 读取 planet 文件进入二维码分享；读取失败(并发删除)不弹页
                                val file = File(File(context.filesDir, "planets"), planet.fileName!!)
                                val bytes = runCatching { file.readBytes() }.getOrNull()
                                if (bytes != null) {
                                    qrShareData = bytes
                                    showQRShare = true
                                }
                            },
                        )
                    }
```

（若组合作用域无 `context`，用 `val context = LocalContext.current` 补取；`planet.fileName` 的空安全以实际模型为准。）

3. 新增 `ShareRow` 组件（放在 `FileSizeRow` 旁）：

```kotlin
/** 分享行（iOS e4139a9 后）：「分享」标签 + 文件/二维码双选项（iOS shareLabel，
 *  图标 14dp、间距 4dp、颜色跟随正文）。 */
@Composable
private fun ShareRow(onFile: () -> Unit, onQr: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.common_share), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        ShareOption(Icons.Outlined.Description, stringResource(R.string.planet_share_file), onFile)
        Box(
            Modifier
                .padding(horizontal = 10.dp)
                .width(1.dp)
                .height(14.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        ShareOption(Icons.Outlined.QrCode2, stringResource(R.string.planet_share_qr), onQr)
    }
}

@Composable
private fun ShareOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
```

4. `FileSizeRow` 中删除 shareable 相关的分享图标块（保留文件大小值本身，kdoc 更新为「分享入口已独立成『分享』行」；`sharePlanetFile` 函数保留，分享行文件选项改走它）：

分享行的文件选项实际触发点在页面层：

```kotlin
    if (showFileShare) {
        val file = planet.fileName?.let { File(File(context.filesDir, "planets"), it) }
        if (file != null && file.exists()) sharePlanetFile(context, file)
        showFileShare = false
    }

    if (showQRShare && qrShareData != null) {
        QRShareSheet(
            title = stringResource(R.string.planet_share_qr_title),
            data = qrShareData!!,
            onDismiss = {
                showQRShare = false
                qrShareData = null
            },
        )
    }
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/cn/imgozi/planetport/ui/planet/PlanetSettingsView.kt app/src/main/res/
git commit -m "feat(planet): 行星设置页分享入口改为文件/二维码双选项

文件大小行内单一分享图标移除，信息区新增「分享」行（非官方行星
且 planet 文件存在时显示）：文件走原 FileProvider ACTION_SEND，
二维码读取 planet 文件进动态二维码分享弹层。"
```

---

### Task 6: 新增页动态二维码导入 + 5 分钟表单清空

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/home/AddPlanetPage.kt`
- Modify: 三语 strings.xml

**Interfaces:**
- Consumes: Task 4 的 `QRReceiveSheet`；现有 `PlanetImporter.PLANET_TYPE_BYTE`、`addplanet_badHeader` 文案
- Produces: 无对外接口

- [ ] **Step 1: 添加文案**

values/strings.xml:
```xml
    <string name="addplanet_scanPlanetQr">或扫描动态二维码导入</string>
```
values-en/strings.xml:
```xml
    <string name="addplanet_scanPlanetQr">Or scan animated QR code to import</string>
```
values-zh-rTW/strings.xml:
```xml
    <string name="addplanet_scanPlanetQr">或掃描動態二維碼導入</string>
```

- [ ] **Step 2: AddPlanetForm 加参数与状态**

`AddPlanetForm` 签名加 `isCurrentPage: Boolean`（放在 `colorHex` 之后），调用点（`AddPlanetPage` 内 `AddPlanetForm(...)` 处）传 `isCurrentPage = isCurrentPage`。

`AddPlanetForm` 内新增状态：

```kotlin
    var showPlanetReceiver by remember { mutableStateOf(false) }
    // 离开新增页的时间；离开超过 5 分钟后回来视为放弃，清空表单
    var leftAt by remember { mutableStateOf<Long?>(null) }
    val keepFormInterval = 5 * 60 * 1000L
```

现有 `LaunchedEffect(isCurrentPage)`（AddPlanetPage 层重掷身份处不动）之外，Form 内加：

```kotlin
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            // 回来时才判断：离开超过 5 分钟清空 URL 与已就绪文件
            // （时间戳判墙钟，App 挂起期间同样计时）
            val left = leftAt
            if (left != null && System.currentTimeMillis() - left > keepFormInterval) {
                url = ""
                pendingData = null
            }
            leftAt = null
        } else {
            leftAt = System.currentTimeMillis()
        }
    }
```

- [ ] **Step 3: 加「扫描动态二维码导入」按钮与接收弹层**

在「扫码导入」（`addplanet_scanUrl` 那个 OutlinedButton）**之前**插入（iOS 顺序：选择文件 → 动态二维码导入 → 扫 URL 码）：

```kotlin
            // 动态二维码导入（模块 QRTransfer）：连续扫分片，收齐即回填 pendingData，
            // 复用与文件/URL 相同的「命名 + 添加 + 查重」后续流程
            OutlinedButton(
                onClick = { showPlanetReceiver = true },
                enabled = !downloading,
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = RoundedCornerShape(HomeMetrics.radiusSmall),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCode2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    stringResource(R.string.addplanet_scanPlanetQr),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
            }
```

文件末尾 `if (showScanner)` 块旁加：

```kotlin
    if (showPlanetReceiver) {
        QRReceiveSheet(
            onComplete = { data ->
                showPlanetReceiver = false
                when {
                    data.isEmpty() || data[0] != PlanetImporter.PLANET_TYPE_BYTE -> alert = badHeader
                    else -> pendingData = data
                }
            },
            onDismiss = { showPlanetReceiver = false },
        )
    }
```

import 补：`cn.imgozi.planetport.ui.qrtransfer.QRReceiveSheet`、`androidx.compose.material.icons.outlined.QrCode2`。

- [ ] **Step 4: 编译验证**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/cn/imgozi/planetport/ui/home/AddPlanetPage.kt app/src/main/res/
git commit -m "feat(planet): 新增页支持扫描动态二维码导入与超时清空表单

「选择行星文件」与「扫码导入 URL」之间新增动态二维码导入按钮，
连续扫分片收齐校验后回填待添加数据，复用命名+添加+查重流程；
记录离开新增页的墙钟时间，回来时超 5 分钟清空 URL 与已就绪文件
（App 挂起期间同样计时），未超时保留原输入。"
```

---

### Task 7: 关于页构建信息行（BuildInfo）

**Files:**
- Modify: `app/build.gradle.kts`（generateBuildInfo 任务）
- Modify: `.gitignore`
- Create: （构建时生成）`app/src/main/java/cn/imgozi/planetport/Generated/BuildInfo.kt`
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/prefs/PrefsView.kt`
- Modify: 三语 strings.xml

**Interfaces:**
- Produces: `object BuildInfo { const val time: String; const val revision: String }`（包 `cn.imgozi.planetport`）

- [ ] **Step 1: 添加文案**

values/strings.xml:
```xml
    <string name="prefs_about_build">构建</string>
```
values-en/strings.xml:
```xml
    <string name="prefs_about_build">Build</string>
```
values-zh-rTW/strings.xml:
```xml
    <string name="prefs_about_build">構建</string>
```

- [ ] **Step 2: Gradle 生成任务**

`app/build.gradle.kts` 文件末尾（`dependencies` 块之后）加：

```kotlin
// 构建信息（iOS project.yml preBuildScripts「生成构建信息」等价）：
// 每次构建生成构建时间 + git describe，关于页展示，用于确认手机上安装的
// 包对应哪次构建。生成文件不入库（.gitignore）。
val generateBuildInfo by tasks.registering {
    outputs.upToDateWhen { false }
    val genDir = file("src/main/java/cn/imgozi/planetport/Generated")
    doLast {
        genDir.mkdirs()
        // tag 优先（git describe），无 tag 回退短 hash；dirty 标记未提交改动
        val rev = runCatching {
            ProcessBuilder("git", "describe", "--tags", "--always", "--dirty=+dirty")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start().inputStream.bufferedReader().readText().trim()
        }.getOrNull().takeUnless { it.isNullOrEmpty() } ?: "unknown"
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())
        File(genDir, "BuildInfo.kt").writeText(
            "// 构建脚本自动生成，勿手改（app/build.gradle.kts generateBuildInfo）。\n" +
                "package cn.imgozi.planetport\n" +
                "\n" +
                "object BuildInfo {\n" +
                "    const val time = \"$now\"\n" +
                "    const val revision = \"$rev\"\n" +
                "}\n",
        )
    }
}

tasks.named("preBuild") { dependsOn(generateBuildInfo) }
```

根 `.gitignore` 加：

```
app/src/main/java/cn/imgozi/planetport/Generated/
```

- [ ] **Step 3: 关于区加构建信息行**

`PrefsView.kt` 关于 FormSection 内，App 版本行之后加（对齐 iOS buildInfoRow：上行构建时间、下行 git 版本，等宽、可选择复制）：

```kotlin
                SelectionContainer {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            stringResource(R.string.prefs_about_build),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.weight(1f).padding(start = 12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(BuildInfo.time, style = PlanetFonts.MonoBody)
                            Text(BuildInfo.revision, style = PlanetFonts.MonoBody)
                        }
                    }
                }
```

（`PlanetFonts.MonoBody` 若 PrefsView 未导入则补 import；`BuildInfo` 同包无需 import。）

- [ ] **Step 4: 构建验证（同时触发生成任务）**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5 && cat app/src/main/java/cn/imgozi/planetport/Generated/BuildInfo.kt`
Expected: BUILD SUCCESSFUL；BuildInfo.kt 含 time/revision 两行
再 Run: `git status --porcelain`
Expected: Generated/ 不出现在未跟踪列表（已 gitignore）

- [ ] **Step 5: 提交**

```bash
git add app/build.gradle.kts .gitignore app/src/main/java/cn/imgozi/planetport/ui/prefs/PrefsView.kt app/src/main/res/
git commit -m "feat(build): 关于页展示构建时间与代码版本

preBuild 挂 generateBuildInfo 任务，每次构建生成 BuildInfo（构建
时间 + git describe 的 tag/短 hash，dirty 标记未提交改动），设置-
关于新增构建信息行（等宽字体可复制），用于确认手机上安装的包对
应哪次构建。生成文件不入库。"
```

---

### Task 8: 托管路由行点击切换网段/网关

**Files:**
- Modify: `app/src/main/java/cn/imgozi/planetport/ui/detail/NetworkDetailView.kt`

**Interfaces:**
- Consumes: 现有 `MonoListOrEmpty`（地址区继续用）
- Produces: 无对外接口

- [ ] **Step 1: 路由区换 RouteList**

`NetworkDetailView` 组合内加状态：

```kotlin
    // 已切到 via 网关视图的路由下标（点击值区域切换）
    var viaExpandedRoutes by remember { mutableStateOf(setOf<Int>()) }
```

托管路由 FormSection 改为：

```kotlin
        FormSection(header = stringResource(R.string.network_managedRoutes)) {
            val routes = snapshot?.routes.orEmpty()
            if (routes.isEmpty()) {
                // 空表仍渲染标签行 +「—」（与 MonoListOrEmpty 空态同构）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.network_route), style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                routes.forEachIndexed { index, route ->
                    RouteRow(
                        index = index,
                        route = route,
                        viaExpanded = index in viaExpandedRoutes,
                        toggle = {
                            viaExpandedRoutes = if (index in viaExpandedRoutes) {
                                viaExpandedRoutes - index
                            } else {
                                viaExpandedRoutes + index
                            }
                        },
                    )
                }
            }
        }
```

- [ ] **Step 2: RouteRow 组件**

```kotlin
/**
 * 路由行（iOS RouteRow）：路由串较长（`目标 via 网关`），默认只显示目标网段，
 * 点击值区域切换为 via 网关；直连路由（无 via）不可切换、不显示图标。
 * 展开状态由调用方按下标追踪（路由字符串可能重复，不能用内容当 key）。
 */
@Composable
private fun RouteRow(
    index: Int,
    route: String,
    viaExpanded: Boolean,
    toggle: () -> Unit,
) {
    // "192.168.8.0/24 via 152.124.13.2" → 目标 / via 网关
    val parts = route.split(" via ", limit = 2)
    val target = parts[0]
    val via = parts.getOrNull(1)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.network_routeLabel, index + 1), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f).padding(start = 12.dp))
        if (via != null) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = toggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (viaExpanded) "via $via" else target,
                    style = PlanetFonts.MonoBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    textAlign = TextAlign.End,
                )
                Icon(
                    Icons.AutoMirrored.Outlined.SwapHoriz,
                    contentDescription = stringResource(R.string.network_managedRoutes),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Text(
                target,
                style = PlanetFonts.MonoBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                textAlign = TextAlign.End,
            )
        }
    }
}
```

（`Icons.AutoMirrored.Outlined.SwapHoriz` 对应 iOS `arrow.left.arrow.right`；需补 import `androidx.compose.material.icons.automirrored.outlined.SwapHoriz`。）

- [ ] **Step 3: 编译验证**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/cn/imgozi/planetport/ui/detail/NetworkDetailView.kt
git commit -m "feat(ui): 托管路由行支持点击切换网段与网关

路由串（目标 via 网关）过长易截断，默认只显示目标网段，点击值区
域切换为 via 网关，末尾双向箭头图标提示可切换；直连路由无网关、
不可切换。展开状态按下标追踪，避免重复路由串触发重复 key 崩溃。"
```

---

### Task 9: e4139a9 核对 + 全量构建与真机验证

**Files:**
- 无代码（核对结论写入设计文档尾注或本计划）

- [ ] **Step 1: 核对 e4139a9 等效交互**

iOS 修复的是 iOS 26 对 Form/List 按钮整行布局接管导致对齐失效 + 弹窗锚点漂移——平台特有 bug。Android 侧核对两点：
1. `PlanetSettingsView.kt` 删除按钮：确认弹窗为平台 AlertDialog（居中、无锚点问题），按钮文字对齐无异常 → 无需改动
2. `NetworkDetailView.kt` 删除按钮：同上

核对方式：grep 两处删除按钮实现，确认均为 AlertDialog + 无布局 hack。结论记录到本文件勾选项旁。

- [ ] **Step 2: 全量测试 + 构建**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug 2>&1 | tail -10`
Expected: 11 例单测全 PASS，APK 构建成功

- [ ] **Step 3: 装机冒烟（交给用户真机自测）**

装机后核对清单（对照 iOS 行为）：
- 行星设置页（私有行星）：分享行 → 二维码 → 轮播展示、速度四档、手动模式点块跳帧
- 新增页：扫描动态二维码导入 → 与 iOS 互扫双向成功（Android 发 iOS 收、iOS 发 Android 收）
- 新增页离开 5 分钟以上回来 URL/文件清空，5 分钟内保留
- 网络详情页托管路由：点击值区域网段/网关切换、直连无切换
- 设置-关于：构建时间与版本两行
- 三语切换、深色模式过一遍

- [ ] **Step 4: 收尾提交（如有核对修正）**

```bash
git --no-pager log --oneline dev ^e172165
git --no-pager diff --stat e172165..HEAD
```

确认本次迁移提交序列完整、无夹带无关变更。

---

## Self-Review 记录

- Spec 覆盖：块 1（Task 1-6）、块 2（Task 6）、块 3 三项（Task 7/8/9）✓；iOS `qrtransfer.frame` 文案键未被 iOS 最终代码引用（388c2c0 被方块阵列替代），不迁 ✓
- 类型一致性：`ShardGrid` 签名 Task 3 定义、Task 4 消费一致；`QRShareSheet`/`QRReceiveSheet` 签名与 Task 5/6 调用一致；`decodeQr`/`DeniedGuide` internal 化在 Task 4 Step 2 先行
- CRC32 表生成多项式循环为 `ushr 1`（表查询为 `ushr 8`），已核对与 iOS 一致

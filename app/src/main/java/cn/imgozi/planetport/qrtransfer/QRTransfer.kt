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
                    "${"%08x".format(crc.toInt())}|${"%05d".format(encoded.length)}|$chunk",
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

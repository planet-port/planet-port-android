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

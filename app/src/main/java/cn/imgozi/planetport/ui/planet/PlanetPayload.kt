package cn.imgozi.planetport.ui.planet

import cn.imgozi.planetport.qrtransfer.PayloadContainer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** ZTP 容器的 PlanetPort 应用层 key 约定(key 语义归应用层,组件零假设):
 * 1=planet 二进制(必备) 2=名称 3=图标 key 4=图标底色 hex。 */
internal object PlanetPayload {
    val keyPlanet: UByte = 1u
    val keyName: UByte = 2u
    val keyIcon: UByte = 3u
    val keyIconColor: UByte = 4u

    data class SharePayload(
        val planet: ByteArray,
        val name: String?,
        val icon: String?,
        val iconColor: String?,
    )

    /** 组容器:名称必带(空名防御性省略);icon/iconColor 为 null 时省略条目。 */
    fun container(planet: ByteArray, name: String, icon: String?, iconColor: String?): ByteArray {
        val fields = mutableListOf(keyPlanet to planet)
        if (name.isNotEmpty()) fields.add(keyName to name.toByteArray(Charsets.UTF_8))
        icon?.let { fields.add(keyIcon to it.toByteArray(Charsets.UTF_8)) }
        iconColor?.let { fields.add(keyIconColor to it.toByteArray(Charsets.UTF_8)) }
        return PayloadContainer.encode(fields)
    }

    /** 解析容器。容器非法(magic/边界不成立)或 planet 字段缺失 → null。
     * 文本字段按 UTF-8 解码,失败按缺省处理(保留 planet 可用性)。 */
    fun parse(data: ByteArray): SharePayload? {
        val fields = PayloadContainer.decode(data) ?: return null
        val planet = fields[keyPlanet] ?: return null
        fun text(key: UByte): String? = fields[key]?.let(::decodeUtf8)
        return SharePayload(planet, text(keyName), text(keyIcon), text(keyIconColor))
    }

    /** 严格 UTF-8 解码(非法序列返回 null,与 iOS String(data:encoding:) 一致)。 */
    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
}

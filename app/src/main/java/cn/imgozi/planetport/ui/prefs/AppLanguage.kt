package cn.imgozi.planetport.ui.prefs

import androidx.core.os.LocaleListCompat

/**
 * 语言存储码 ↔ per-app locale 标签映射（T7 本地化接线）。
 *
 * AppPrefs.language 沿用 T4 存储码（"zh"/"zh-rTW"/"en"，null = 跟随系统）；
 * 但 [LocaleListCompat.forLanguageTags] 走 BCP47，"zh-rTW" 非法（子标签会被
 * 丢弃只剩 "zh"，繁体请求会错误落到简体），必须映射为 "zh-TW"。
 * "zh" 请求映射为 "zh-CN"：工程无 values-zh 目录，简体即默认资源，二者等价，
 * 且规范化后与系统 Settings「应用语言」写回的标签形态一致。
 */

/** 存储码 → BCP47 标签（null = 跟随系统）。 */
fun localeTagFor(code: String?): String? = when (code) {
    "zh" -> "zh-CN"
    "zh-rTW" -> "zh-TW"
    "en" -> "en"
    else -> null
}

/** 已应用 locale 标签 → 存储码（无法识别返回 null，跟随系统）。 */
fun languageCodeFor(tags: String?): String? = when {
    tags.isNullOrEmpty() -> null
    tags.startsWith("zh-TW") || tags.startsWith("zh-HK") ||
        tags.startsWith("zh-MO") || tags.startsWith("zh-Hant") -> "zh-rTW"
    tags.startsWith("zh") -> "zh"
    tags.startsWith("en") -> "en"
    else -> null
}

/** 存储码 → locale 列表（null = 空列表，即跟随系统）。 */
fun localeListFor(code: String?): LocaleListCompat =
    localeTagFor(code)?.let(LocaleListCompat::forLanguageTags)
        ?: LocaleListCompat.getEmptyLocaleList()

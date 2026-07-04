package com.pochamps.supporter.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 9개 언어 이름 묶음.
 * 포챔스 공식 지원 언어: en/ja/ko/de/es/fr/it/zh-cn/zh-tw.
 * JSON 키에 하이픈이 있어(zh-cn/zh-tw) @SerialName 으로 매핑한다.
 */
@Serializable
data class LocalizedNames(
    val en: String? = null,
    val ja: String? = null,
    val ko: String? = null,
    val de: String? = null,
    val es: String? = null,
    val fr: String? = null,
    val it: String? = null,
    @SerialName("zh-cn") val zhCn: String? = null,
    @SerialName("zh-tw") val zhTw: String? = null,
) {
    /** 언어 코드로 이름 조회. 없으면 en fallback. */
    fun get(lang: String): String? = when (lang) {
        "en" -> en
        "ja" -> ja
        "ko" -> ko
        "de" -> de
        "es" -> es
        "fr" -> fr
        "it" -> it
        "zh-cn" -> zhCn
        "zh-tw" -> zhTw
        else -> null
    } ?: en

    /** 인덱싱/매칭에 쓰기 위해 존재하는 모든 언어 이름을 리스트로. */
    fun all(): List<String> = listOfNotNull(en, ja, ko, de, es, fr, it, zhCn, zhTw)
}

/** 포챔스가 지원하는(=우리가 매칭 대상으로 삼는) 언어 코드 목록. */
val SUPPORTED_LANGUAGES: List<String> =
    listOf("en", "ja", "ko", "de", "es", "fr", "it", "zh-cn", "zh-tw")

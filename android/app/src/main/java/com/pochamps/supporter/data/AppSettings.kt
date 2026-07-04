package com.pochamps.supporter.data

import android.content.Context

/**
 * 앱 설정(게임 언어 등) 영속 저장소.
 *
 * DESIGN.md 5장/열린질문: 게임 언어 선택(9언어 → OcrEngine/표시 언어 연동).
 * 언어 코드는 SUPPORTED_LANGUAGES 중 하나. 기본값은 "ko".
 */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 게임/표시 언어 코드(SUPPORTED_LANGUAGES). 기본 "ko". */
    var language: String
        get() = prefs.getString(KEY_LANG, DEFAULT_LANG)
            ?.takeIf { it in SUPPORTED_LANGUAGES } ?: DEFAULT_LANG
        set(value) {
            val v = if (value in SUPPORTED_LANGUAGES) value else DEFAULT_LANG
            prefs.edit().putString(KEY_LANG, v).apply()
        }

    companion object {
        const val DEFAULT_LANG = "ko"
        private const val PREFS_NAME = "app_settings"
        private const val KEY_LANG = "capture_lang"

        /** 언어 코드 → 사람이 읽는 이름(설정 UI). */
        val LANGUAGE_LABELS: Map<String, String> = mapOf(
            "ko" to "한국어",
            "en" to "English",
            "ja" to "日本語",
            "de" to "Deutsch",
            "es" to "Español",
            "fr" to "Français",
            "it" to "Italiano",
            "zh-cn" to "简体中文",
            "zh-tw" to "繁體中文",
        )
    }
}

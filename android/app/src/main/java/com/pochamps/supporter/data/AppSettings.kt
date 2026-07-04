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

    /**
     * 진단 모드(P14 필드테스트 지원). 켜면 오버레이에 소형 진단 스트립을 표시한다
     * (마지막 OCR 원문·매칭 root/editDistance·인식 시각·OCR 빈도). 기본 off(release 포함).
     */
    var diagnosticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIAG, false)
        set(value) { prefs.edit().putBoolean(KEY_DIAG, value).apply() }

    companion object {
        const val DEFAULT_LANG = "ko"
        private const val PREFS_NAME = "app_settings"
        private const val KEY_LANG = "capture_lang"
        private const val KEY_DIAG = "diagnostics_enabled"

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

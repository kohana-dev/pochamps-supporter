package com.pochamps.supporter.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * 표시 언어(displayLang) ↔ Android 로케일 매핑 유틸(P19).
 *
 * 두 언어 개념이 분리되어 있다:
 *  - 캡처 언어(AppSettings.language): OCR 이 읽을 게임 화면 언어(기술적 필수).
 *  - 표시 언어(AppSettings.displayLang): 유저가 보고 싶은 앱 UI + 카드 내용 언어.
 *
 * 이 유틸은 표시 언어에만 관여한다:
 *  1) 시스템 로케일 → 9개 지원 언어 중 가장 가까운 코드로 매핑(기본값 계산).
 *  2) 언어 코드 → 리소스가 인식하는 [Locale] 로 변환(zh-cn/zh-tw 는 지역 코드 포함).
 *  3) 임의 Context 를 표시 언어 로케일로 래핑한 Context 생성(Activity/Service attachBaseContext 용).
 */
object LocaleUtils {

    /**
     * 앱 표시 언어 코드([SUPPORTED_LANGUAGES]) → Android [Locale].
     * 중국어는 간체(zh-CN)/번체(zh-TW) 를 지역까지 구분해야 리소스(values-zh-rCN/rTW)가 잡힌다.
     */
    fun toLocale(lang: String): Locale = when (lang) {
        "zh-cn" -> Locale.Builder().setLanguage("zh").setRegion("CN").build()
        "zh-tw" -> Locale.Builder().setLanguage("zh").setRegion("TW").build()
        "ko", "en", "ja", "de", "es", "fr", "it" -> Locale.forLanguageTag(lang)
        else -> Locale.forLanguageTag("en")
    }

    /**
     * 시스템(또는 임의) 로케일을 9개 지원 언어 중 가장 가까운 코드로 매핑한다.
     * 매칭 규칙:
     *  - 중국어(zh): 지역/스크립트가 번체(TW/HK/MO/Hant)면 zh-tw, 그 외 zh-cn.
     *  - 그 외 언어는 언어 코드(en/ja/ko/de/es/fr/it)가 지원 목록에 있으면 그 코드.
     *  - 아무것도 안 맞으면 en.
     */
    fun closestSupported(locale: Locale): String {
        val lang = locale.language.lowercase(Locale.ROOT)
        if (lang == "zh") {
            val region = locale.country.uppercase(Locale.ROOT)
            val script = locale.script // "Hant"/"Hans" (있을 수도, 없을 수도)
            val traditional = script.equals("Hant", ignoreCase = true) ||
                region in setOf("TW", "HK", "MO")
            return if (traditional) "zh-tw" else "zh-cn"
        }
        return if (lang in SIMPLE_SUPPORTED) lang else "en"
    }

    /** 앱 최초 실행 시(저장값 없음) 사용할 기본 표시 언어. 시스템 로케일 기반. */
    fun defaultDisplayLang(context: Context): String {
        val locale = primaryLocale(context.resources.configuration)
        return closestSupported(locale)
    }

    /** Configuration 에서 대표 로케일 하나를 꺼낸다(멀티 로케일 환경에서 첫 번째). */
    private fun primaryLocale(config: Configuration): Locale {
        val list = config.locales
        return if (!list.isEmpty()) list[0] else Locale.getDefault()
    }

    /**
     * [base] Context 를 표시 언어 [lang] 로케일로 래핑한 새 Context 를 만든다.
     * Activity/Service 의 attachBaseContext 에서 호출하면, 그 컴포넌트가 조회하는 모든
     * 문자열/리소스가 표시 언어로 해석된다(시스템 로케일은 건드리지 않음).
     */
    fun wrap(base: Context, lang: String): Context {
        val locale = toLocale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** 지역 코드가 필요 없는 단순 지원 언어(중국어 제외). */
    private val SIMPLE_SUPPORTED = setOf("en", "ja", "ko", "de", "es", "fr", "it")
}

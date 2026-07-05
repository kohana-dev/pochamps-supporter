package com.pochamps.supporter.data

import android.content.Context
import com.pochamps.supporter.overlay.OverlayScale
// BattleFormat 은 같은 data 패키지 — import 불필요(동일 패키지).

/**
 * 앱 설정(게임 언어 등) 영속 저장소.
 *
 * DESIGN.md 5장/열린질문: 게임 언어 선택(9언어 → OcrEngine/표시 언어 연동).
 * 언어 코드는 SUPPORTED_LANGUAGES 중 하나. 기본값은 "ko".
 */
class AppSettings(context: Context) {

    // 표시 언어 기본값 계산(시스템 로케일 조회) 시 리소스 접근이 필요해 앱 컨텍스트를 보관한다.
    // (SharedPreferences 는 프로세스 공용이라 앱 컨텍스트로 열어도 동일.)
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 게임 화면 언어(캡처/OCR 용, P19 이전엔 "언어" 단일값).
     * OCR 이 읽을 언어 + 이름 매칭 경로에 쓰인다. 표시 언어([displayLang])와는 별개.
     * 기본 "ko".
     */
    var language: String
        get() = prefs.getString(KEY_LANG, DEFAULT_LANG)
            ?.takeIf { it in SUPPORTED_LANGUAGES } ?: DEFAULT_LANG
        set(value) {
            val v = if (value in SUPPORTED_LANGUAGES) value else DEFAULT_LANG
            prefs.edit().putString(KEY_LANG, v).apply()
        }

    /**
     * 앱 표시 언어(UI chrome + 카드 내용, P19 신규).
     * 캡처 언어([language])와 분리 — OCR 이 어떤 언어를 읽든 도감번호가 확정되면
     * 카드는 이 언어로 정보(이름/타입/특성/기술)를 표시하고, 앱 UI 도 이 로케일로 렌더한다.
     *
     * 저장값이 없으면(최초 실행) 시스템 로케일을 9개 지원 언어 중 가장 가까운 것으로 매핑해
     * 기본값으로 삼는다(없으면 en). [displayLangOrNull] 은 매핑 없이 저장 여부만 본다.
     */
    var displayLang: String
        get() = prefs.getString(KEY_DISPLAY_LANG, null)
            ?.takeIf { it in SUPPORTED_LANGUAGES }
            ?: LocaleUtils.defaultDisplayLang(appContext)
        set(value) {
            val v = if (value in SUPPORTED_LANGUAGES) value else "en"
            prefs.edit().putString(KEY_DISPLAY_LANG, v).apply()
        }

    /**
     * 진단 모드(P14 필드테스트 지원). 켜면 오버레이에 소형 진단 스트립을 표시한다
     * (마지막 OCR 원문·매칭 root/editDistance·인식 시각·OCR 빈도). 기본 off(release 포함).
     */
    var diagnosticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_DIAG, false)
        set(value) { prefs.edit().putBoolean(KEY_DIAG, value).apply() }

    /**
     * 오버레이 카드 스케일(P16). 실기기에서 카드가 너무 크거나 작을 때 설정에서 조정한다.
     * 저장값은 [OverlayScale] 의 허용 단계(0.8/1.0/1.25/1.5) 중 하나로 스냅되고, 범위를 넘으면 클램프된다.
     * 기본 1.0. 저장 즉시 다음 렌더에 반영(오버레이 재생성).
     */
    var overlayScale: Float
        get() = OverlayScale.snap(prefs.getFloat(KEY_SCALE, OverlayScale.DEFAULT))
        set(value) { prefs.edit().putFloat(KEY_SCALE, OverlayScale.snap(value)).apply() }

    /**
     * 배틀 형식(싱글/더블, P20). ROI 밴드 수(싱글 1 / 더블 2)와 사용률 메타(싱글 vs 더블)를 함께 좌우한다.
     * 기본 [BattleFormat.DOUBLES](포챔스는 더블 중심). 저장은 slug 문자열("singles"/"doubles"),
     * 오래된/깨진 값은 기본값으로 안전 폴백.
     *
     * 오버레이 빠른 토글/설정 선택이 이 값을 바꾸면, 파이프라인이 다음 프레임부터 형식별 ROI/사용률을 쓴다.
     * (자동 형식 감지는 향후 확장 지점 — 지금은 수동 토글만.)
     */
    var battleFormat: BattleFormat
        get() = when (prefs.getString(KEY_FORMAT, null)) {
            BattleFormat.SINGLES.slug -> BattleFormat.SINGLES
            else -> BattleFormat.DOUBLES
        }
        set(value) { prefs.edit().putString(KEY_FORMAT, value.slug).apply() }

    /**
     * [P25] 상호작용 모드 자동복귀 사용 여부.
     * true(기본): 조작 모드에서 무조작 [com.pochamps.supporter.overlay.InteractionMode.DEFAULT_TIMEOUT_MS]
     *   경과 시 통과(게임 조작) 모드로 자동 복귀(안전장치).
     * false: 자동복귀 끔 — 유저가 핸들을 다시 탭할 때까지 조작 모드 유지.
     *   핸들이 항상 보이므로(P25) 갇힐 위험이 없어 끄는 선택지를 제공한다.
     */
    var autoRevertEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REVERT, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_REVERT, value).apply() }

    companion object {
        const val DEFAULT_LANG = "ko"
        private const val PREFS_NAME = "app_settings"
        private const val KEY_LANG = "capture_lang"
        private const val KEY_DISPLAY_LANG = "display_lang"
        private const val KEY_DIAG = "diagnostics_enabled"
        private const val KEY_SCALE = "overlay_scale"
        private const val KEY_FORMAT = "battle_format"
        private const val KEY_AUTO_REVERT = "handle_auto_revert" // P25

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

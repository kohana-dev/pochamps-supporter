package com.pochamps.supporter.capture

import android.content.Context
import com.pochamps.supporter.data.BattleFormat

/**
 * [RoiConfigStore] 의 SharedPreferences 구현(얇은 Android 어댑터).
 * 비율 계산/직렬화 로직은 [RoiConfig](순수 JVM)에 있고, 여기선 문자열 읽기/쓰기만 한다.
 * 보정 UI 가 이 store 에 오버라이드를 저장하면 파이프라인이 자동 반영한다.
 *
 * ## P20 — 형식별 저장 키 분리
 * 싱글/더블은 밴드 수가 달라 오버라이드를 공유하면 깨지므로 형식별 키(`roi_override_single`/
 * `roi_override_doubles`)로 나눈다. 구버전 단일 키(`roi_config`)에 값이 있으면 더블 오버라이드로
 * 1회 마이그레이션한다(더블이 기존 기본이었으므로 안전).
 */
class PrefsRoiConfigStore(context: Context) : RoiConfigStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyKeyIfNeeded()
    }

    private fun keyFor(format: BattleFormat): String = when (format) {
        BattleFormat.SINGLES -> KEY_ROI_SINGLE
        BattleFormat.DOUBLES -> KEY_ROI_DOUBLES
    }

    override fun load(format: BattleFormat): RoiConfig? =
        RoiConfig.parse(prefs.getString(keyFor(format), null))

    override fun save(format: BattleFormat, config: RoiConfig) {
        prefs.edit().putString(keyFor(format), RoiConfig.serialize(config)).apply()
    }

    override fun clear(format: BattleFormat) {
        prefs.edit().remove(keyFor(format)).apply()
    }

    /** 구버전 단일 오버라이드(형식 무관) → 더블 오버라이드로 이관 후 구키 제거(1회). */
    private fun migrateLegacyKeyIfNeeded() {
        val legacy = prefs.getString(KEY_ROI_LEGACY, null) ?: return
        val edit = prefs.edit()
        if (prefs.getString(KEY_ROI_DOUBLES, null) == null) {
            edit.putString(KEY_ROI_DOUBLES, legacy)
        }
        edit.remove(KEY_ROI_LEGACY).apply()
    }

    private companion object {
        const val PREFS_NAME = "roi_prefs"
        /** 구버전 형식 무관 키(마이그레이션 대상). */
        const val KEY_ROI_LEGACY = "roi_config"
        const val KEY_ROI_SINGLE = "roi_override_single"
        const val KEY_ROI_DOUBLES = "roi_override_doubles"
    }
}

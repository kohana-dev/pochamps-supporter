package com.pochamps.supporter.capture

import android.content.Context

/**
 * [RoiConfigStore] 의 SharedPreferences 구현(얇은 Android 어댑터).
 * 비율 계산/직렬화 로직은 [RoiConfig](순수 JVM)에 있고, 여기선 문자열 읽기/쓰기만 한다.
 * 나중에 보정 UI 가 이 store 에 오버라이드를 저장하면 파이프라인이 자동 반영한다.
 */
class PrefsRoiConfigStore(context: Context) : RoiConfigStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): RoiConfig? =
        RoiConfig.parse(prefs.getString(KEY_ROI, null))

    override fun save(config: RoiConfig) {
        prefs.edit().putString(KEY_ROI, RoiConfig.serialize(config)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_ROI).apply()
    }

    private companion object {
        const val PREFS_NAME = "roi_prefs"
        const val KEY_ROI = "roi_config"
    }
}

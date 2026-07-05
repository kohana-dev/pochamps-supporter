package com.pochamps.supporter.overlay

import android.content.Context

/**
 * [MinimizeStore] 의 SharedPreferences 구현(P21, 얇은 Android 어댑터).
 * 최소화 상태 토글/기본값 로직은 [MinimizeState](순수 JVM)에 있고, 여기선 읽기/쓰기만 한다.
 * 위치 저장소([PrefsOverlayPositionStore]) 와 같은 오버레이 prefs 파일을 공유한다.
 */
class PrefsMinimizeStore(context: Context) : MinimizeStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): MinimizeState {
        if (!prefs.contains(KEY_MINIMIZED)) return MinimizeState.DEFAULT
        return MinimizeState(minimized = prefs.getBoolean(KEY_MINIMIZED, false))
    }

    override fun save(state: MinimizeState) {
        prefs.edit().putBoolean(KEY_MINIMIZED, state.minimized).apply()
    }

    private companion object {
        const val PREFS_NAME = "overlay_prefs"
        const val KEY_MINIMIZED = "overlay_minimized"
    }
}

package com.pochamps.supporter.overlay

import android.content.Context

/**
 * [OverlayPositionStore] 의 SharedPreferences 구현(얇은 Android 어댑터).
 * 위치 클램프/기본값 계산 로직은 [OverlayPosition](순수 JVM)에 있고, 여기선 읽기/쓰기만 한다.
 */
class PrefsOverlayPositionStore(context: Context) : OverlayPositionStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): OverlayPosition? {
        if (!prefs.contains(KEY_X) || !prefs.contains(KEY_Y)) return null
        return OverlayPosition(
            x = prefs.getInt(KEY_X, 0),
            y = prefs.getInt(KEY_Y, 0),
        )
    }

    override fun save(position: OverlayPosition) {
        prefs.edit()
            .putInt(KEY_X, position.x)
            .putInt(KEY_Y, position.y)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "overlay_prefs"
        const val KEY_X = "overlay_x"
        const val KEY_Y = "overlay_y"
    }
}

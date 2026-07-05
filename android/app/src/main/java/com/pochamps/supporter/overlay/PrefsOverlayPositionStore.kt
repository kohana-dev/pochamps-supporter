package com.pochamps.supporter.overlay

import android.content.Context

/**
 * [OverlayPositionStore] 의 SharedPreferences 구현(얇은 Android 어댑터).
 * 위치 클램프/기본값 계산 로직은 [OverlayPosition](순수 JVM)에 있고, 여기선 읽기/쓰기만 한다.
 */
class PrefsOverlayPositionStore(
    context: Context,
    /**
     * [P24] 키 접두사. 메인 창과 핸들 창이 각자 위치를 따로 저장할 수 있게 한다.
     * 기본 "overlay_"(메인, 하위호환). 핸들은 "handle_" 등을 넘긴다.
     */
    private val keyPrefix: String = "overlay_",
) : OverlayPositionStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val keyX = keyPrefix + "x"
    private val keyY = keyPrefix + "y"

    override fun load(): OverlayPosition? {
        if (!prefs.contains(keyX) || !prefs.contains(keyY)) return null
        return OverlayPosition(
            x = prefs.getInt(keyX, 0),
            y = prefs.getInt(keyY, 0),
        )
    }

    override fun save(position: OverlayPosition) {
        prefs.edit()
            .putInt(keyX, position.x)
            .putInt(keyY, position.y)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "overlay_prefs"
    }
}

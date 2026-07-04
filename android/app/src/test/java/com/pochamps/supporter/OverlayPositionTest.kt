package com.pochamps.supporter

import com.pochamps.supporter.overlay.OverlayPosition
import com.pochamps.supporter.overlay.OverlayPositionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오버레이 위치 저장/클램프 로직 유닛 테스트(순수 JVM).
 * 드래그로 창이 화면 밖으로 나가지 않는지, 저장/복원이 맞는지 검증.
 */
class OverlayPositionTest {

    // 화면 1080x2400, 카드 220x120 가정.
    private val screenW = 1080
    private val screenH = 2400
    private val cardW = 220
    private val cardH = 120

    @Test
    fun clamp_안쪽좌표는_그대로() {
        val p = OverlayPosition(300, 500).clampedTo(screenW, screenH, cardW, cardH)
        assertEquals(300, p.x)
        assertEquals(500, p.y)
    }

    @Test
    fun clamp_음수는_0으로() {
        val p = OverlayPosition(-50, -80).clampedTo(screenW, screenH, cardW, cardH)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    @Test
    fun clamp_오른쪽아래_초과는_최대치로() {
        val p = OverlayPosition(9999, 9999).clampedTo(screenW, screenH, cardW, cardH)
        assertEquals(screenW - cardW, p.x) // 860
        assertEquals(screenH - cardH, p.y) // 2280
    }

    @Test
    fun clamp_카드가_화면보다_크면_0으로() {
        // 카드 폭이 화면보다 큼 → maxX 음수 방지, 0 으로.
        val p = OverlayPosition(500, 500).clampedTo(200, 200, 400, 400)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    @Test
    fun default_가로중앙_상단12퍼센트() {
        val p = OverlayPosition.default(screenW, screenH, cardW, cardH)
        assertEquals((screenW - cardW) / 2, p.x) // 430
        assertEquals((screenH * 0.12f).toInt(), p.y) // 288
        // 항상 화면 안이어야 함.
        assertTrue(p.x in 0..(screenW - cardW))
        assertTrue(p.y in 0..(screenH - cardH))
    }

    @Test
    fun store_저장_복원_왕복() {
        val store = InMemoryStore()
        assertNull("초기엔 저장값 없음", store.load())
        store.save(OverlayPosition(123, 456))
        assertEquals(OverlayPosition(123, 456), store.load())
        // 덮어쓰기.
        store.save(OverlayPosition(7, 8))
        assertEquals(OverlayPosition(7, 8), store.load())
    }

    /** 테스트용 인메모리 저장소. */
    private class InMemoryStore : OverlayPositionStore {
        private var pos: OverlayPosition? = null
        override fun load(): OverlayPosition? = pos
        override fun save(position: OverlayPosition) { pos = position }
    }
}

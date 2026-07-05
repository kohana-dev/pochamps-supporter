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

    // --- P25: 핸들 위치 화면 안 보장 / 회전 재보정 ---

    // 핸들 60dp @ density 3 ≈ 180px, edgeInset 8dp ≈ 24px 가정(테스트용 고정치).
    private val handle = 180
    private val inset = 24

    @Test
    fun isFullyInside_안쪽true_밖false() {
        assertTrue(OverlayPosition(100, 100).isFullyInside(screenW, screenH, handle, handle))
        // 오른쪽 넘침.
        assertTrue(!OverlayPosition(screenW - handle + 1, 100).isFullyInside(screenW, screenH, handle, handle))
        // 음수.
        assertTrue(!OverlayPosition(-1, 100).isFullyInside(screenW, screenH, handle, handle))
    }

    @Test
    fun handleDefault_우측세로중앙_화면안() {
        val p = OverlayPosition.handleDefault(screenW, screenH, handle, handle, inset)
        // 우측: 오른쪽 가장자리 안쪽(inset 만큼).
        assertEquals(screenW - handle - inset, p.x)
        // 세로 중앙.
        assertEquals((screenH - handle) / 2, p.y)
        assertTrue(p.isFullyInside(screenW, screenH, handle, handle))
    }

    @Test
    fun handleDefault_가로화면에서도_화면안() {
        // 가로(1280x720) 게임 화면.
        val lw = 1280; val lh = 720
        val p = OverlayPosition.handleDefault(lw, lh, handle, handle, inset)
        assertTrue("가로에서 핸들이 화면 안이어야 함", p.isFullyInside(lw, lh, handle, handle))
    }

    @Test
    fun remap_세로에서_가로전환_시_화면안() {
        // 세로(1080x2400)에서 우측 하단 근처에 저장된 위치.
        val portrait = OverlayPosition(1080 - handle - inset, 2000)
        // 가로(2400x1080)로 회전.
        val landscape = OverlayPosition.remapForScreen(
            current = portrait,
            oldW = 1080, oldH = 2400,
            newW = 2400, newH = 1080,
            handleWidth = handle, handleHeight = handle,
            edgeInset = inset,
        )
        // 회전 후에도 반드시 화면 안(밖으로 나가 안 보이면 안 됨).
        assertTrue("가로 회전 후 핸들이 화면 안이어야 함", landscape.isFullyInside(2400, 1080, handle, handle))
    }

    @Test
    fun remap_비율보존_상대위치유지() {
        // 세로에서 화면 좌상단 1/4 지점(중심 비율 ≈ 0.25, 0.25).
        val p = OverlayPosition(
            (1080 * 0.25f - handle / 2f).toInt(),
            (2400 * 0.25f - handle / 2f).toInt(),
        )
        val remapped = OverlayPosition.remapForScreen(
            current = p, oldW = 1080, oldH = 2400,
            newW = 2400, newH = 1080,
            handleWidth = handle, handleHeight = handle,
            edgeInset = inset,
        )
        // 새 화면에서도 중심 비율이 대략 0.25 근처(±5%)여야 상대 위치 보존.
        val cxRatio = (remapped.x + handle / 2f) / 2400
        val cyRatio = (remapped.y + handle / 2f) / 1080
        assertTrue("가로 중심 x 비율 유지", cxRatio in 0.20f..0.30f)
        assertTrue("가로 중심 y 비율 유지", cyRatio in 0.20f..0.30f)
    }

    @Test
    fun remap_이전화면크기_미상이면_클램프만() {
        // oldW/oldH = 0(최초/미상) → 비율 매핑 없이 현재 좌표를 새 화면으로 clamp.
        val offscreen = OverlayPosition(9999, 9999)
        val r = OverlayPosition.remapForScreen(
            current = offscreen, oldW = 0, oldH = 0,
            newW = 1280, newH = 720,
            handleWidth = handle, handleHeight = handle,
            edgeInset = inset,
        )
        assertTrue("클램프로 화면 안", r.isFullyInside(1280, 720, handle, handle))
        assertEquals(1280 - handle, r.x)
        assertEquals(720 - handle, r.y)
    }

    @Test
    fun remap_핸들이_화면보다크면_안전기본값() {
        // 비정상: 핸들이 새 화면보다 큼 → clamp 로도 다 못 들어감 → 기본값으로 폴백(화면 좌상단 0,0 근처).
        val bigHandle = 900
        val r = OverlayPosition.remapForScreen(
            current = OverlayPosition(100, 100), oldW = 1080, oldH = 2400,
            newW = 800, newH = 600,
            handleWidth = bigHandle, handleHeight = bigHandle,
            edgeInset = inset,
        )
        // handleDefault 는 clamp 를 거치므로 (0,0) 로 수렴(화면보다 큰 창).
        assertEquals(0, r.x)
        assertEquals(0, r.y)
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

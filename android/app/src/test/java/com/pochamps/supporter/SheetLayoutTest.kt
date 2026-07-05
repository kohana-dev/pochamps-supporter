package com.pochamps.supporter

import com.pochamps.supporter.overlay.SheetLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 후보/검색 시트 배치·창위치 재조정 로직 유닛 테스트(P16, 순수 JVM).
 *
 * 버그: 가로에서 시트가 아래로 펼쳐져 세로가 잘림 → 가로면 옆(측면 flyout), 카드가 오른쪽 끝이면 왼쪽 flip,
 * 커진 창이 화면을 넘으면 화면 안으로 clamp. 세로면 아래 전개 + 아래 넘침 clamp.
 */
class SheetLayoutTest {

    // 가로 화면 1280x720 (AVD 실측 기준), 카드 300x120, 시트 300x400 가정.
    private val landW = 1280
    private val landH = 720
    private val cardW = 300
    private val cardH = 120
    private val sheetW = 300
    private val sheetH = 400
    private val gap = 16

    // --- 세로(portrait): 아래 전개 유지 ---

    @Test
    fun 세로는_아래로_전개() {
        val r = SheetLayout.open(
            landscape = false, screenWidth = 1080, screenHeight = 2400,
            winX = 100, winY = 200,
            cardWidth = cardW, cardHeight = cardH, sheetWidth = sheetW, sheetHeight = sheetH,
        )
        assertEquals(SheetLayout.Placement.BELOW, r.placement)
    }

    @Test
    fun 세로_아래_넘치면_위로_당겨_clamp() {
        // winY 가 화면 하단 근처 → card+sheet 총높이(520)가 화면 아래를 넘음 → y 위로 당김.
        val r = SheetLayout.open(
            landscape = false, screenWidth = 1080, screenHeight = 2400,
            winX = 100, winY = 2200,
            cardWidth = cardW, cardHeight = cardH, sheetWidth = sheetW, sheetHeight = sheetH,
        )
        val totalH = cardH + sheetH // 520
        assertEquals(2400 - totalH, r.windowY) // 1880
        assertTrue(r.windowY + totalH <= 2400)
    }

    // --- 가로(landscape): 측면 flyout ---

    @Test
    fun 가로는_옆으로_전개() {
        val r = SheetLayout.open(
            landscape = true, screenWidth = landW, screenHeight = landH,
            winX = 100, winY = 100,
            cardWidth = cardW, cardHeight = cardH, sheetWidth = sheetW, sheetHeight = sheetH, gap = gap,
        )
        assertEquals(SheetLayout.Placement.SIDE, r.placement)
    }

    @Test
    fun 가로_왼쪽에_카드있으면_오른쪽_flyout() {
        // 카드가 화면 왼쪽(x=100) → 오른쪽에 여유 충분 → RIGHT. 창 x = 카드 x 유지.
        val r = SheetLayout.open(
            landscape = true, screenWidth = landW, screenHeight = landH,
            winX = 100, winY = 100,
            cardWidth = cardW, cardHeight = cardH, sheetWidth = sheetW, sheetHeight = sheetH, gap = gap,
        )
        assertEquals(SheetLayout.SideDir.RIGHT, r.sideDir)
        assertEquals(100, r.windowX)
        // 총폭이 화면 안.
        assertTrue(r.windowX + cardW + gap + sheetW <= landW)
    }

    @Test
    fun 가로_카드가_오른쪽끝이면_왼쪽으로_flip() {
        // 카드가 화면 오른쪽 끝(x=950, cardW=300 → 우측 끝 1250) → 오른쪽에 시트 안 들어감 → LEFT flip.
        val winX = 950
        val r = SheetLayout.open(
            landscape = true, screenWidth = landW, screenHeight = landH,
            winX = winX, winY = 100,
            cardWidth = cardW, cardHeight = cardH, sheetWidth = sheetW, sheetHeight = sheetH, gap = gap,
        )
        assertEquals(SheetLayout.SideDir.LEFT, r.sideDir)
        // LEFT flip: 창 좌상단 = 시트 좌상단 = winX - gap - sheetW = 950-16-300 = 634.
        assertEquals(winX - gap - sheetW, r.windowX) // 634
        assertTrue("창이 화면 밖으로 안 나감", r.windowX >= 0)
        assertTrue(r.windowX + cardW + gap + sheetW <= landW)
    }

    @Test
    fun 가로_세로_넘치면_위로_당겨_clamp() {
        // winY 가 화면 하단 근처 → 시트 높이(400)가 화면 아래 넘음 → y 위로 당김.
        val r = SheetLayout.open(
            landscape = true, screenWidth = landW, screenHeight = landH,
            winX = 100, winY = 600,
            cardWidth = cardW, cardHeight = cardH, sheetWidth = sheetW, sheetHeight = sheetH, gap = gap,
        )
        val totalH = maxOf(cardH, sheetH) // 400
        assertTrue("측면 시트가 화면 세로 안", r.windowY + totalH <= landH)
        assertEquals(landH - totalH, r.windowY) // 320
    }

    @Test
    fun 가로_양쪽_다_안맞아도_화면밖으로_안나감() {
        // 매우 좁은 가로: 화면폭 500, 카드 300, 시트 300 → 어느 쪽도 안 맞음 → RIGHT 우선 + clamp.
        val r = SheetLayout.open(
            landscape = true, screenWidth = 500, screenHeight = 400,
            winX = 100, winY = 100,
            cardWidth = 300, cardHeight = 120, sheetWidth = 300, sheetHeight = 200, gap = 16,
        )
        // 총폭(616) > 화면(500) → clampStart 가 0 으로.
        assertEquals(0, r.windowX)
        assertTrue(r.windowX >= 0)
    }

    // --- 닫기: 카드만 남는 크기 기준 clamp ---

    @Test
    fun 닫으면_카드기준으로_화면안_clamp() {
        // 시트 열 때 왼쪽으로 당겨졌던 창을 닫으면, 카드만 있는 크기로 화면 안이면 유지.
        val r = SheetLayout.close(
            screenWidth = landW, screenHeight = landH,
            winX = 634, winY = 100,
            cardWidth = cardW, cardHeight = cardH,
        )
        assertEquals(634, r.windowX)
        assertEquals(100, r.windowY)
        assertTrue(r.windowX + cardW <= landW)
    }

    @Test
    fun 닫을때_카드가_화면밖이면_안으로_당김() {
        val r = SheetLayout.close(
            screenWidth = landW, screenHeight = landH,
            winX = 1200, winY = 700,
            cardWidth = cardW, cardHeight = cardH,
        )
        assertTrue(r.windowX + cardW <= landW)
        assertTrue(r.windowY + cardH <= landH)
    }
}

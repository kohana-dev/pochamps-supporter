package com.pochamps.supporter

import com.pochamps.supporter.overlay.TrashDropZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P35 리포트3] 휴지통 드롭존 겹침 판정 순수 로직 테스트(메신저 챗헤드 패턴).
 * 드래그 핸들 중심이 하단 중앙 드롭존 반경 안이면 드롭 시 종료, 밖이면 평소 이동.
 */
class TrashDropZoneTest {

    @Test
    fun 드롭존_중심은_하단중앙() {
        val (cx, cy) = TrashDropZone.center(screenWidth = 1000, screenHeight = 2000, bottomMarginPx = 200)
        assertEquals(500, cx)       // 가로 중앙.
        assertEquals(1800, cy)      // 아래에서 200px 위.
    }

    @Test
    fun 드롭존_여백이_화면보다_크면_0으로() {
        val (_, cy) = TrashDropZone.center(screenWidth = 1000, screenHeight = 500, bottomMarginPx = 800)
        assertEquals(0, cy) // 음수 방지.
    }

    @Test
    fun 핸들이_드롭존_중심에_있으면_겹침() {
        // 정확히 중심 위 → 거리 0 → 겹침.
        assertTrue(
            TrashDropZone.isOver(
                handleCenterX = 500f, handleCenterY = 1800f,
                zoneCenterX = 500, zoneCenterY = 1800,
                hitRadiusPx = 100f,
            ),
        )
    }

    @Test
    fun 핸들이_반경_안이면_겹침() {
        // 중심에서 80px(반경 100 이내) → 겹침.
        assertTrue(
            TrashDropZone.isOver(
                handleCenterX = 500f + 48f, handleCenterY = 1800f + 64f, // dist = 80.
                zoneCenterX = 500, zoneCenterY = 1800,
                hitRadiusPx = 100f,
            ),
        )
    }

    @Test
    fun 핸들이_반경_밖이면_겹침아님() {
        // 중심에서 150px(반경 100 초과) → 겹침 아님(평소 이동).
        assertFalse(
            TrashDropZone.isOver(
                handleCenterX = 500f + 150f, handleCenterY = 1800f,
                zoneCenterX = 500, zoneCenterY = 1800,
                hitRadiusPx = 100f,
            ),
        )
    }

    @Test
    fun 반경_경계_정확히_반경이면_겹침() {
        // 거리 == 반경 → 겹침(<=).
        assertTrue(
            TrashDropZone.isOver(
                handleCenterX = 600f, handleCenterY = 1800f, // dist = 100.
                zoneCenterX = 500, zoneCenterY = 1800,
                hitRadiusPx = 100f,
            ),
        )
    }

    @Test
    fun 반경이_0이하면_항상_겹침아님() {
        assertFalse(
            TrashDropZone.isOver(
                handleCenterX = 500f, handleCenterY = 1800f,
                zoneCenterX = 500, zoneCenterY = 1800,
                hitRadiusPx = 0f,
            ),
        )
    }

    @Test
    fun 화면_상단에서_드래그시작하면_겹침아님() {
        // 화면 상단(드래그 시작 지점)은 하단 드롭존과 멀어 겹치지 않는다(오종료 방지).
        val (zx, zy) = TrashDropZone.center(screenWidth = 1280, screenHeight = 720, bottomMarginPx = 180)
        assertFalse(
            TrashDropZone.isOver(
                handleCenterX = 1200f, handleCenterY = 60f, // 우상단 근처.
                zoneCenterX = zx, zoneCenterY = zy,
                hitRadiusPx = 220f,
            ),
        )
    }
}

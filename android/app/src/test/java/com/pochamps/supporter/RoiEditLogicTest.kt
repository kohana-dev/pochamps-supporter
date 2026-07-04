package com.pochamps.supporter

import com.pochamps.supporter.capture.RoiConfig
import com.pochamps.supporter.capture.RoiEditLogic
import com.pochamps.supporter.capture.RoiRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 인앱 ROI 보정(P14) 순수 좌표 변환 로직 유닛 테스트(Android 의존성 없음).
 * 드래그(이동)/리사이즈가 화면비율(0~1)로 클램프되는지, 크기·경계·최소크기 불변식을 검증.
 */
class RoiEditLogicTest {

    private val W = 1000
    private val H = 500

    @Test
    fun move_비율로_이동하고_크기보존() {
        val r = RoiRect(0.2, 0.2, 0.5, 0.4)
        // +100px 가로 = +0.1 비율, +50px 세로 = +0.1 비율.
        val moved = RoiEditLogic.move(r, 100f, 50f, W, H)
        assertEquals(0.3, moved.left, 1e-6)
        assertEquals(0.3, moved.top, 1e-6)
        // 크기 보존.
        assertEquals(r.right - r.left, moved.right - moved.left, 1e-6)
        assertEquals(r.bottom - r.top, moved.bottom - moved.top, 1e-6)
    }

    @Test
    fun move_화면밖으로_밀면_경계에서_멈추고_크기유지() {
        val r = RoiRect(0.8, 0.8, 0.95, 0.95)
        // 오른쪽/아래로 크게 밀어도 오른쪽 끝(1.0)에서 멈춘다.
        val moved = RoiEditLogic.move(r, 999f, 999f, W, H)
        assertEquals(1.0, moved.right, 1e-6)
        assertEquals(1.0, moved.bottom, 1e-6)
        // 크기 그대로.
        assertEquals(0.15, moved.right - moved.left, 1e-6)
        assertEquals(0.15, moved.bottom - moved.top, 1e-6)
    }

    @Test
    fun move_왼쪽위로_밀면_0에서_멈춤() {
        val r = RoiRect(0.1, 0.1, 0.3, 0.3)
        val moved = RoiEditLogic.move(r, -999f, -999f, W, H)
        assertEquals(0.0, moved.left, 1e-6)
        assertEquals(0.0, moved.top, 1e-6)
        assertEquals(0.2, moved.right - moved.left, 1e-6)
    }

    @Test
    fun resize_우하단_핸들은_좌상단_고정() {
        val r = RoiRect(0.2, 0.2, 0.5, 0.5)
        val out = RoiEditLogic.resize(r, RoiEditLogic.Handle.BOTTOM_RIGHT, 100f, 50f, W, H)
        // 좌상단 고정.
        assertEquals(0.2, out.left, 1e-6)
        assertEquals(0.2, out.top, 1e-6)
        // 우하단만 이동(+0.1, +0.1).
        assertEquals(0.6, out.right, 1e-6)
        assertEquals(0.6, out.bottom, 1e-6)
    }

    @Test
    fun resize_좌상단_핸들은_우하단_고정() {
        val r = RoiRect(0.2, 0.2, 0.5, 0.5)
        val out = RoiEditLogic.resize(r, RoiEditLogic.Handle.TOP_LEFT, 100f, 50f, W, H)
        assertEquals(0.3, out.left, 1e-6)
        assertEquals(0.3, out.top, 1e-6)
        // 우하단 고정.
        assertEquals(0.5, out.right, 1e-6)
        assertEquals(0.5, out.bottom, 1e-6)
    }

    @Test
    fun resize_최소크기_아래로는_안줄어듦() {
        val r = RoiRect(0.2, 0.2, 0.5, 0.5)
        // 우하단을 좌상단 쪽으로 크게 당겨도 MIN_SIZE 유지.
        val out = RoiEditLogic.resize(r, RoiEditLogic.Handle.BOTTOM_RIGHT, -9999f, -9999f, W, H)
        assertTrue(out.right - out.left >= RoiEditLogic.MIN_SIZE - 1e-6)
        assertTrue(out.bottom - out.top >= RoiEditLogic.MIN_SIZE - 1e-6)
        // 좌상단 앵커 고정 → right=left+MIN.
        assertEquals(r.left + RoiEditLogic.MIN_SIZE, out.right, 1e-6)
    }

    @Test
    fun resize_화면경계_넘지않음() {
        val r = RoiRect(0.2, 0.2, 0.9, 0.9)
        val out = RoiEditLogic.resize(r, RoiEditLogic.Handle.BOTTOM_RIGHT, 9999f, 9999f, W, H)
        assertTrue(out.right <= 1.0 + 1e-6)
        assertTrue(out.bottom <= 1.0 + 1e-6)
        assertEquals(1.0, out.right, 1e-6)
        assertEquals(1.0, out.bottom, 1e-6)
    }

    @Test
    fun resize_결과는_항상_유효한_RoiRect() {
        // 무작위성 대신 극단 delta 조합으로 RoiRect init(require) 위반이 없는지.
        val r = RoiRect(0.4, 0.4, 0.6, 0.6)
        for (h in RoiEditLogic.Handle.entries) {
            for (dx in listOf(-5000f, -300f, 300f, 5000f)) {
                for (dy in listOf(-5000f, -300f, 300f, 5000f)) {
                    val out = RoiEditLogic.resize(r, h, dx, dy, W, H)
                    // RoiRect 생성자가 left<right, top<bottom 을 보장하므로 여기 도달 = 유효.
                    assertTrue(out.right > out.left)
                    assertTrue(out.bottom > out.top)
                }
            }
        }
    }

    @Test
    fun resizeBandCount_1에서2로_확장() {
        val single = RoiConfig.DEFAULT_LANDSCAPE_SINGLE.rois
        val out = RoiEditLogic.resizeBandCount(single, 2)
        assertEquals(2, out.size)
        // 기존 밴드 보존 + 기본 더블에서 부족분 채움.
        assertEquals(single[0], out[0])
    }

    @Test
    fun resizeBandCount_2에서1로_축소() {
        val doubles = RoiConfig.DEFAULT_LANDSCAPE_DOUBLES.rois
        val out = RoiEditLogic.resizeBandCount(doubles, 1)
        assertEquals(1, out.size)
        assertEquals(doubles[0], out[0])
    }

    @Test
    fun resizeBandCount_같은개수면_그대로() {
        val doubles = RoiConfig.DEFAULT_LANDSCAPE_DOUBLES.rois
        assertEquals(doubles, RoiEditLogic.resizeBandCount(doubles, 2))
    }

    @Test
    fun defaultRois_포맷별() {
        assertEquals(1, RoiEditLogic.defaultRois(1).size)
        assertEquals(2, RoiEditLogic.defaultRois(2).size)
        assertEquals(RoiConfig.DEFAULT_LANDSCAPE_SINGLE.rois, RoiEditLogic.defaultRois(1))
        assertEquals(RoiConfig.DEFAULT_LANDSCAPE_DOUBLES.rois, RoiEditLogic.defaultRois(2))
    }

    @Test
    fun move_저장왕복_RoiConfig_직렬화_호환() {
        // 편집 결과를 RoiConfig 로 조립 → 직렬화 → 파싱 왕복이 되는지(저장 경로 무결성).
        val edited = listOf(
            RoiEditLogic.move(RoiConfig.DEFAULT_LANDSCAPE_DOUBLES.rois[0], 40f, 10f, W, H),
            RoiConfig.DEFAULT_LANDSCAPE_DOUBLES.rois[1],
        )
        val cfg = RoiConfig(edited)
        val parsed = RoiConfig.parse(RoiConfig.serialize(cfg))
        assertEquals(cfg, parsed)
    }
}

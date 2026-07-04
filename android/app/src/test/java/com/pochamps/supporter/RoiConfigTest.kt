package com.pochamps.supporter

import com.pochamps.supporter.capture.RoiConfig
import com.pochamps.supporter.capture.RoiConfigStore
import com.pochamps.supporter.capture.RoiRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RoiConfig 비율→픽셀 계산 / 직렬화 / 오버라이드 store 유닛 테스트(순수 JVM).
 */
class RoiConfigTest {

    @Test
    fun toPixels_비율을_픽셀로() {
        val roi = RoiRect(0.25, 0.10, 0.75, 0.40)
        val rect = roi.toPixels(bitmapWidth = 1000, bitmapHeight = 500)
        assertEquals(250, rect.x)
        assertEquals(50, rect.y)
        assertEquals(500, rect.width)  // (0.75-0.25)*1000
        assertEquals(150, rect.height) // (0.40-0.10)*500
    }

    @Test
    fun toPixels_경계_클램프() {
        // right=1.0 이면 오른쪽 끝까지, 폭이 최소 1 이상.
        val roi = RoiRect(0.0, 0.0, 1.0, 1.0)
        val rect = roi.toPixels(100, 80)
        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
        assertTrue(rect.width in 1..100)
        assertTrue(rect.height in 1..80)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 잘못된_rect는_예외() {
        // right < left → 예외.
        RoiRect(0.5, 0.1, 0.3, 0.4)
    }

    @Test
    fun 기본설정은_ROI_2개() {
        val cfg = RoiConfig.default()
        assertEquals(2, cfg.rois.size)
        // 좌/우 분리(첫 ROI 는 왼쪽, 둘째는 오른쪽).
        assertTrue(cfg.rois[0].right <= cfg.rois[1].left)
    }

    @Test
    fun K2실측_더블_우상단밀집_배치() {
        // P8 K2 실측: 상대 이름표 2개는 화면 "우측 절반"에 나란히(좌/우 대칭 아님).
        val cfg = RoiConfig.DEFAULT_LANDSCAPE_DOUBLES
        assertEquals(2, cfg.rois.size)
        // 두 ROI 모두 화면 오른쪽 절반(x>0.5)에서 시작.
        assertTrue("좌플레이트도 우측 절반에서 시작", cfg.rois[0].left >= 0.5)
        assertTrue("우플레이트는 화면 오른쪽 끝까지", cfg.rois[1].right >= 0.98)
        // 상단 띠(P12 세로밴드 확장: bottom 0.17→0.24, 장면별 y 편차 흡수. matchBest 가 인접 UI 걸러줌).
        assertTrue(cfg.rois.all { it.top <= 0.05 && it.bottom <= 0.25 })
    }

    @Test
    fun K2실측_싱글_우상단_ROI1개() {
        // P8 K2 실측: 싱글배틀은 상대 1마리 → 우상단 이름표 1개.
        // P9 K3 실측: 이름표가 살짝 아래 뜬 프레임 대비 bottom 을 0.22 로 확장(하단 클리핑 방지).
        val cfg = RoiConfig.DEFAULT_LANDSCAPE_SINGLE
        assertEquals(1, cfg.rois.size)
        val r = cfg.rois[0]
        assertTrue("우상단 영역", r.left >= 0.5 && r.top <= 0.05)
        // 상단 띠(P12 세로밴드 확장: bottom 0.22→0.30, 레터박스/장면별 y 편차 흡수. matchBest 로 안전).
        assertTrue("상단 띠", r.bottom <= 0.32)
    }

    @Test
    fun 직렬화_왕복() {
        val cfg = RoiConfig(
            listOf(
                RoiRect(0.1, 0.2, 0.3, 0.4),
                RoiRect(0.5, 0.2, 0.7, 0.4),
            ),
        )
        val s = RoiConfig.serialize(cfg)
        val parsed = RoiConfig.parse(s)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.rois.size)
        assertEquals(0.1, parsed.rois[0].left, 1e-9)
        assertEquals(0.7, parsed.rois[1].right, 1e-9)
    }

    @Test
    fun parse_빈문자열_null() {
        assertNull(RoiConfig.parse(null))
        assertNull(RoiConfig.parse(""))
        assertNull(RoiConfig.parse("   "))
    }

    @Test
    fun parse_잘못된형식_null() {
        assertNull(RoiConfig.parse("0.1,0.2,0.3")) // 값 3개
        assertNull(RoiConfig.parse("a,b,c,d"))     // 숫자 아님
    }

    @Test
    fun store_effective_오버라이드없으면_기본() {
        val store = InMemoryRoiStore()
        assertEquals(RoiConfig.default(), store.effective())
    }

    @Test
    fun store_effective_오버라이드있으면_그것() {
        val store = InMemoryRoiStore()
        val custom = RoiConfig(listOf(RoiRect(0.0, 0.0, 0.5, 0.5)))
        store.save(custom)
        assertEquals(custom, store.effective())
        store.clear()
        assertEquals(RoiConfig.default(), store.effective())
    }

    private class InMemoryRoiStore : RoiConfigStore {
        private var cfg: RoiConfig? = null
        override fun load(): RoiConfig? = cfg
        override fun save(config: RoiConfig) { cfg = config }
        override fun clear() { cfg = null }
    }
}

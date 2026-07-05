package com.pochamps.supporter

import com.pochamps.supporter.overlay.OverlayScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오버레이 카드 스케일 스냅/클램프 로직 유닛 테스트(P16, 순수 JVM).
 * 설정에서 온 임의 값이 허용 단계로 스냅되고 범위 밖은 클램프되는지 검증.
 */
class OverlayScaleTest {

    @Test
    fun 기본값은_1() {
        assertEquals(1.0f, OverlayScale.DEFAULT, 0.0001f)
    }

    @Test
    fun 정확한_단계는_그대로() {
        for (step in OverlayScale.STEPS) {
            assertEquals(step, OverlayScale.snap(step), 0.0001f)
        }
    }

    @Test
    fun 가까운_값은_가장_가까운_단계로_스냅() {
        assertEquals(0.8f, OverlayScale.snap(0.82f), 0.0001f)
        assertEquals(1.0f, OverlayScale.snap(0.95f), 0.0001f)
        assertEquals(1.0f, OverlayScale.snap(1.1f), 0.0001f)
        assertEquals(1.25f, OverlayScale.snap(1.2f), 0.0001f)
        assertEquals(1.5f, OverlayScale.snap(1.45f), 0.0001f)
    }

    @Test
    fun 최소_미만은_MIN단계로_클램프() {
        // 0.5 < MIN(0.8) → 클램프 후 스냅 → 0.8
        assertEquals(0.8f, OverlayScale.snap(0.5f), 0.0001f)
        assertEquals(0.8f, OverlayScale.snap(0f), 0.0001f)
        assertEquals(0.8f, OverlayScale.snap(-3f), 0.0001f)
    }

    @Test
    fun 최대_초과는_MAX단계로_클램프() {
        // 3.0 > MAX(1.5) → 1.5
        assertEquals(1.5f, OverlayScale.snap(3.0f), 0.0001f)
        assertEquals(1.5f, OverlayScale.snap(999f), 0.0001f)
    }

    @Test
    fun 비정상값은_기본값() {
        assertEquals(OverlayScale.DEFAULT, OverlayScale.snap(Float.NaN), 0.0001f)
        assertEquals(OverlayScale.DEFAULT, OverlayScale.snap(Float.POSITIVE_INFINITY), 0.0001f)
        assertEquals(OverlayScale.DEFAULT, OverlayScale.snap(Float.NEGATIVE_INFINITY), 0.0001f)
    }

    @Test
    fun 스냅결과는_항상_허용단계_중_하나() {
        val samples = listOf(-1f, 0.3f, 0.7f, 0.9f, 1.0f, 1.15f, 1.3f, 1.6f, 5f)
        for (s in samples) {
            assertTrue("snap($s) 는 STEPS 중 하나여야", OverlayScale.snap(s) in OverlayScale.STEPS)
        }
    }

    @Test
    fun 라벨_퍼센트() {
        assertEquals("80%", OverlayScale.label(0.8f))
        assertEquals("100%", OverlayScale.label(1.0f))
        assertEquals("125%", OverlayScale.label(1.25f))
        assertEquals("150%", OverlayScale.label(1.5f))
    }
}

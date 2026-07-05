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

    // --- P30: 연속(드래그) 클램프 + 델타 적용 ---

    @Test
    fun 연속클램프_범위내는_그대로() {
        assertEquals(0.6f, OverlayScale.clampCont(0.6f), 0.0001f)
        assertEquals(0.73f, OverlayScale.clampCont(0.73f), 0.0001f) // 스냅 안 함(연속값 보존)
        assertEquals(1.37f, OverlayScale.clampCont(1.37f), 0.0001f)
        assertEquals(2.0f, OverlayScale.clampCont(2.0f), 0.0001f)
    }

    @Test
    fun 연속클램프_범위밖은_경계로() {
        assertEquals(0.6f, OverlayScale.clampCont(0.3f), 0.0001f)
        assertEquals(0.6f, OverlayScale.clampCont(-5f), 0.0001f)
        assertEquals(2.0f, OverlayScale.clampCont(2.5f), 0.0001f)
        assertEquals(2.0f, OverlayScale.clampCont(999f), 0.0001f)
    }

    @Test
    fun 연속클램프_비정상값은_기본값() {
        assertEquals(OverlayScale.DEFAULT, OverlayScale.clampCont(Float.NaN), 0.0001f)
        assertEquals(OverlayScale.DEFAULT, OverlayScale.clampCont(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test
    fun 드래그델타_우하_확대_좌상_축소() {
        // refPx=1000. 우하로 +200,+200 → 평균 200 / 1000 = +0.2 → 1.2
        assertEquals(1.2f, OverlayScale.applyDragDelta(1.0f, 200f, 200f, 1000f), 0.0001f)
        // 좌상으로 -300,-300 → -0.3 → 0.7
        assertEquals(0.7f, OverlayScale.applyDragDelta(1.0f, -300f, -300f, 1000f), 0.0001f)
    }

    @Test
    fun 드래그델타_클램프_경계() {
        // 이미 최대에서 더 키우려 해도 CONT_MAX 로 클램프.
        assertEquals(OverlayScale.CONT_MAX, OverlayScale.applyDragDelta(2.0f, 500f, 500f, 1000f), 0.0001f)
        // 이미 최소에서 더 줄이려 해도 CONT_MIN.
        assertEquals(OverlayScale.CONT_MIN, OverlayScale.applyDragDelta(0.6f, -500f, -500f, 1000f), 0.0001f)
    }

    @Test
    fun 드래그델타_기준픽셀_0이하면_현재값_유지() {
        assertEquals(1.1f, OverlayScale.applyDragDelta(1.1f, 300f, 300f, 0f), 0.0001f)
        assertEquals(1.1f, OverlayScale.applyDragDelta(1.1f, 300f, 300f, -10f), 0.0001f)
    }

    @Test
    fun 드래그델타_현재값_비정상이면_기본값_기준() {
        // current=NaN → DEFAULT(1.0) 기준 + (100+100)/2/1000 = 0.1 → 1.1
        assertEquals(1.1f, OverlayScale.applyDragDelta(Float.NaN, 100f, 100f, 1000f), 0.0001f)
    }
}

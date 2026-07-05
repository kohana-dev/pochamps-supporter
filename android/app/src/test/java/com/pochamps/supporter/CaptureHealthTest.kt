package com.pochamps.supporter

import com.pochamps.supporter.capture.CaptureHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CaptureHealth(K1 자동 진단) 분류/지속시간/복귀/오탐억제 유닛 테스트(순수 JVM).
 * NoFrames / BlackScreen / Healthy 경계와 짧은 검정 무시를 검증한다.
 */
class CaptureHealthTest {

    private fun health(
        noFramesMs: Long = 4000,
        blackHoldMs: Long = 2500,
        blackLumaThreshold: Int = 18,
    ) = CaptureHealth(noFramesMs, blackHoldMs, blackLumaThreshold)

    // --- 초기 상태 ---

    @Test
    fun 시작전엔_healthy_중립() {
        val h = health()
        assertEquals(CaptureHealth.Health.HEALTHY, h.currentHealth())
        // start 전 evaluate 는 상태 변화 없음.
        assertNull(h.evaluate(10_000))
    }

    // --- NoFrames ---

    @Test
    fun 프레임없이_유예지나면_noFrames() {
        val h = health(noFramesMs = 4000)
        h.start(0)
        // 유예 안 → 아직 변화 없음.
        assertNull(h.evaluate(3999))
        // 유예 경과 → NoFrames.
        assertEquals(CaptureHealth.Health.NO_FRAMES, h.evaluate(4000))
    }

    @Test
    fun 프레임_끊기면_noFrames() {
        val h = health(noFramesMs = 4000)
        h.start(0)
        // 정상 프레임 한 장(밝음).
        assertNull(h.onFrame(avgLuma = 120, nowMs = 500))
        // 이후 프레임 없이 noFramesMs 초과 → NoFrames.
        assertEquals(CaptureHealth.Health.NO_FRAMES, h.evaluate(500 + 4000))
    }

    @Test
    fun noFrames_후_프레임오면_healthy_복귀() {
        val h = health(noFramesMs = 4000)
        h.start(0)
        assertEquals(CaptureHealth.Health.NO_FRAMES, h.evaluate(4000))
        // 밝은 프레임이 다시 오면 Healthy 로 복귀(상태 변화 보고).
        assertEquals(CaptureHealth.Health.HEALTHY, h.onFrame(avgLuma = 130, nowMs = 4100))
    }

    // --- BlackScreen ---

    @Test
    fun 어두운프레임_지속되면_blackScreen() {
        val h = health(blackHoldMs = 2500, blackLumaThreshold = 18)
        h.start(0)
        // 어두운 프레임(FLAG_SECURE) 연속.
        assertNull(h.onFrame(avgLuma = 3, nowMs = 0))
        assertNull(h.onFrame(avgLuma = 2, nowMs = 1000))
        // 아직 지속 안 참(2000<2500).
        assertNull(h.onFrame(avgLuma = 4, nowMs = 2000))
        // 지속 경과 → BlackScreen.
        assertEquals(CaptureHealth.Health.BLACK_SCREEN, h.onFrame(avgLuma = 1, nowMs = 2600))
    }

    @Test
    fun blackScreen_경계_정확히() {
        val h = health(blackHoldMs = 2500)
        h.start(0)
        assertNull(h.onFrame(avgLuma = 0, nowMs = 100))
        // 정확히 blackHoldMs 지점(100+2500=2600) → 확정.
        assertEquals(CaptureHealth.Health.BLACK_SCREEN, h.onFrame(avgLuma = 0, nowMs = 2600))
    }

    @Test
    fun 짧은검정은_무시_오탐억제() {
        // 로딩/전환 순간의 일시적 검정(밝은 프레임이 곧 옴) → BlackScreen 판정 안 함.
        val h = health(blackHoldMs = 2500)
        h.start(0)
        assertNull(h.onFrame(avgLuma = 2, nowMs = 0))
        assertNull(h.onFrame(avgLuma = 3, nowMs = 1000))
        // 밝은 프레임 1장 → 어두운 구간 리셋.
        assertNull(h.onFrame(avgLuma = 140, nowMs = 1500))
        // 다시 어두워져도 지속 타이머는 여기서부터 새로 시작 → 2500 안 넘으면 무시.
        assertNull(h.onFrame(avgLuma = 2, nowMs = 2000))
        assertNull(h.onFrame(avgLuma = 2, nowMs = 3000)) // 1500부터 1000ms<2500
        assertEquals(CaptureHealth.Health.HEALTHY, h.currentHealth())
    }

    @Test
    fun blackScreen_후_밝아지면_healthy_복귀() {
        val h = health(blackHoldMs = 2500)
        h.start(0)
        assertNull(h.onFrame(avgLuma = 0, nowMs = 0))
        assertEquals(CaptureHealth.Health.BLACK_SCREEN, h.onFrame(avgLuma = 0, nowMs = 2600))
        // 밝은 프레임 → Healthy 복귀(안내 카드 자동 해제 트리거).
        assertEquals(CaptureHealth.Health.HEALTHY, h.onFrame(avgLuma = 150, nowMs = 2700))
    }

    @Test
    fun 임계바로위_휘도는_어둡지않음() {
        // 경계: threshold 초과 휘도는 밝은 프레임으로 취급 → 검정 지속 안 쌓임.
        val h = health(blackHoldMs = 1000, blackLumaThreshold = 18)
        h.start(0)
        assertNull(h.onFrame(avgLuma = 19, nowMs = 0))
        assertNull(h.onFrame(avgLuma = 19, nowMs = 2000))
        assertEquals(CaptureHealth.Health.HEALTHY, h.currentHealth())
    }

    // --- 상태 변화 보고(중복 억제) ---

    @Test
    fun 동일상태는_null_반환() {
        val h = health(blackHoldMs = 1000)
        h.start(0)
        assertNull(h.onFrame(avgLuma = 0, nowMs = 0))
        assertEquals(CaptureHealth.Health.BLACK_SCREEN, h.onFrame(avgLuma = 0, nowMs = 1100))
        // 계속 검정 — 이미 BLACK_SCREEN 보고했으므로 null(중복 억제).
        assertNull(h.onFrame(avgLuma = 0, nowMs = 1500))
    }

    // --- averageLuma 헬퍼 ---

    @Test
    fun averageLuma_평균계산() {
        assertEquals(100, CaptureHealth.averageLuma(intArrayOf(50, 150)))
        assertEquals(0, CaptureHealth.averageLuma(intArrayOf()))
        assertEquals(255, CaptureHealth.averageLuma(intArrayOf(255, 255, 255)))
    }

    // --- reset ---

    @Test
    fun reset_후_중립() {
        val h = health(blackHoldMs = 1000)
        h.start(0)
        assertNull(h.onFrame(avgLuma = 0, nowMs = 0))
        assertEquals(CaptureHealth.Health.BLACK_SCREEN, h.onFrame(avgLuma = 0, nowMs = 1100))
        h.reset()
        assertEquals(CaptureHealth.Health.HEALTHY, h.currentHealth())
    }

    // --- P24: 헬스=정상시 안내 카드 미표시 판정 ---

    @Test
    fun 정상이면_안내카드_미표시() {
        // 인식 성공 평상시(HEALTHY) → 안내 카드 없음(카드만 보여야 함).
        assertFalse(CaptureHealth.shouldShowCard(CaptureHealth.Health.HEALTHY))
    }

    @Test
    fun 문제일때만_안내카드_표시() {
        assertTrue(CaptureHealth.shouldShowCard(CaptureHealth.Health.BLACK_SCREEN))
        assertTrue(CaptureHealth.shouldShowCard(CaptureHealth.Health.NO_FRAMES))
    }
}

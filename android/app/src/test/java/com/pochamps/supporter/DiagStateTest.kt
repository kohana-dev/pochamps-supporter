package com.pochamps.supporter

import com.pochamps.supporter.capture.DiagState
import com.pochamps.supporter.capture.OcrRateMeter
import com.pochamps.supporter.capture.SlotDiag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 진단 패널(P14 adb 대체) 순수 상태/포매팅 로직 유닛 테스트.
 * 원인 분류(빈텍스트/미매칭/매칭), 스트립 한줄 포맷, 경과시간, OCR 빈도 계수기를 검증.
 */
class DiagStateTest {

    @Test
    fun outcome_매칭이면_MATCHED() {
        val d = SlotDiag(0, listOf("갸라도스"), matchedRoot = "gyarados", editDistance = 1, atMs = 100)
        assertEquals(SlotDiag.Outcome.MATCHED, d.outcome)
    }

    @Test
    fun outcome_빈라인이면_EMPTY_TEXT() {
        val d = SlotDiag(0, emptyList(), matchedRoot = null, editDistance = null, atMs = 100)
        assertEquals(SlotDiag.Outcome.EMPTY_TEXT, d.outcome)
    }

    @Test
    fun outcome_라인있는데_미매칭이면_UNMATCHED_TEXT() {
        val d = SlotDiag(0, listOf("MOVE TIME 45"), matchedRoot = null, editDistance = null, atMs = 100)
        assertEquals(SlotDiag.Outcome.UNMATCHED_TEXT, d.outcome)
    }

    @Test
    fun formatSlot_매칭_root와_거리표시() {
        val d = SlotDiag(0, listOf("갸라도스"), "gyarados", 1, 100)
        val s = DiagState.formatSlot(d)
        assertTrue(s.startsWith("S0 "))
        assertTrue(s.contains("gyarados"))
        assertTrue(s.contains("d1"))
    }

    @Test
    fun formatSlot_빈텍스트_원인문구() {
        val d = SlotDiag(1, emptyList(), null, null, 100)
        assertEquals("S1 OCR:빈텍스트", DiagState.formatSlot(d))
    }

    @Test
    fun formatSlot_미매칭_원문표시() {
        val d = SlotDiag(0, listOf("abcd"), null, null, 100)
        val s = DiagState.formatSlot(d)
        assertTrue(s.contains("미매칭"))
        assertTrue(s.contains("abcd"))
    }

    @Test
    fun formatSlot_긴텍스트_말줄임() {
        val long = "a".repeat(100)
        val d = SlotDiag(0, listOf(long), null, null, 100)
        val s = DiagState.formatSlot(d)
        // 원문이 MAX_TEXT 이하로 잘림(따옴표 안).
        assertTrue(s.length < long.length)
        assertTrue(s.contains("…"))
    }

    @Test
    fun formatLastSeen_인식없음() {
        val state = DiagState(lastRecognitionAtMs = 0L, nowMs = 5000)
        assertEquals("인식 없음", DiagState.formatLastSeen(state))
    }

    @Test
    fun formatLastSeen_방금과_n초전() {
        assertEquals("방금 인식", DiagState.formatLastSeen(DiagState(lastRecognitionAtMs = 5000, nowMs = 5300)))
        assertEquals("3초 전 인식", DiagState.formatLastSeen(DiagState(lastRecognitionAtMs = 2000, nowMs = 5000)))
    }

    @Test
    fun formatRate_회당초() {
        val s = DiagState.formatRate(DiagState(ocrRunsPerSec = 1.08))
        assertTrue(s.contains("1.1"))
        assertTrue(s.contains("회/s"))
    }

    @Test
    fun rateMeter_윈도우내_실행수_기반() {
        val meter = OcrRateMeter(windowMs = 1000L)
        // 1초 윈도우에 3회 → 3.0회/s.
        meter.record(0)
        meter.record(300)
        meter.record(600)
        assertEquals(3.0, meter.ratePerSec(600), 1e-9)
    }

    @Test
    fun rateMeter_오래된실행_윈도우밖으로_제거() {
        val meter = OcrRateMeter(windowMs = 1000L)
        meter.record(0)
        meter.record(200)
        // now=1500 → cutoff=500, 0/200 둘 다 밖 → 0회.
        assertEquals(0.0, meter.ratePerSec(1500), 1e-9)
    }

    @Test
    fun rateMeter_이력없으면_0() {
        assertEquals(0.0, OcrRateMeter().ratePerSec(1000), 1e-9)
    }

    @Test
    fun rateMeter_reset() {
        val meter = OcrRateMeter(windowMs = 1000L)
        meter.record(0)
        meter.reset()
        assertEquals(0.0, meter.ratePerSec(0), 1e-9)
    }

    @Test
    fun diagState_슬롯정렬_다중슬롯() {
        val state = DiagState(
            slots = mapOf(
                1 to SlotDiag(1, listOf("Charizard"), "charizard", 0, 100),
                0 to SlotDiag(0, emptyList(), null, null, 100),
            ),
        )
        // keys.sorted() 로 0,1 순 표시가 가능해야 함(포맷은 UI 담당, 여기선 데이터만 검증).
        assertEquals(listOf(0, 1), state.slots.keys.sorted())
        assertEquals(SlotDiag.Outcome.EMPTY_TEXT, state.slots[0]!!.outcome)
        assertEquals(SlotDiag.Outcome.MATCHED, state.slots[1]!!.outcome)
    }
}

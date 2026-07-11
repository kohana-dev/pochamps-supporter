package com.pochamps.supporter

import com.pochamps.supporter.overlay.AbilityDetail
import com.pochamps.supporter.overlay.CardStage
import com.pochamps.supporter.overlay.ExpandExclusive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P36] 특성 상세 서브상태 전환 순수 로직 검증(Android 의존성 없음).
 * 열기 → 단계 순환/자동 축소가 상세를 닫지 않음 → 뒤로 시 직전 단계로 복귀, 를 상태 맵으로 검증.
 */
class AbilityDetailTest {

    @Test
    fun open_then_close_roundtrip() {
        var open = emptyMap<Int, String>()
        assertFalse("초기엔 미열림", AbilityDetail.isOpen(open, 0))

        open = AbilityDetail.open(open, 0, "rough-skin")
        assertTrue("열림", AbilityDetail.isOpen(open, 0))
        assertEquals("rough-skin", open[0])

        open = AbilityDetail.close(open, 0)
        assertFalse("뒤로 → 닫힘", AbilityDetail.isOpen(open, 0))
    }

    @Test
    fun 상세열림중_단계순환_차단() {
        val open = AbilityDetail.open(emptyMap(), 0, "sand-veil")
        // 상세가 열린 슬롯은 순환 불가(상세 유지). 다른 슬롯은 순환 가능.
        assertFalse("열린 슬롯 순환 차단", AbilityDetail.allowsStageCycle(open, 0))
        assertTrue("다른 슬롯은 순환 허용", AbilityDetail.allowsStageCycle(open, 1))
    }

    @Test
    fun 상세열림중_자동축소_제외() {
        val open = AbilityDetail.open(emptyMap(), 0, "rough-skin")
        // 슬롯 0(상세 열림)·1(상세 없음)이 둘 다 자동 축소 후보라도, 0은 제외되고 1만 축소된다.
        val collapsible = AbilityDetail.collapsible(listOf(0, 1), open)
        assertEquals(listOf(1), collapsible)
    }

    @Test
    fun 뒤로시_직전EXPANDED단계로_복귀() {
        // stage 는 상세 열림/닫힘과 무관하게 얼려 둔다(호출부가 순환·축소를 막으므로).
        // 시나리오: EXPANDED 에서 특성 상세를 열고 → 자동 축소가 돌아도 상세 슬롯은 제외 → 뒤로 시 EXPANDED 유지.
        val stages = mapOf(0 to CardStage.EXPANDED)
        var open = AbilityDetail.open(emptyMap(), 0, "rough-skin")

        // 자동 축소 시도: 상세 열린 0 은 대상에서 빠지므로 stage 는 그대로 EXPANDED.
        val toCollapse = AbilityDetail.collapsible(stages.keys, open)
        assertTrue("EXPANDED 상세 슬롯은 축소 대상 아님", toCollapse.isEmpty())

        // 뒤로 → 상세만 닫히고 stage 는 EXPANDED 로 복귀.
        open = AbilityDetail.close(open, 0)
        assertFalse(AbilityDetail.isOpen(open, 0))
        assertEquals(CardStage.EXPANDED, stages[0])
    }

    @Test
    fun 다른포켓몬교체_시나리오는_호출부가_close로_처리() {
        // 렌더러 updateSlot 이 key 변경 시 close 를 호출한다는 계약을 순수 로직 수준에서 표현.
        var open = AbilityDetail.open(emptyMap(), 0, "rough-skin")
        // 교체 감지 → close.
        open = AbilityDetail.close(open, 0)
        assertFalse("교체 시 상세 무효화", AbilityDetail.isOpen(open, 0))
        // ExpandExclusive 와 독립적으로 동작(서로 다른 관심사) — 단계 전환 로직은 그대로.
        val next = ExpandExclusive.apply(mapOf(0 to CardStage.CARD), 0, CardStage.EXPANDED)
        assertEquals(CardStage.EXPANDED, next[0])
    }
}

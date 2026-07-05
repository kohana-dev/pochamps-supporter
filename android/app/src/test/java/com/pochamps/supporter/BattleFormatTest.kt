package com.pochamps.supporter

import com.pochamps.supporter.capture.PipelineAction
import com.pochamps.supporter.capture.PipelineDecider
import com.pochamps.supporter.capture.RoiConfig
import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.data.Candidate
import com.pochamps.supporter.matching.MatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P20 싱글/더블 형식 전환 관련 순수 로직 테스트:
 *  - 형식별 슬롯 수/유지 최대 슬롯 인덱스(더블→싱글 정리 근거).
 *  - 형식별 활성 ROI 밴드 수(싱글 1 / 더블 2).
 *  - 형식 전환 시 Decider 리셋으로 슬롯 판정/핀 고착이 풀리는지.
 */
class BattleFormatTest {

    @Test
    fun 형식별_슬롯수() {
        assertEquals(1, BattleFormat.SINGLES.slotCount)
        assertEquals(2, BattleFormat.DOUBLES.slotCount)
        // 유지 최대 슬롯 인덱스: 싱글 0(슬롯0만), 더블 1(슬롯0/1).
        assertEquals(0, BattleFormat.SINGLES.maxSlotIndex)
        assertEquals(1, BattleFormat.DOUBLES.maxSlotIndex)
    }

    @Test
    fun 형식별_활성ROI_밴드수() {
        assertEquals(1, RoiConfig.activeDefault(BattleFormat.SINGLES).rois.size)
        assertEquals(2, RoiConfig.activeDefault(BattleFormat.DOUBLES).rois.size)
    }

    @Test
    fun slug_왕복() {
        assertEquals("singles", BattleFormat.SINGLES.slug)
        assertEquals("doubles", BattleFormat.DOUBLES.slug)
    }

    /**
     * 슬롯 정리 시뮬레이션: 더블에서 슬롯 0/1 카드가 있을 때 싱글로 바꾸면 슬롯 1(> maxSlotIndex)이
     * 제거 대상. 오버레이 pruneSlotsAbove(maxSlotIndex) 로직과 동일한 규칙을 순수 계산으로 검증.
     */
    @Test
    fun 더블에서_싱글전환_슬롯1_제거대상() {
        val slots = listOf(0, 1)
        val keep = BattleFormat.SINGLES.maxSlotIndex
        val toRemove = slots.filter { it > keep }
        assertEquals(listOf(1), toRemove)

        // 반대로 더블 유지 시 아무것도 제거되지 않는다.
        val keepDoubles = BattleFormat.DOUBLES.maxSlotIndex
        assertTrue(slots.filter { it > keepDoubles }.isEmpty())
    }

    /**
     * 형식 전환 시 Decider 상태 리셋: 더블 슬롯1에 핀을 걸어 둔 뒤 reset() 하면 핀이 풀려
     * 이후 인식이 슬롯0을 정상 갱신한다(형식 전환 고착 방지). resetForFormatChange 가 내부적으로
     * decider.reset() 을 호출하는 것과 동일한 효과를 Decider 단위로 검증.
     */
    @Test
    fun 형식전환_decider리셋_핀해제() {
        val decider = PipelineDecider()
        decider.pin(1, "someKey")
        assertTrue(decider.isPinned(1))

        // 형식 전환 → 리셋.
        decider.reset()
        assertFalse(decider.isPinned(1))

        // 리셋 후 슬롯0 인식이 정상 카드 갱신.
        val match = MatchResult.Matched(
            root = "garchomp",
            candidates = listOf(Candidate(key = "garchomp", usage_rank = null)),
            matchedKey = "garchomp",
            editDistance = 0,
        )
        val action = decider.decide(0, match)
        assertTrue(action is PipelineAction.UpdateCard)
        assertEquals("garchomp", (action as PipelineAction.UpdateCard).key)
    }
}

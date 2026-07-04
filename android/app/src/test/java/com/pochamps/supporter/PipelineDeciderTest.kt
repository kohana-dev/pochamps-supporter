package com.pochamps.supporter

import com.pochamps.supporter.capture.PipelineAction
import com.pochamps.supporter.capture.PipelineDecider
import com.pochamps.supporter.data.Candidate
import com.pochamps.supporter.matching.MatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PipelineDecider 판정 로직 유닛 테스트(순수 JVM, OCR/매칭 mock).
 * DESIGN.md 3장 5단계 규칙 검증: 미매칭 유지 / 후보 1 / 후보 2+ / 연속 동일 스킵 / 더블 슬롯 독립.
 */
class PipelineDeciderTest {

    private fun candidate(key: String, rank: Double? = null) =
        Candidate(key = key, usage_rank = rank)

    private fun matched(vararg keys: String): MatchResult.Matched =
        MatchResult.Matched(
            root = keys.first(),
            candidates = keys.map { candidate(it) },
            matchedKey = keys.first(),
            editDistance = 0,
        )

    @Test
    fun 미매칭은_기존카드유지() {
        val d = PipelineDecider()
        assertEquals(PipelineAction.KeepCurrent, d.decide(0, MatchResult.NoMatch))
    }

    @Test
    fun 후보1개는_UpdateCard_후보없음() {
        val d = PipelineDecider()
        val action = d.decide(0, matched("garchomp"))
        assertTrue(action is PipelineAction.UpdateCard)
        action as PipelineAction.UpdateCard
        assertEquals("garchomp", action.key)
        assertFalse(action.hasMoreCandidates)
        assertEquals(1, action.candidateCount)
    }

    @Test
    fun 후보2개는_최상위표시_후보있음플래그() {
        val d = PipelineDecider()
        val action = d.decide(0, matched("arcanine", "arcanine-hisui"))
        assertTrue(action is PipelineAction.UpdateCard)
        action as PipelineAction.UpdateCard
        // best = 첫 후보(매칭기가 usage_rank 순 정렬해 넘김).
        assertEquals("arcanine", action.key)
        assertTrue(action.hasMoreCandidates)
        assertEquals(2, action.candidateCount)
    }

    @Test
    fun 같은포켓몬_연속인식은_스킵() {
        val d = PipelineDecider()
        val first = d.decide(0, matched("garchomp"))
        assertTrue(first is PipelineAction.UpdateCard)
        // 같은 key 재인식 → NoChange.
        val second = d.decide(0, matched("garchomp"))
        assertEquals(PipelineAction.NoChange, second)
    }

    @Test
    fun 다른포켓몬으로_바뀌면_다시갱신() {
        val d = PipelineDecider()
        d.decide(0, matched("garchomp"))
        val changed = d.decide(0, matched("dragonite"))
        assertTrue(changed is PipelineAction.UpdateCard)
        assertEquals("dragonite", (changed as PipelineAction.UpdateCard).key)
    }

    @Test
    fun 더블배틀_슬롯은_독립() {
        val d = PipelineDecider()
        // 두 ROI 에 같은 key 라도 슬롯이 다르면 각각 갱신.
        val a = d.decide(0, matched("garchomp"))
        val b = d.decide(1, matched("garchomp"))
        assertTrue(a is PipelineAction.UpdateCard)
        assertTrue(b is PipelineAction.UpdateCard)
        assertEquals(0, (a as PipelineAction.UpdateCard).roiIndex)
        assertEquals(1, (b as PipelineAction.UpdateCard).roiIndex)
    }

    @Test
    fun reset_후_같은key도_다시갱신() {
        val d = PipelineDecider()
        d.decide(0, matched("garchomp"))
        d.reset()
        val after = d.decide(0, matched("garchomp"))
        assertTrue(after is PipelineAction.UpdateCard)
    }
}

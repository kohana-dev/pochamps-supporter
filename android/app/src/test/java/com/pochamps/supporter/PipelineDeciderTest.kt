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

    // --- P18 강제 재인식(resetSlot) ---

    @Test
    fun resetSlot_후_같은key도_다시갱신() {
        // 강제 재인식: 슬롯 판정 상태를 지우면 직전과 동일 key 라도 다시 UpdateCard(즉시 반영).
        val d = PipelineDecider()
        assertTrue(d.decide(0, matched("garchomp")) is PipelineAction.UpdateCard)
        // 같은 key 재인식은 원래 스킵.
        assertEquals(PipelineAction.NoChange, d.decide(0, matched("garchomp")))
        // resetSlot 후엔 같은 key 라도 다시 갱신(오인식 고착 탈출 시 카드 재조립 보장).
        d.resetSlot(0)
        assertTrue(d.decide(0, matched("garchomp")) is PipelineAction.UpdateCard)
    }

    @Test
    fun resetSlot_은_해당슬롯만_영향() {
        // 더블배틀 개별 ↻: 슬롯0 리셋이 슬롯1 판정 상태를 건드리지 않아야 한다.
        val d = PipelineDecider()
        d.decide(0, matched("garchomp"))
        d.decide(1, matched("dragonite"))
        d.resetSlot(0)
        // 슬롯1 은 여전히 같은 key 스킵(상태 보존).
        assertEquals(PipelineAction.NoChange, d.decide(1, matched("dragonite")))
        // 슬롯0 은 리셋됐으므로 같은 key 라도 갱신.
        assertTrue(d.decide(0, matched("garchomp")) is PipelineAction.UpdateCard)
    }

    @Test
    fun resetSlot_은_기억된_후보선택도_초기화() {
        // 잘못 기억된 선택도 강제 재인식으로 초기화 → best(최상위)로 복귀.
        val d = PipelineDecider()
        d.decide(0, matched("arcanine", "arcanine-hisui"))
        // 유저가 히스이 선택 기억.
        d.rememberChoice(0, "arcanine", "arcanine-hisui")
        val remembered = d.decide(0, matched("arcanine", "arcanine-hisui"))
        assertEquals("arcanine-hisui", (remembered as PipelineAction.UpdateCard).key)
        // resetSlot 후 재인식 → 기억이 지워져 best(arcanine)로.
        d.resetSlot(0)
        val after = d.decide(0, matched("arcanine", "arcanine-hisui"))
        assertEquals("arcanine", (after as PipelineAction.UpdateCard).key)
    }

    @Test
    fun resetSlot_은_핀은_건드리지않음() {
        // resetSlot 은 판정 상태만 초기화 — 핀은 호출부(forceRescan) 정책에 맡긴다.
        val d = PipelineDecider()
        d.pin(0, "garchomp")
        assertTrue(d.isPinned(0))
        d.resetSlot(0)
        assertTrue("resetSlot 만으로 핀이 풀리면 안 됨", d.isPinned(0))
        // 핀 유지 중엔 인식 결과 무시(NoChange).
        assertEquals(PipelineAction.NoChange, d.decide(0, matched("dragonite")))
    }

    @Test
    fun 핀해제_후_resetSlot_이면_재인식_반영() {
        // forceRescan 의 실동작 재현: unpin → resetSlot → 다음 인식이 새 포켓몬으로 갱신.
        val d = PipelineDecider()
        d.pin(0, "garchomp")
        d.unpin(0)
        d.resetSlot(0)
        val after = d.decide(0, matched("dragonite"))
        assertTrue(after is PipelineAction.UpdateCard)
        assertEquals("dragonite", (after as PipelineAction.UpdateCard).key)
    }
}

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
 * P4: 후보 선택 기억 + 수동 핀 로직 테스트(순수 JVM).
 * DESIGN.md 5장: 후보 선택은 같은 표시명(root) 유지 동안 기억, 수동 핀은 파이프라인이 덮어쓰지 않음.
 */
class PipelineDeciderChoiceTest {

    private fun matched(root: String, vararg keys: String): MatchResult.Matched =
        MatchResult.Matched(
            root = root,
            candidates = keys.map { Candidate(key = it) },
            matchedKey = root,
            editDistance = 0,
        )

    @Test
    fun 후보선택_기억_같은root_재인식시_유지() {
        val d = PipelineDecider()
        // 최초: 후보 2개 → 최상위(arcanine).
        val first = d.decide(0, matched("arcanine", "arcanine", "arcanine-hisui")) as PipelineAction.UpdateCard
        assertEquals("arcanine", first.key)
        assertEquals("arcanine", first.root)

        // 유저가 히스이폼 선택.
        d.rememberChoice(0, "arcanine", "arcanine-hisui")

        // 같은 root 재인식 → 기억된 선택(히스이) 유지, 최상위로 덮어쓰지 않음.
        val again = d.decide(0, matched("arcanine", "arcanine", "arcanine-hisui")) as PipelineAction.UpdateCard
        assertEquals("arcanine-hisui", again.key)
        // 후보가 여럿이므로 "바꾸기" 진입점은 계속 노출.
        assertTrue(again.hasMoreCandidates)
    }

    @Test
    fun 후보선택_기억은_다른root면_무시() {
        val d = PipelineDecider()
        d.decide(0, matched("arcanine", "arcanine", "arcanine-hisui"))
        d.rememberChoice(0, "arcanine", "arcanine-hisui")

        // 다른 root(예: growlithe) 인식 → arcanine 선택 기억은 적용 안 됨(최상위 표시).
        val other = d.decide(0, matched("growlithe", "growlithe", "growlithe-hisui")) as PipelineAction.UpdateCard
        assertEquals("growlithe", other.key)
    }

    @Test
    fun 후보선택_기억은_슬롯별_독립() {
        val d = PipelineDecider()
        d.rememberChoice(0, "arcanine", "arcanine-hisui")
        // 슬롯 1 은 기억 없음 → 최상위.
        val slot1 = d.decide(1, matched("arcanine", "arcanine", "arcanine-hisui")) as PipelineAction.UpdateCard
        assertEquals("arcanine", slot1.key)
    }

    @Test
    fun 핀_설정시_인식결과_무시() {
        val d = PipelineDecider()
        d.pin(0, "garchomp")
        assertTrue(d.isPinned(0))
        // 핀 상태에선 다른 포켓몬 인식돼도 슬롯 갱신 안 함(NoChange).
        val action = d.decide(0, matched("dragonite", "dragonite"))
        assertEquals(PipelineAction.NoChange, action)
    }

    @Test
    fun 핀_해제후_인식_다시반영() {
        val d = PipelineDecider()
        d.pin(0, "garchomp")
        d.unpin(0)
        assertFalse(d.isPinned(0))
        val action = d.decide(0, matched("dragonite", "dragonite")) as PipelineAction.UpdateCard
        assertEquals("dragonite", action.key)
    }

    @Test
    fun 핀은_슬롯별_독립() {
        val d = PipelineDecider()
        d.pin(0, "garchomp")
        // 슬롯 1 은 핀 없음 → 정상 갱신.
        val slot1 = d.decide(1, matched("dragonite", "dragonite")) as PipelineAction.UpdateCard
        assertEquals("dragonite", slot1.key)
    }

    @Test
    fun reset은_선택기억과_핀_모두초기화() {
        val d = PipelineDecider()
        d.rememberChoice(0, "arcanine", "arcanine-hisui")
        d.pin(1, "garchomp")
        d.reset()
        assertFalse(d.isPinned(1))
        // arcanine 기억도 사라져 최상위 표시.
        val a = d.decide(0, matched("arcanine", "arcanine", "arcanine-hisui")) as PipelineAction.UpdateCard
        assertEquals("arcanine", a.key)
    }
}

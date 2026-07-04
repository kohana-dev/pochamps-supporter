package com.pochamps.supporter

import com.pochamps.supporter.capture.PipelineAction
import com.pochamps.supporter.capture.PipelineDecider
import com.pochamps.supporter.data.Candidate
import com.pochamps.supporter.matching.MatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P11 — 저신뢰 전환 히스테리시스 유닛 테스트.
 *
 * 움직이는 배틀 영상에서 카메라 회전·기술 이펙트 프레임의 OCR 이 **엉뚱한 다른 포켓몬**으로
 * 약매칭(editDistance 큼)될 때, 단발 오인식이 카드를 뒤집지 않고(깜빡임 방지),
 * 연속 확인이 있을 때만 전환됨을 검증한다. 정상 이름표(저편집거리)는 즉시 전환.
 */
class PipelineDeciderHysteresisTest {

    private fun candidate(key: String) = Candidate(key = key, usage_rank = null)

    /** editDistance 지정 가능한 매칭 헬퍼. */
    private fun matched(root: String, editDistance: Int): MatchResult.Matched =
        MatchResult.Matched(
            root = root,
            candidates = listOf(candidate(root)),
            matchedKey = root,
            editDistance = editDistance,
        )

    @Test
    fun 최초취득은_약매칭이라도_즉시표시() {
        val d = PipelineDecider()
        // 아직 카드 없음 → 첫 정보는 약매칭(editDist 2)이라도 바로 보여준다.
        val action = d.decide(0, matched("hippowdon", editDistance = 2))
        assertTrue(action is PipelineAction.UpdateCard)
        assertEquals("hippowdon", (action as PipelineAction.UpdateCard).key)
    }

    @Test
    fun 저신뢰_단발_다른포켓몬_오인식은_카드유지() {
        val d = PipelineDecider()
        // 안정적으로 hippowdon 표시 중(고신뢰).
        assertTrue(d.decide(0, matched("hippowdon", editDistance = 0)) is PipelineAction.UpdateCard)
        // 이펙트 프레임에서 약매칭으로 다른 포켓몬 1회 오인식 → 아직 교체 안 함(깜빡임 방지).
        val spam = d.decide(0, matched("garchomp", editDistance = 2))
        assertEquals(PipelineAction.KeepCurrent, spam)
    }

    @Test
    fun 저신뢰_연속2회_동일이면_전환() {
        val d = PipelineDecider()
        d.decide(0, matched("hippowdon", editDistance = 0))
        // 약매칭 1회차 → 유지.
        assertEquals(PipelineAction.KeepCurrent, d.decide(0, matched("garchomp", editDistance = 2)))
        // 약매칭 2회차(연속 동일 root) → 전환 확정.
        val switched = d.decide(0, matched("garchomp", editDistance = 2))
        assertTrue(switched is PipelineAction.UpdateCard)
        assertEquals("garchomp", (switched as PipelineAction.UpdateCard).key)
    }

    @Test
    fun 고신뢰_다른포켓몬_전환은_즉시() {
        val d = PipelineDecider()
        d.decide(0, matched("hippowdon", editDistance = 0))
        // 정상 이름표(editDist 0)로 실제 포켓몬 교체 → 지연 없이 즉시 전환.
        val switched = d.decide(0, matched("dragonite", editDistance = 0))
        assertTrue(switched is PipelineAction.UpdateCard)
        assertEquals("dragonite", (switched as PipelineAction.UpdateCard).key)
    }

    @Test
    fun 편집거리1_저편집매칭은_고신뢰로_즉시전환() {
        val d = PipelineDecider()
        d.decide(0, matched("hippowdon", editDistance = 0))
        // 한글 1자 오인식 등 editDist 1 = 정상 인식 → 즉시 전환(CONFIDENT_EDIT_DISTANCE=1).
        val switched = d.decide(0, matched("gyarados", editDistance = 1))
        assertTrue(switched is PipelineAction.UpdateCard)
        assertEquals("gyarados", (switched as PipelineAction.UpdateCard).key)
    }

    @Test
    fun 저신뢰_오인식_흔들리면_카운트리셋되어_유지() {
        val d = PipelineDecider()
        d.decide(0, matched("hippowdon", editDistance = 0))
        // 약매칭이 매번 다른 포켓몬으로 흔들리면(A,B,A) 연속 확인이 안 돼 계속 유지.
        assertEquals(PipelineAction.KeepCurrent, d.decide(0, matched("garchomp", editDistance = 2)))
        assertEquals(PipelineAction.KeepCurrent, d.decide(0, matched("dragonite", editDistance = 2)))
        assertEquals(PipelineAction.KeepCurrent, d.decide(0, matched("garchomp", editDistance = 2)))
    }

    @Test
    fun 정상프레임_복귀하면_대기중이던_저신뢰전환_취소() {
        val d = PipelineDecider()
        d.decide(0, matched("hippowdon", editDistance = 0))
        // 약매칭 1회(pending) → 유지.
        assertEquals(PipelineAction.KeepCurrent, d.decide(0, matched("garchomp", editDistance = 2)))
        // 원래 포켓몬 정상 재인식(editDist 0, 같은 key) → NoChange, pending 정리.
        assertEquals(PipelineAction.NoChange, d.decide(0, matched("hippowdon", editDistance = 0)))
        // 이후 약매칭 1회는 다시 처음부터 → 유지(pending 이 리셋됐으므로 1회론 전환 안 됨).
        assertEquals(PipelineAction.KeepCurrent, d.decide(0, matched("garchomp", editDistance = 2)))
    }

    @Test
    fun 더블배틀_슬롯별_히스테리시스_독립() {
        val d = PipelineDecider()
        d.decide(0, matched("typhlosion", editDistance = 0))
        d.decide(1, matched("charizard", editDistance = 0))
        // 슬롯0 약매칭 오인식은 슬롯0만 걸러지고 슬롯1 은 영향 없음.
        assertEquals(PipelineAction.KeepCurrent, d.decide(0, matched("torkoal", editDistance = 2)))
        // 슬롯1 정상 전환은 즉시.
        val s1 = d.decide(1, matched("torkoal", editDistance = 0))
        assertTrue(s1 is PipelineAction.UpdateCard)
    }
}

package com.pochamps.supporter

import com.pochamps.supporter.matching.MatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P12 ROI 강건화] 다중 라인 매칭(matchBest) 검증.
 *
 * ROI 밴드가 넓어져 이름표 + 인접 UI 텍스트("MOVE TIME 45" 등)가 함께 크롭돼도,
 * 사전 매칭이 UI 라인을 걸러내고 실제 종족명 라인을 채택해야 한다.
 * (실측 근거: 밴드 확장 시 pickNameLine 은 'MOVE TIME 45' 를 이름으로 오선택 → NoMatch.
 *  matchBest 는 같은 라인 집합에서 'Sylveon' 을 editDist 0 로 채택.)
 */
class MatchBestTest {

    private val repo = TestData.repository()

    @Test
    fun 이름표_인접UI_함께있어도_종족명채택() {
        val lines = listOf("MOVE TIME 45", "Sylveon", "Battle Info", "100%")
        val m = repo.matchBest(lines)
        assertTrue("Matched 이어야 함", m is MatchResult.Matched)
        assertEquals("sylveon", (m as MatchResult.Matched).root)
        assertEquals(0, m.editDistance)
    }

    @Test
    fun 순서무관_최소editDistance_라인채택() {
        // 약매칭 라인(가라도스, d=1)과 완전일치 라인이 섞여도 완전일치 우선.
        val lines = listOf("Gyarodos", "Gyarados")
        val m = repo.matchBest(lines)
        assertTrue(m is MatchResult.Matched)
        assertEquals("gyarados", (m as MatchResult.Matched).root)
        assertEquals(0, m.editDistance)
    }

    @Test
    fun 매칭되는라인_하나도없으면_NoMatch() {
        val lines = listOf("MOVE TIME 45", "Battle Info", "zzzzzzzz")
        assertEquals(MatchResult.NoMatch, repo.matchBest(lines))
    }

    @Test
    fun 빈리스트_NoMatch() {
        assertEquals(MatchResult.NoMatch, repo.matchBest(emptyList()))
    }

    @Test
    fun 단일_종족명라인_기존동작유지() {
        val m = repo.matchBest(listOf("Hippowdon"))
        assertTrue(m is MatchResult.Matched)
        assertEquals("hippowdon", (m as MatchResult.Matched).root)
    }
}

package com.pochamps.supporter

import com.pochamps.supporter.matching.MatchResult
import com.pochamps.supporter.matching.NameMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NameMatcher — 정규화/편집거리/lookup 매칭 검증(실데이터 기반). */
class NameMatcherTest {

    private val matcher: NameMatcher by lazy { TestData.repository().nameMatcher }

    @Test
    fun normalize_removesSpaceAndSymbols_lowercases() {
        assertEquals("garchomp", NameMatcher.normalize("  Gar chomp!  "))
        assertEquals("한카리아스", NameMatcher.normalize("한카리아스"))
        assertEquals("mrmime", NameMatcher.normalize("Mr. Mime"))
    }

    @Test
    fun levenshtein_basicDistances() {
        assertEquals(0, NameMatcher.levenshtein("abc", "abc"))
        assertEquals(1, NameMatcher.levenshtein("abc", "abd"))
        assertEquals(1, NameMatcher.levenshtein("abc", "ab"))
        assertEquals(3, NameMatcher.levenshtein("abc", "xyz"))
    }

    @Test
    fun levenshtein_earlyExitOverMax() {
        // 상한 1 을 넘으면 max+1(=2) 로 조기 반환
        assertEquals(2, NameMatcher.levenshtein("abc", "xyz", maxDistance = 1))
    }

    @Test
    fun match_exact_korean() {
        // 정확한 한글 이름 → 편집거리 0 매칭
        val r = matcher.match("한카리아스")
        assertTrue("한카리아스는 매칭돼야 함", r is MatchResult.Matched)
        r as MatchResult.Matched
        assertEquals(0, r.editDistance)
        assertEquals("garchomp", r.root)
    }

    @Test
    fun match_exact_english() {
        val r = matcher.match("Garchomp")
        assertTrue(r is MatchResult.Matched)
        r as MatchResult.Matched
        assertEquals("garchomp", r.root)
    }

    @Test
    fun match_typo_oneChar_stillMatches() {
        // 한 글자 오타(OCR 오인식 시뮬레이션) → fuzzy 로 교정
        val r = matcher.match("한카리아소") // 스→소
        assertTrue("1글자 오타도 fuzzy 로 매칭돼야 함", r is MatchResult.Matched)
        r as MatchResult.Matched
        assertEquals("garchomp", r.root)
        assertTrue("편집거리는 1 이상", r.editDistance in 1..2)
    }

    @Test
    fun match_typo_english_oneChar() {
        val r = matcher.match("Garchmop") // 전치 오타
        assertTrue(r is MatchResult.Matched)
    }

    @Test
    fun match_garbage_noMatch() {
        // 완전히 무관한 문자열은 미매칭
        val r = matcher.match("zzzqqqxxxwww999")
        assertEquals(MatchResult.NoMatch, r)
    }

    @Test
    fun match_empty_noMatch() {
        assertEquals(MatchResult.NoMatch, matcher.match("   !!!   "))
    }

    @Test
    fun match_collisionGroup_returnsMultipleCandidates_sortedByUsage() {
        // 윈디(arcanine root): 히스이윈디 + 윈디 2후보. usage_rank 내림차순 정렬 확인.
        val r = matcher.match("윈디")
        assertTrue(r is MatchResult.Matched)
        r as MatchResult.Matched
        assertEquals("arcanine", r.root)
        assertTrue("충돌 그룹은 후보 2+개", r.candidates.size >= 2)
        assertTrue("첫 후보가 unique 는 아님", !r.isUnique)
        // 사용률 내림차순 정렬 검증
        val ranks = r.candidates.map { it.usage_rank ?: -1.0 }
        assertEquals(ranks.sortedDescending(), ranks)
    }
}

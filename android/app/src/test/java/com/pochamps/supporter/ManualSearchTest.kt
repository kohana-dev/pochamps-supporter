package com.pochamps.supporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4: 수동 검색(이름 부분일치) 테스트 — 실 assets JSON.
 * DESIGN.md 5장: OCR 미매칭 fallback. 현재 언어 이름 부분일치, 접두 우선.
 */
class ManualSearchTest {

    private val repo = TestData.repository()

    @Test
    fun 부분일치_한국어_리자() {
        val hits = repo.searchByName("리자", "ko")
        val keys = hits.map { it.key }
        assertTrue("리자몽 포함", keys.contains("charizard"))
        // 접두 일치(리자몽)가 부분 일치(일레도리자드)보다 먼저.
        val idxChar = keys.indexOf("charizard")
        val idxHelio = keys.indexOf("heliolisk")
        if (idxHelio >= 0) assertTrue("접두 우선", idxChar < idxHelio)
    }

    @Test
    fun 정확일치_한카리아스() {
        val hits = repo.searchByName("한카리아스", "ko")
        assertEquals("garchomp", hits.first().key)
    }

    @Test
    fun 빈검색어는_빈결과() {
        assertTrue(repo.searchByName("", "ko").isEmpty())
        assertTrue(repo.searchByName("   ", "ko").isEmpty())
    }

    @Test
    fun 메가폼은_검색결과에서_제외() {
        // "한카리아스" 검색은 base 만(mega-garchomp 제외).
        val hits = repo.searchByName("한카리아스", "ko")
        assertFalse("메가 제외", hits.any { it.key == "mega-garchomp" })
    }

    @Test
    fun 무관검색어는_빈결과() {
        assertTrue(repo.searchByName("zzzzzz없는이름", "ko").isEmpty())
    }
}

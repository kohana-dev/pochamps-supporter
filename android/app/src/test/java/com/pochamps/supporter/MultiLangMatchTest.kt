package com.pochamps.supporter

import com.pochamps.supporter.matching.MatchResult
import com.pochamps.supporter.matching.NameMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P31] "항상 다국어" 매칭 검증(순수 JVM, 실 candidate_index 데이터).
 *
 * 핵심: 캡처 언어를 고정하지 않아도 상대 이름이 **어느 언어(9개)로 떠도** 같은 root 로 매칭돼야 한다.
 * NameMatcher 는 9개 언어 lookup 을 하나로 합쳐(normalizedToRoot) 라인마다 완전일치→fuzzy 로 해석한다.
 * 또한 사전 매칭이 "엉뚱한 스크립트 인식기가 만든 garbage 라인"을 걸러주는지(오탐 억제) 확인한다.
 */
class MultiLangMatchTest {

    private val repo = TestData.repository()

    /** (설명, 관측 문자열, 기대 root) — 한 종족(garchomp 등)을 9개 언어 표기로. */
    private val multiLang = listOf(
        // garchomp
        Triple("ko", "한카리아스", "garchomp"),
        Triple("en", "Garchomp", "garchomp"),
        Triple("ja", "ガブリアス", "garchomp"),
        Triple("de", "Knakrack", "garchomp"),
        Triple("fr", "Carchacrok", "garchomp"),
        Triple("zh-cn", "烈咬陆鲨", "garchomp"),
        Triple("zh-tw", "烈咬陸鯊", "garchomp"),
        // gyarados
        Triple("ko", "갸라도스", "gyarados"),
        Triple("en", "Gyarados", "gyarados"),
        Triple("ja", "ギャラドス", "gyarados"),
        Triple("fr", "Léviator", "gyarados"),
        Triple("zh-cn", "暴鲤龙", "gyarados"),
        // gardevoir / absol
        Triple("ja", "サーナイト", "gardevoir"),
        Triple("ko", "앱솔", "absol"),
        Triple("zh-cn", "阿勃梭鲁", "absol"),
    )

    @Test
    fun 어느_언어로_떠도_같은_root_로_매칭된다() {
        for ((lang, text, expectedRoot) in multiLang) {
            val r = repo.match(text)
            assertTrue("$lang '$text' 는 매칭되어야(NoMatch 아님)", r is MatchResult.Matched)
            val m = r as MatchResult.Matched
            assertEquals("$lang '$text' → root", expectedRoot, m.root)
            assertEquals("$lang '$text' 완전일치(거리 0)", 0, m.editDistance)
        }
    }

    @Test
    fun matchBest_여러언어라인_혼재해도_최적_root_채택() {
        // 크롭에 상대 이름(일본어) + 인접 UI(라틴 garbage)가 함께 들어온 상황.
        // 사전 매칭이 UI/garbage 를 걸러내고 종족명만 채택해야 한다.
        val lines = listOf("MOVE TIME 45", "ガブリアス", "Lv50")
        val m = repo.matchBest(lines)
        assertTrue(m is MatchResult.Matched)
        assertEquals("garchomp", (m as MatchResult.Matched).root)
    }

    @Test
    fun 완전일치가_fuzzy보다_우선() {
        // 완전일치 이름이 있으면 거리 0 으로 즉시 채택(다른 언어 근접 이름에 흔들리지 않음).
        val m = repo.match("Absol") as? MatchResult.Matched
        assertTrue(m != null)
        assertEquals("absol", m!!.root)
        assertEquals(0, m.editDistance)
    }

    // ---- 오탐 억제(fuzzy 임계 보수화, P31) ----

    @Test
    fun 아주짧은_garbage_라인은_fuzzy로_매칭되지_않는다() {
        // 엉뚱한 스크립트 인식기가 뱉을 법한 2~3글자 짧은 garbage. 완전일치가 아니면 매칭 금지.
        val shortGarbage = listOf("ab", "xy", "l50", "hp", "1v")
        for (g in shortGarbage) {
            val r = repo.match(g)
            assertTrue("짧은 garbage '$g' 는 NoMatch 여야(오탐 억제)", r is MatchResult.NoMatch)
        }
    }

    @Test
    fun 정규화길이_임계이하는_fuzzy_금지_완전일치만() {
        // MIN_FUZZY_LEN 이하 길이는 fuzzy 를 아예 시도하지 않는다(완전일치만 허용).
        assertTrue(NameMatcher.MIN_FUZZY_LEN in 2..4)
        // 길이 3 짜리 임의 garbage("zzz")는 매칭 실패.
        assertTrue(repo.match("zzz") is MatchResult.NoMatch)
    }

    @Test
    fun 충분히_긴이름은_한글자_오타를_여전히_교정() {
        // fuzzy 보수화가 정상 회수까지 죽이면 안 된다 — 긴 이름의 1글자 오타는 여전히 잡혀야.
        val m = repo.match("Garchimp") as? MatchResult.Matched // o→i 치환
        assertTrue("긴 이름 1글자 오타는 fuzzy 로 교정돼야", m != null)
        assertEquals("garchomp", m!!.root)
        assertTrue("fuzzy 거리 1", m.editDistance in 1..2)
    }
}

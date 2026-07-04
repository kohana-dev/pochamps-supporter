package com.pochamps.supporter

import com.pochamps.supporter.matching.MatchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P8 · K2/K3 다운스트림] 실배틀 화면에서 **실제로 관측된 이름 문자열**이 앱 DB(candidate_index)로
 * 정확히 해석되는지 검증(순수 JVM).
 *
 * ## 배경
 *  K3(온디바이스 OCR 실측)는 에뮬레이터에서 ML Kit 모델 다운로드(GMS Zapp)가 실패해 실행 불가
 *  (PROGRESS P8 참조 — 실기기 잔여). 대신 OCR "이후" 단계(NameMatcher→Repository)를 실관측 문자열로 검증한다.
 *
 * ## 관측 출처
 *  field_samples/ 의 실배틀 스크린샷(모바일/스위치 녹화)에서 눈으로 읽은 이름표 문자열.
 *  표시명 형식(K2 확정): **폼 접두어/닉네임 없는 base 종족명 단독** (영어 대문자 시작, 한국어 종족명).
 *  → candidate_index 가 op.gg 이름을 프록시로 써 왔는데, 실화면 문자열과 **일치**함을 여기서 확정한다.
 */
class FieldNameMatchTest {

    private val repo = TestData.repository()

    /** (언어, 관측 문자열, 기대 root) — field_samples 실관측. */
    private val observed = listOf(
        Triple("en", "Hippowdon", "hippowdon"),
        Triple("en", "Typhlosion", "typhlosion"),
        Triple("en", "Charizard", "charizard"),
        Triple("en", "Torkoal", "torkoal"),
        Triple("en", "Meowscarada", "meowscarada"),
        Triple("en", "Lucario", "lucario"),
        Triple("en", "Aerodactyl", "aerodactyl"),
        Triple("en", "Politoed", "politoed"),
        Triple("ko", "갸라도스", "gyarados"),
    )

    @Test
    fun 실관측_이름표_문자열이_DB로_정확매칭() {
        for ((lang, text, expectedRoot) in observed) {
            val r = repo.match(text)
            assertTrue("$lang '$text' 는 매칭되어야 함(NoMatch 아님)", r is MatchResult.Matched)
            val m = r as MatchResult.Matched
            assertEquals("$lang '$text' → root", expectedRoot, m.root)
            // 완전일치이므로 편집거리 0.
            assertEquals("$lang '$text' 완전일치(거리 0)", 0, m.editDistance)
        }
    }

    @Test
    fun 실관측_OCR노이즈_섞여도_fuzzy로_교정() {
        // ML Kit 이 이탤릭 폰트를 살짝 오인식하는 흔한 케이스(1글자 오타/치환)를 fuzzy 가 잡는지.
        val noisy = listOf(
            "Hippowdan" to "hippowdon",   // o→a 치환
            "TyphIosion" to "typhlosion", // l→I 오인식(대문자 아이)
            "Charizand" to "charizard",   // r→n
        )
        for ((text, expectedRoot) in noisy) {
            val r = repo.match(text)
            assertTrue("'$text' fuzzy 매칭 되어야 함", r is MatchResult.Matched)
            assertEquals("'$text' → root", expectedRoot, (r as MatchResult.Matched).root)
        }
    }

    @Test
    fun 관측된_이름표는_폼접두어_없는_종족명() {
        // K2 확정: 화면 이름표 = base 종족명. "Hisuian ..." 같은 폼 접두어가 붙지 않음(관측 범위).
        // 프록시 이름(op.gg)과 동일하므로, base 종족명이 그대로 단일 root 로 해석돼야 한다.
        val r = repo.match("Charizard") as MatchResult.Matched
        // Charizard 는 메가 X/Y 로 후보가 여럿일 수 있으나 root 는 charizard 단일.
        assertEquals("charizard", r.root)
        assertTrue("후보 최소 1개", r.candidates.isNotEmpty())
    }
}

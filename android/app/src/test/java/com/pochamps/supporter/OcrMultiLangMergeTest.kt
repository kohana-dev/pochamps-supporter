package com.pochamps.supporter

import com.pochamps.supporter.capture.RecognitionPipeline
import com.pochamps.supporter.matching.MatchResult
import com.pochamps.supporter.ocr.OcrEngine
import com.pochamps.supporter.ocr.OcrScript
import com.pochamps.supporter.ocr.TaggedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P31] 항상-다국어 OCR 의 순수 로직 검증(순수 JVM — ML Kit 네이티브 없이):
 *  - 4개 스크립트 인식기 결과 라인 **병합/중복 제거**([OcrEngine.mergeTagged]).
 *  - 매칭된 라인이 **어느 스크립트로 읽혔는지** 태그 되짚기([RecognitionPipeline.scriptTagFor]).
 */
class OcrMultiLangMergeTest {

    // ---- 병합/중복 제거 ----

    @Test
    fun 라틴이름은_여러_인식기가_읽어도_한번만_남고_스크립트가_합쳐진다() {
        // latin 이름 "Garchomp" 는 korean/japanese/chinese/latin 인식기 모두 읽을 수 있다.
        val per = listOf(
            OcrScript.KOREAN to listOf("Garchomp"),
            OcrScript.JAPANESE to listOf("Garchomp"),
            OcrScript.CHINESE to listOf("Garchomp"),
            OcrScript.LATIN to listOf("Garchomp"),
        )
        val merged = OcrEngine.mergeTagged(per)
        assertEquals("중복 제거로 한 줄만", 1, merged.size)
        assertEquals("Garchomp", merged[0].text)
        // 4개 인식기 전부 태그.
        assertEquals(
            listOf(OcrScript.KOREAN, OcrScript.JAPANESE, OcrScript.CHINESE, OcrScript.LATIN).toSet(),
            merged[0].scripts.toSet(),
        )
    }

    @Test
    fun 대소문자_공백차이는_같은_라인으로_합쳐진다() {
        val per = listOf(
            OcrScript.LATIN to listOf("Hippowdon"),
            OcrScript.KOREAN to listOf("hippowdon "),
        )
        val merged = OcrEngine.mergeTagged(per)
        assertEquals(1, merged.size)
        // 표시 텍스트는 최초 등장본(Latin) 유지.
        assertEquals("Hippowdon", merged[0].text)
    }

    @Test
    fun 서로다른_언어_라인은_각각_남는다() {
        // 한국어 인식기는 "한카리아스", 라틴 인식기는 UI "Lv50" 만 읽었다면 둘 다 남아야.
        val per = listOf(
            OcrScript.KOREAN to listOf("한카리아스"),
            OcrScript.LATIN to listOf("Lv50"),
        )
        val merged = OcrEngine.mergeTagged(per)
        assertEquals(2, merged.size)
        assertEquals(setOf("한카리아스", "Lv50"), merged.map { it.text }.toSet())
    }

    @Test
    fun 등장순서_유지_및_빈라인_무시() {
        val per = listOf(
            OcrScript.LATIN to listOf("", "  ", "Absol"),
            OcrScript.JAPANESE to listOf("アブソル"),
        )
        val merged = OcrEngine.mergeTagged(per)
        assertEquals(listOf("Absol", "アブソル"), merged.map { it.text })
    }

    // ---- 스크립트 태그 되짚기 ----

    @Test
    fun scriptTag_라틴은_L_태그() {
        val tagged = listOf(TaggedLine("Ninetales", listOf(OcrScript.KOREAN, OcrScript.LATIN)))
        val matched = MatchResult.Matched(
            root = "ninetales", candidates = emptyList(), matchedKey = "ninetales", editDistance = 0,
        )
        // K,L 을 고정순서로 조합 → "KL".
        assertEquals("KL", RecognitionPipeline.scriptTagFor(matched, tagged))
    }

    @Test
    fun scriptTag_한국어이름은_K_태그() {
        val tagged = listOf(TaggedLine("나인테일", listOf(OcrScript.KOREAN)))
        val matched = MatchResult.Matched(
            root = "ninetales", candidates = emptyList(), matchedKey = "나인테일", editDistance = 0,
        )
        assertEquals("K", RecognitionPipeline.scriptTagFor(matched, tagged))
    }

    @Test
    fun scriptTag_라인없으면_null() {
        val matched = MatchResult.Matched(
            root = "ninetales", candidates = emptyList(), matchedKey = "ninetales", editDistance = 0,
        )
        assertNull(RecognitionPipeline.scriptTagFor(matched, emptyList()))
    }

    @Test
    fun 다국어_병합이_지연을_늘리지_않음을_문서화() {
        // 병합 자체는 O(전체 라인 수) 로 저렴 — 지연은 병렬 인식(가장 느린 하나)에 지배된다.
        // 여기선 병합 결과 크기만 확인(성능 회귀 감지용 스모크).
        val per = (0 until 4).map { i ->
            OcrScript.entries[i] to listOf("Garchomp", "노이즈$i")
        }
        val merged = OcrEngine.mergeTagged(per)
        // "Garchomp" 1 + 노이즈0..3 4 = 5 라인.
        assertEquals(5, merged.size)
        assertTrue(merged.any { it.text == "Garchomp" && it.scripts.size == 4 })
    }
}

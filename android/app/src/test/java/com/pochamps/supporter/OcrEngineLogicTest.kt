package com.pochamps.supporter

import com.pochamps.supporter.ocr.OcrEngine
import com.pochamps.supporter.ocr.OcrScript
import com.pochamps.supporter.ocr.Preprocess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OcrEngine 의 순수 로직(언어→스크립트 선택, 이름 라인 추출) 유닛 테스트(순수 JVM).
 * ML Kit recognizer 자체는 Android/네이티브라 여기선 로직만 검증.
 */
class OcrEngineLogicTest {

    @Test
    fun 언어별_스크립트_매핑() {
        assertEquals(OcrScript.KOREAN, OcrScript.forLanguage("ko"))
        assertEquals(OcrScript.JAPANESE, OcrScript.forLanguage("ja"))
        assertEquals(OcrScript.CHINESE, OcrScript.forLanguage("zh-cn"))
        assertEquals(OcrScript.CHINESE, OcrScript.forLanguage("zh-tw"))
        // 라틴계는 전부 LATIN 하나로.
        assertEquals(OcrScript.LATIN, OcrScript.forLanguage("en"))
        assertEquals(OcrScript.LATIN, OcrScript.forLanguage("de"))
        assertEquals(OcrScript.LATIN, OcrScript.forLanguage("es"))
        assertEquals(OcrScript.LATIN, OcrScript.forLanguage("fr"))
        assertEquals(OcrScript.LATIN, OcrScript.forLanguage("it"))
    }

    @Test
    fun 대소문자_무관() {
        assertEquals(OcrScript.KOREAN, OcrScript.forLanguage("KO"))
        assertEquals(OcrScript.CHINESE, OcrScript.forLanguage("ZH-CN"))
    }

    @Test
    fun 미지언어는_라틴_fallback() {
        assertEquals(OcrScript.LATIN, OcrScript.forLanguage("xx"))
    }

    @Test
    fun pickNameLine_빈리스트는_null() {
        assertNull(OcrEngine.pickNameLine(emptyList()))
        assertNull(OcrEngine.pickNameLine(listOf("", "   ")))
    }

    @Test
    fun pickNameLine_문자많은라인_선택() {
        // "Lv50" 보다 이름 "한카리아스" 가 문자 수가 많아 선택돼야 함.
        val lines = listOf("Lv50", "한카리아스")
        assertEquals("한카리아스", OcrEngine.pickNameLine(lines))
    }

    @Test
    fun pickNameLine_단일라인() {
        assertEquals("Garchomp", OcrEngine.pickNameLine(listOf("Garchomp")))
    }

    @Test
    fun 전처리_기본은_NONE_이고_옵션존재() {
        // P9: 전처리 옵션 노출(기본 NONE = 무전처리). GRAYSCALE_CONTRAST 는 실기기 튜닝용 훅.
        assertTrue(Preprocess.entries.contains(Preprocess.NONE))
        assertTrue(Preprocess.entries.contains(Preprocess.GRAYSCALE_CONTRAST))
        assertEquals(2, Preprocess.entries.size)
    }
}

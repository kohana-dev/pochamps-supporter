package com.pochamps.supporter

import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.data.LocaleUtils
import com.pochamps.supporter.data.SUPPORTED_LANGUAGES
import com.pochamps.supporter.overlay.OverlayCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * P19: 표시 언어(displayLang)를 캡처 언어와 분리한 로직 검증.
 *
 * Context 가 필요한 부분(AppSettings 영속, LocaleUtils.wrap/defaultDisplayLang)은
 * 이 순수 JVM 테스트 범위 밖이라 에뮬 실측으로 확인한다. 여기서는:
 *  - 시스템 로케일 → 9개 지원 언어 매핑(closestSupported).
 *  - 언어 코드 → Locale 변환(toLocale, 중국어 지역 코드 포함).
 *  - 카드(OverlayCardData)가 넘겨받은 표시 언어로 렌더되는지(캡처 언어 무관).
 */
class DisplayLangTest {

    private val repo = TestData.repository()

    // ── 시스템 로케일 → 9언어 매핑 ─────────────────────────────────────────

    @Test
    fun 시스템로케일_직접일치() {
        assertEquals("ko", LocaleUtils.closestSupported(Locale.KOREAN))
        assertEquals("ja", LocaleUtils.closestSupported(Locale.JAPANESE))
        assertEquals("en", LocaleUtils.closestSupported(Locale.ENGLISH))
        assertEquals("de", LocaleUtils.closestSupported(Locale.GERMAN))
        assertEquals("fr", LocaleUtils.closestSupported(Locale.FRENCH))
        assertEquals("it", LocaleUtils.closestSupported(Locale.ITALIAN))
        assertEquals("es", LocaleUtils.closestSupported(Locale.forLanguageTag("es")))
    }

    @Test
    fun 중국어_간체번체_구분() {
        // 간체: 본토/싱가포르/스크립트 Hans.
        assertEquals("zh-cn", LocaleUtils.closestSupported(Locale.forLanguageTag("zh-CN")))
        assertEquals("zh-cn", LocaleUtils.closestSupported(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-cn", LocaleUtils.closestSupported(Locale.forLanguageTag("zh-Hans-CN")))
        // 번체: 대만/홍콩/마카오/스크립트 Hant.
        assertEquals("zh-tw", LocaleUtils.closestSupported(Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-tw", LocaleUtils.closestSupported(Locale.TRADITIONAL_CHINESE))
        assertEquals("zh-tw", LocaleUtils.closestSupported(Locale.forLanguageTag("zh-HK")))
        assertEquals("zh-tw", LocaleUtils.closestSupported(Locale.forLanguageTag("zh-Hant-TW")))
    }

    @Test
    fun 미지원언어는_en_폴백() {
        assertEquals("en", LocaleUtils.closestSupported(Locale.forLanguageTag("ru")))
        assertEquals("en", LocaleUtils.closestSupported(Locale.forLanguageTag("pt-BR")))
        assertEquals("en", LocaleUtils.closestSupported(Locale.forLanguageTag("th")))
    }

    // ── 언어 코드 → Locale ─────────────────────────────────────────────────

    @Test
    fun toLocale_중국어는_지역코드포함() {
        val cn = LocaleUtils.toLocale("zh-cn")
        assertEquals("zh", cn.language)
        assertEquals("CN", cn.country)
        val tw = LocaleUtils.toLocale("zh-tw")
        assertEquals("zh", tw.language)
        assertEquals("TW", tw.country)
    }

    @Test
    fun toLocale_단순언어() {
        assertEquals("ko", LocaleUtils.toLocale("ko").language)
        assertEquals("ja", LocaleUtils.toLocale("ja").language)
        assertEquals("de", LocaleUtils.toLocale("de").language)
    }

    @Test
    fun toLocale_모든지원언어_라운드트립() {
        // 지원하는 9개 언어 코드가 모두 유효한 Locale 로 변환되고,
        // 다시 closestSupported 로 돌리면 같은 코드가 나온다(왕복 일관성).
        SUPPORTED_LANGUAGES.forEach { code ->
            val locale = LocaleUtils.toLocale(code)
            assertEquals("왕복 일관성: $code", code, LocaleUtils.closestSupported(locale))
        }
    }

    // ── 카드가 표시 언어로 렌더(캡처 언어 무관) ────────────────────────────

    @Test
    fun 카드는_넘겨받은_표시언어로_렌더() {
        // 같은 도감(garchomp)이라도 lang 에 따라 이름이 달라진다.
        val ko = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        val en = OverlayCardData.fromRepository(repo, "garchomp", "en", BattleFormat.DOUBLES)!!
        val ja = OverlayCardData.fromRepository(repo, "garchomp", "ja", BattleFormat.DOUBLES)!!

        assertEquals("한카리아스", ko.name)
        assertEquals("Garchomp", en.name)
        // 세 언어 이름이 서로 다르다(표시 언어가 실제로 반영됨).
        assertNotEquals(ko.name, en.name)
        assertNotEquals(ko.name, ja.name)
        assertNotEquals(en.name, ja.name)

        // 같은 도감이므로 도감번호/메가 가능 여부는 언어와 무관하게 동일.
        assertEquals(ko.key, en.key)
        assertEquals(ko.canMega, en.canMega)
        assertEquals(ja.key, en.key)
    }

    @Test
    fun 캡처언어와_표시언어가_다른_조합_시뮬레이션() {
        // 실제 서비스 흐름 시뮬레이션:
        //  - 게임(캡처)은 일본어 → OCR/매칭은 도감번호를 확정(언어 무관, matchBest).
        //  - 표시 언어는 한국어 → 카드는 한국어로 렌더.
        // matchBest 는 언어와 무관하게 도감키를 돌려주므로, 카드 조립에 쓰는 lang 만
        // 표시 언어(ko)를 넘기면 게임이 일본어여도 카드가 한국어로 나온다.
        val displayLang = "ko"
        val card = OverlayCardData.fromRepository(repo, "garchomp", displayLang, BattleFormat.DOUBLES)!!
        assertEquals("게임이 일본어여도 표시 언어(ko) 카드", "한카리아스", card.name)
        assertTrue("타입 칩도 표시 언어", card.typeChips.any { it.label == "드래곤" })
    }

    @Test
    fun 표시언어_타입칩도_언어반영() {
        val ko = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        val en = OverlayCardData.fromRepository(repo, "garchomp", "en", BattleFormat.DOUBLES)!!
        val koTypes = ko.typeChips.map { it.label }.toSet()
        val enTypes = en.typeChips.map { it.label }.toSet()
        assertTrue("한국어 타입 라벨", koTypes.contains("드래곤"))
        assertTrue("영어 타입 라벨", enTypes.contains("Dragon"))
        assertNotEquals(koTypes, enTypes)
    }
}

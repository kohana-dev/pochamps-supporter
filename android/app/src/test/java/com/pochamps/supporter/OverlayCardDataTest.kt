package com.pochamps.supporter

import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.overlay.OverlayCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오버레이 카드 뷰모델 조립 로직 테스트(실 assets JSON 사용, 순수 JVM).
 * 데모 카드(garchomp)가 실데이터로 올바르게 채워지는지 검증.
 */
class OverlayCardDataTest {

    private val repo = TestData.repository()

    @Test
    fun garchomp_한국어_더블_카드조립() {
        val card = OverlayCardData.fromRepository(
            repo = repo,
            key = "garchomp",
            lang = "ko",
            format = BattleFormat.DOUBLES,
        )!!

        // 이름(한국어).
        assertEquals("한카리아스", card.name)

        // 타입 칩 2개(드래곤/땅), 각각 색상 헥스 존재.
        assertEquals(2, card.typeChips.size)
        val typeLabels = card.typeChips.map { it.label }.toSet()
        assertTrue("드래곤 타입 칩", typeLabels.contains("드래곤"))
        card.typeChips.forEach { chip ->
            assertTrue("타입 색상 헥스 존재: ${chip.label}", chip.colorHex?.startsWith("#") == true)
        }

        // 특성 최소 1개.
        assertTrue("특성 존재", card.abilities.isNotEmpty())

        // 주요기술 4개(더블 상위), 사용률 내림차순.
        assertEquals(4, card.topMoves.size)
        val pcts = card.topMoves.mapNotNull { it.pct }
        assertEquals("모든 기술에 사용률", 4, pcts.size)
        assertTrue("사용률 내림차순", pcts.zipWithNext().all { (a, b) -> a >= b })

        // garchomp 는 메가 가능.
        assertTrue("메가 가능 배지", card.canMega)
        assertEquals("garchomp", card.key)
    }

    @Test
    fun 없는키는_null() {
        val card = OverlayCardData.fromRepository(repo, "존재하지않는키", "ko", BattleFormat.DOUBLES)
        assertNull(card)
    }
}

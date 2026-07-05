package com.pochamps.supporter

import com.pochamps.supporter.overlay.CardStage
import com.pochamps.supporter.overlay.ExpandExclusive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [P30] "한 번에 한 카드만 EXPANDED" stage 전환 로직 유닛 테스트(순수 JVM).
 * 한 슬롯을 EXPANDED 로 펼치면 다른 EXPANDED 슬롯이 CARD 로 자동 축소되는지 검증
 * (더블배틀에서 둘 다 세로로 길어져 화면 아래가 잘리는 것 방지).
 */
class ExpandExclusiveTest {

    @Test
    fun 슬롯0_펼치면_다른_EXPANDED_슬롯1은_CARD로_축소() {
        val current = mapOf(0 to CardStage.CARD, 1 to CardStage.EXPANDED)
        val result = ExpandExclusive.apply(current, slot = 0, next = CardStage.EXPANDED)
        assertEquals(CardStage.EXPANDED, result[0])
        assertEquals(CardStage.CARD, result[1]) // 다른 EXPANDED → CARD 로 축소
    }

    @Test
    fun 여러_EXPANDED_슬롯이_있어도_대상만_남고_나머지_축소() {
        val current = mapOf(0 to CardStage.EXPANDED, 1 to CardStage.EXPANDED, 2 to CardStage.CHIP)
        val result = ExpandExclusive.apply(current, slot = 1, next = CardStage.EXPANDED)
        assertEquals(CardStage.CARD, result[0])
        assertEquals(CardStage.EXPANDED, result[1])
        assertEquals(CardStage.CHIP, result[2]) // CHIP 은 건드리지 않음
    }

    @Test
    fun CARD_나_CHIP_로_전환할_땐_다른_슬롯을_건드리지_않음() {
        val current = mapOf(0 to CardStage.EXPANDED, 1 to CardStage.EXPANDED)
        // 슬롯0 을 CHIP 으로(EXPANDED→CHIP 순환) → 슬롯1 EXPANDED 유지.
        val result = ExpandExclusive.apply(current, slot = 0, next = CardStage.CHIP)
        assertEquals(CardStage.CHIP, result[0])
        assertEquals(CardStage.EXPANDED, result[1]) // 그대로
    }

    @Test
    fun 새_슬롯_EXPANDED_추가_시_기존_EXPANDED_없으면_변화_없음() {
        val current = mapOf(0 to CardStage.CARD)
        val result = ExpandExclusive.apply(current, slot = 1, next = CardStage.EXPANDED)
        assertEquals(CardStage.CARD, result[0])
        assertEquals(CardStage.EXPANDED, result[1])
    }

    @Test
    fun 원본_맵은_변경되지_않음() {
        val current = mapOf(0 to CardStage.EXPANDED, 1 to CardStage.EXPANDED)
        ExpandExclusive.apply(current, slot = 0, next = CardStage.EXPANDED)
        // 원본 불변(순수 함수).
        assertEquals(CardStage.EXPANDED, current[1])
    }
}

package com.pochamps.supporter

import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.overlay.ControlSearch
import com.pochamps.supporter.overlay.MinimizeState
import com.pochamps.supporter.overlay.MinimizeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P21] 항상 보이는 컨트롤 바의 순수 로직 테스트:
 *  - 최소화 상태 토글/영속(재시작 후에도 유지).
 *  - "카드 없이 검색 → 핀" 대상 슬롯 선택(인식 실패해도 손으로 띄우는 진입 판정).
 */
class ControlBarTest {

    /** 테스트용 인메모리 최소화 저장소(SharedPreferences 대체). */
    private class FakeMinimizeStore(var state: MinimizeState = MinimizeState.DEFAULT) : MinimizeStore {
        override fun load() = state
        override fun save(s: MinimizeState) { state = s }
    }

    @Test
    fun 최소화_기본은_펼침() {
        assertFalse(MinimizeState.DEFAULT.minimized)
    }

    @Test
    fun 최소화_토글_왕복() {
        val expanded = MinimizeState(minimized = false)
        val minimized = expanded.toggled()
        assertTrue(minimized.minimized)
        assertFalse(minimized.toggled().minimized) // 다시 토글 → 펼침 복귀.
    }

    @Test
    fun 최소화_영속_재로드() {
        val store = FakeMinimizeStore()
        // 최소화 저장.
        store.save(MinimizeState(minimized = true))
        // 재시작 시 로드 → 최소화 유지.
        assertTrue(store.load().minimized)

        // 복원(펼침) 저장 → 로드 시 펼침.
        store.save(MinimizeState(minimized = false))
        assertFalse(store.load().minimized)
    }

    @Test
    fun 검색_카드없음_싱글_슬롯0() {
        // 인식 실패로 카드가 하나도 없을 때(싱글) → 슬롯 0 에 핀.
        assertEquals(0, ControlSearch.targetSlot(BattleFormat.SINGLES, emptySet()))
    }

    @Test
    fun 검색_카드없음_더블_슬롯0() {
        // 더블이라도 비어 있으면 가장 낮은 슬롯 0.
        assertEquals(0, ControlSearch.targetSlot(BattleFormat.DOUBLES, emptySet()))
    }

    @Test
    fun 검색_더블_슬롯0채움시_슬롯1() {
        // 더블에서 슬롯0이 이미 인식돼 있으면 검색 핀은 빈 슬롯1로 순차.
        assertEquals(1, ControlSearch.targetSlot(BattleFormat.DOUBLES, setOf(0)))
    }

    @Test
    fun 검색_더블_둘다채움시_슬롯0_덮어쓰기() {
        // 슬롯0/1 모두 차 있으면 슬롯0을 덮어쓴다(대상 재지정).
        assertEquals(0, ControlSearch.targetSlot(BattleFormat.DOUBLES, setOf(0, 1)))
    }

    @Test
    fun 검색_싱글_슬롯0채움시도_슬롯0() {
        // 싱글은 슬롯이 하나뿐 → 채워져 있어도 항상 슬롯0(교정 지정).
        assertEquals(0, ControlSearch.targetSlot(BattleFormat.SINGLES, setOf(0)))
    }
}

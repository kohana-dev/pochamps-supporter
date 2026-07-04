package com.pochamps.supporter

import com.pochamps.supporter.overlay.MegaSelection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [P12 회귀] 슬롯 카드 교체 시 메가 선택 누수 방지.
 *
 * 버그(BUG-P12-1): OverlayRenderer.updateSlot 이 megaSel 을 null 일 때만 초기화해,
 * 더블배틀에서 한 슬롯의 포켓몬이 교체돼도(예: Charizard[Mega X 토글]→Gengar) 직전 인덱스가 남아
 * effectiveCard 가 유저가 토글한 적 없는 새 포켓몬의 메가 폼(Mega Gengar)을 오표시했다.
 * 수정: key 가 바뀌면 base(-1)로 초기화.
 */
class MegaSelectionTest {

    @Test
    fun 최초표시는_base() {
        assertEquals(-1, MegaSelection.resolveOnUpdate(prevKey = null, newKey = "charizard", currentSel = null))
        // currentSel 이 우연히 남아 있어도 최초 표시면 base.
        assertEquals(-1, MegaSelection.resolveOnUpdate(prevKey = null, newKey = "charizard", currentSel = 1))
    }

    @Test
    fun 같은포켓몬_유지시_선택보존() {
        // Charizard 유지 중 유저가 Mega Y(1) 선택 → 재인식돼도 선택 유지.
        assertEquals(1, MegaSelection.resolveOnUpdate(prevKey = "charizard", newKey = "charizard", currentSel = 1))
        assertEquals(0, MegaSelection.resolveOnUpdate(prevKey = "charizard", newKey = "charizard", currentSel = 0))
        // base 유지.
        assertEquals(-1, MegaSelection.resolveOnUpdate(prevKey = "charizard", newKey = "charizard", currentSel = -1))
    }

    @Test
    fun 다른포켓몬_교체시_base로초기화() {
        // 핵심 회귀: Charizard[Mega X=0] → Gengar 교체 시 인덱스 누수 금지.
        assertEquals(-1, MegaSelection.resolveOnUpdate(prevKey = "charizard", newKey = "gengar", currentSel = 0))
        // Charizard[Mega Y=1] → Venusaur(메가 1개) 교체 — 1이 남으면 인덱스 범위 밖이라 안전하지만,
        // 명시적으로 base 초기화되는지 확인.
        assertEquals(-1, MegaSelection.resolveOnUpdate(prevKey = "charizard", newKey = "venusaur", currentSel = 1))
        // 비메가 → 메가 포켓몬 교체도 base.
        assertEquals(-1, MegaSelection.resolveOnUpdate(prevKey = "torkoal", newKey = "charizard", currentSel = 0))
    }
}

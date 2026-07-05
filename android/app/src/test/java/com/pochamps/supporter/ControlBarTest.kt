package com.pochamps.supporter

import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.overlay.ControlSearch
import com.pochamps.supporter.overlay.InteractionMode
import com.pochamps.supporter.overlay.MinimizeState
import com.pochamps.supporter.overlay.MinimizeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    // --- P24: 터치 모드 상태 기계(통과 ↔ 상호작용) ---

    @Test
    fun 터치_기본은_통과모드() {
        // 기본은 통과(메인 창 NOT_TOUCHABLE) — 평소 게임 터치가 그대로 통과해야 한다.
        assertFalse(InteractionMode().interactive)
    }

    @Test
    fun 터치_핸들탭_통과에서_상호작용_진입() {
        val m = InteractionMode().toggle(nowMs = 1_000L)
        assertTrue(m.interactive)
        assertEquals(1_000L, m.lastTouchMs) // 진입 시각을 타임아웃 기준으로 잡는다.
    }

    @Test
    fun 터치_핸들탭_상호작용에서_통과_즉시복귀() {
        val interactive = InteractionMode().toggle(1_000L)
        val back = interactive.toggle(2_000L)
        assertFalse(back.interactive) // 다시 탭 → 즉시 통과 복귀.
    }

    @Test
    fun 터치_무조작_타임아웃_자동통과복귀() {
        val m = InteractionMode(timeoutMs = 6_000L).toggle(0L) // t=0 진입.
        // 타임아웃 직전 → 아직 상호작용 유지(변화 없음 → 동일 인스턴스).
        assertSame(m, m.evaluate(5_999L))
        // 타임아웃 도달 → 통과 복귀.
        val back = m.evaluate(6_000L)
        assertFalse(back.interactive)
    }

    @Test
    fun 터치_조작하면_타임아웃_리셋() {
        val m = InteractionMode(timeoutMs = 6_000L).toggle(0L)
        // t=5000 에 조작 → 마지막 조작 시각 갱신.
        val touched = m.touched(5_000L)
        assertEquals(5_000L, touched.lastTouchMs)
        // 이제 t=6000 이어도 (6000-5000=1000 < 6000) 아직 유지.
        assertSame(touched, touched.evaluate(6_000L))
        // t=11000 에야 (11000-5000=6000) 통과 복귀.
        assertFalse(touched.evaluate(11_000L).interactive)
    }

    @Test
    fun 터치_통과모드에서는_조작무시() {
        // 통과 모드(비상호작용)에서는 이 창이 터치를 안 받으므로 touched 는 no-op(동일 인스턴스).
        val passthrough = InteractionMode()
        assertSame(passthrough, passthrough.touched(1_000L))
        // evaluate 도 통과 모드면 아무 변화 없음.
        assertSame(passthrough, passthrough.evaluate(999_999L))
    }

    // --- P25: 자동복귀 시간 조정(6초→12초) + 끄기(0) ---

    @Test
    fun 자동복귀_기본지연_12초() {
        // P25: 게임(가로)에서 조작 중 너무 빨리 통과로 되돌아가지 않게 12초로 늘렸다.
        assertEquals(12_000L, InteractionMode.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun 자동복귀_12초경과_통과복귀() {
        val m = InteractionMode(timeoutMs = InteractionMode.DEFAULT_TIMEOUT_MS).toggle(0L)
        // 11.999초엔 유지, 12초에 통과 복귀.
        assertSame(m, m.evaluate(11_999L))
        assertFalse(m.evaluate(12_000L).interactive)
    }

    @Test
    fun 자동복귀_끔이면_영원히_유지() {
        // 설정에서 자동복귀 끔(TIMEOUT_DISABLED=0) → 아무리 시간이 지나도 조작 모드 유지.
        val m = InteractionMode(timeoutMs = InteractionMode.TIMEOUT_DISABLED).toggle(0L)
        assertTrue(m.interactive)
        assertSame(m, m.evaluate(999_999L)) // 변화 없음(자동복귀 안 함).
        assertTrue(m.evaluate(999_999L).interactive)
    }

    @Test
    fun 자동복귀_복귀는_항상_통과안전상태() {
        // 자동복귀 결과는 항상 interactive=false(게임 조작 가능한 안전 상태) + lastTouch 초기화.
        val m = InteractionMode(timeoutMs = 12_000L).toggle(0L)
        val back = m.evaluate(12_000L)
        assertFalse(back.interactive)
        assertEquals(0L, back.lastTouchMs)
    }
}

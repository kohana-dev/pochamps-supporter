package com.pochamps.supporter.overlay

import com.pochamps.supporter.data.BattleFormat

/**
 * [P21] 항상 보이는 오버레이 컨트롤 바의 **순수 로직**(Android 의존성 없음 → JVM 유닛 테스트 가능).
 *
 * 컨트롤 바 자체의 Compose UI 는 [OverlayCard.kt] 의 `ControlBar`/`MinimizedHandle`,
 * 렌더 배선은 [OverlayRenderer] 에 있고, 이 파일은 다음 순수 판정만 담는다:
 *  - 최소화 상태 토글/영속([MinimizeState]).
 *  - "카드 없이 검색 → 핀" 시 대상 슬롯 선택([ControlSearch.targetSlot]).
 *
 * 이렇게 로직을 분리하면 인식 실패(카드 없음) 상황에서의 진입 판정이 실기기 없이 검증된다.
 */

/**
 * 오버레이 최소화 상태(P21). true=최소화(작은 핸들만), false=펼침(카드+컨트롤 바).
 * SharedPreferences 로 영속되며([MinimizeStore]), 재시작 후에도 유지된다.
 */
data class MinimizeState(val minimized: Boolean) {
    /** 최소화 ↔ 펼침 토글. */
    fun toggled(): MinimizeState = MinimizeState(!minimized)

    companion object {
        /** 기본값: 펼침(최초 실행 시 컨트롤 바+카드가 보이는 편이 발견성이 높다). */
        val DEFAULT = MinimizeState(minimized = false)
    }
}

/**
 * 최소화 상태 영속 저장소(P21). Android 구현은 SharedPreferences([PrefsMinimizeStore]),
 * 테스트 구현은 인메모리로 갈아끼운다(위치 저장소 [OverlayPositionStore] 와 동일 패턴).
 */
interface MinimizeStore {
    /** 저장된 최소화 상태. 없으면 기본값([MinimizeState.DEFAULT]). */
    fun load(): MinimizeState

    /** 최소화 상태 저장. */
    fun save(state: MinimizeState)
}

/**
 * 컨트롤 바 검색(수동 지정) 진입의 순수 판정(P21).
 *
 * 핵심: 인식이 실패해 **카드가 하나도 없어도** 컨트롤 바의 🔍 로 검색 시트를 열어
 * 포켓몬을 골라 슬롯에 핀 고정할 수 있어야 한다. 어느 슬롯에 핀할지를 여기서 정한다.
 */
object ControlSearch {
    /**
     * 컨트롤 바 검색으로 고른 포켓몬을 핀할 대상 슬롯을 고른다.
     *
     * 규칙:
     *  - 이미 표시 중인 슬롯이 하나도 없으면(카드 없음/인식 실패) → 슬롯 0.
     *  - 싱글이면 항상 슬롯 0(슬롯이 1개뿐).
     *  - 더블이면 아직 안 채워진 가장 낮은 슬롯(0, 1 순차)을 고른다. 둘 다 차 있으면 슬롯 0(덮어쓰기).
     *
     * @param format       현재 배틀 형식(싱글=슬롯 0만, 더블=슬롯 0/1).
     * @param occupiedSlots 현재 카드/실패 카드가 떠 있는 슬롯 집합(표시 순서 무관).
     */
    fun targetSlot(format: BattleFormat, occupiedSlots: Set<Int>): Int {
        if (format == BattleFormat.SINGLES) return 0
        // 더블: 0..maxSlotIndex 중 비어 있는 가장 낮은 슬롯. 없으면(모두 참) 0.
        for (slot in 0..format.maxSlotIndex) {
            if (slot !in occupiedSlots) return slot
        }
        return 0
    }
}

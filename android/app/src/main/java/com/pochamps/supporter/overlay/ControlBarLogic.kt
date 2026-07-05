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

/**
 * [P24] 오버레이 터치 모델의 **순수 상태 기계**(Android 의존성 없음 → JVM 유닛 테스트 가능).
 *
 * ## 배경(실사용자 리포트 item 1·2)
 * 기존에는 정보/컨트롤 메인 창이 `FLAG_NOT_TOUCHABLE` 없이 떠서, 보이는 콘텐츠 영역
 * (카드+컨트롤 바+진단 스트립) 전체가 **게임 터치를 가로챘다**. "작은 창" 전략으로 통과를
 * 흉내 냈지만 콘텐츠가 커질수록 게임 조작이 막혔다.
 *
 * ## 2창 모델
 *  - **메인 창**: 기본 `FLAG_NOT_TOUCHABLE` → 평소 모든 터치가 게임으로 통과(순수 표시).
 *  - **핸들 창**: 아주 작은 상시 touchable 버튼. 탭하면 이 상태 기계를 [toggle] 해
 *    메인 창의 `FLAG_NOT_TOUCHABLE` 를 잠시 제거(=상호작용 모드) → 유저 조작 → 다시 탭하거나
 *    [timeoutMs] 무조작이면 통과 모드로 자동 복귀([evaluate]).
 *
 * 이 클래스는 "지금 메인 창이 touchable 이어야 하는가"([interactive])와 자동 복귀 판정만 담당한다.
 * 실제 창 플래그 반영은 [OverlayRenderer] 가 [interactive] 값으로 수행한다.
 *
 * @property interactive true=상호작용 모드(메인 창 touchable), false=통과 모드(NOT_TOUCHABLE).
 * @property lastTouchMs 마지막 상호작용 시각(ms). 통과 모드면 무의미(0).
 * @param timeoutMs 상호작용 모드에서 이 시간(ms) 무조작이면 통과 모드로 복귀. 기본 6000.
 */
data class InteractionMode(
    val interactive: Boolean = false,
    val lastTouchMs: Long = 0L,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /**
     * 핸들 탭 → 모드 토글.
     *  - 통과 → 상호작용: [nowMs] 를 마지막 조작 시각으로 잡아 타임아웃을 새로 시작.
     *  - 상호작용 → 통과: 즉시 통과 복귀(유저가 조작 끝냈다는 명시 신호).
     */
    fun toggle(nowMs: Long): InteractionMode =
        if (interactive) copy(interactive = false, lastTouchMs = 0L)
        else copy(interactive = true, lastTouchMs = nowMs)

    /**
     * 상호작용 모드 중 유저가 카드/시트를 조작할 때 호출 → 자동 복귀 타이머를 리셋.
     * 통과 모드에서는 무시(상호작용 아닐 때의 조작은 없음).
     */
    fun touched(nowMs: Long): InteractionMode =
        if (interactive) copy(lastTouchMs = nowMs) else this

    /**
     * 주기 평가: 상호작용 모드에서 [timeoutMs] 이상 무조작이면 통과(게임 조작) 모드로 복귀.
     * [timeoutMs] 가 0 이하면(자동복귀 끔) 절대 복귀시키지 않는다([TIMEOUT_DISABLED]).
     * 변화가 있으면 새 상태, 없으면 자기 자신(호출부가 `!==` 로 반영 여부 판단).
     */
    fun evaluate(nowMs: Long): InteractionMode =
        if (interactive && timeoutMs > 0L && nowMs - lastTouchMs >= timeoutMs)
            copy(interactive = false, lastTouchMs = 0L)
        else this

    companion object {
        /**
         * 상호작용 모드 자동 복귀 지연(ms). 조작 없이 이 시간 지나면 통과(게임 조작) 모드로.
         *
         * [P25] 6초 → 12초로 늘렸다. 실사용자 리포트: 게임(가로)에서 자동복귀가 너무 빨라
         * 조작 도중 통과 모드로 되돌아가 갇히는 느낌. 핸들이 항상 보이게(P25) 개선했으므로
         * 자동복귀는 "안전장치"로만 동작하면 되고, 유저가 언제든 다시 조작 모드로 전환할 수 있다.
         * 자동복귀는 항상 안전한 "통과(게임 조작 가능)" 상태로 되돌린다.
         */
        const val DEFAULT_TIMEOUT_MS = 12_000L

        /** [P25] 자동복귀 끔(설정에서 유저가 끄면 이 값). 0 이하면 [evaluate] 가 복귀시키지 않는다. */
        const val TIMEOUT_DISABLED = 0L
    }
}

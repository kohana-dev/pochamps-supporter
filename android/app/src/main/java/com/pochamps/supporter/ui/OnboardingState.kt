package com.pochamps.supporter.ui

/**
 * 온보딩 단계 판정(P33) — 권한/실행 상태 → 단계 리스트로 매핑하는 **순수 로직**.
 *
 * Android/Compose 의존이 없어 JVM 유닛 테스트로 검증 가능(권한상태→단계 판정 로직 분리).
 * MainActivity 는 이 결과를 받아 체크리스트 카드를 렌더한다.
 *
 * 단계 정의(PRODUCTION_PLAN §3, 태스크 명세):
 *  ① 오버레이 권한(SYSTEM_ALERT_WINDOW)
 *  ② 알림 권한(POST_NOTIFICATIONS, Android 13+)
 *  ③ 게임 내 "배틀명 표시" 안내(사용자 확인형 — 강제 불가, 안내만)
 *  ④ 시작
 */
enum class OnboardingStep { OVERLAY, NOTIFICATION, BATTLE_NAMES, START }

/** 한 단계의 표시 상태. */
data class OnboardingStepState(
    val step: OnboardingStep,
    /** 이 단계가 완료(체크) 상태인가. */
    val done: Boolean,
    /**
     * 이 단계가 지금 "활성"(사용자가 지금 처리해야 할 다음 단계)인가.
     * 미완료 단계 중 앞에서부터 첫 번째만 active — 뒤 단계는 앞 단계가 끝나야 활성화된다.
     * (완료된 단계는 active=false, done=true.)
     */
    val active: Boolean,
)

/** 온보딩 전체 상태(단계 리스트 + 완료 여부). */
data class OnboardingState(
    val steps: List<OnboardingStepState>,
) {
    /** 시작(START) 이전 선행 단계가 모두 완료됐는가 → 온보딩 완료. */
    val allReady: Boolean
        get() = steps.filter { it.step != OnboardingStep.START }.all { it.done }

    /** 지금 사용자가 처리해야 할 다음 단계(없으면 null — 전부 완료). */
    val activeStep: OnboardingStep?
        get() = steps.firstOrNull { it.active }?.step

    fun stepState(step: OnboardingStep): OnboardingStepState =
        steps.first { it.step == step }
}

object OnboardingLogic {

    /**
     * 권한/확인 상태로부터 온보딩 단계 리스트를 계산한다.
     *
     * @param overlayGranted "다른 앱 위에 표시" 권한 허용 여부.
     * @param notificationRequired 알림 권한 단계를 노출해야 하는가(Android 13+ true).
     *   false(Android 12 이하)면 알림 단계는 자동 완료로 취급(런타임 권한 불필요).
     * @param notificationGranted 알림 권한 허용 여부(notificationRequired=false 면 무시).
     * @param battleNamesAcknowledged 사용자가 "배틀명 표시 안내를 확인했다"고 체크했는가.
     *
     * 규칙:
     *  - done: 각 단계의 조건 충족.
     *  - active: 미완료 단계 중 순서상 첫 번째만 true(순차 온보딩 — 앞을 먼저).
     *  - START 는 선행(①②③)이 모두 done 일 때만 active(그전엔 비활성).
     */
    fun compute(
        overlayGranted: Boolean,
        notificationRequired: Boolean,
        notificationGranted: Boolean,
        battleNamesAcknowledged: Boolean,
    ): OnboardingState {
        val overlayDone = overlayGranted
        // 알림 단계가 필요 없는 OS 버전이면 자동 완료로 취급(사용자 액션 불필요).
        val notifDone = !notificationRequired || notificationGranted
        val battleDone = battleNamesAcknowledged
        val startDone = false // START 는 "완료" 개념이 없다(누르면 실행으로 전환).

        val doneByStep = mapOf(
            OnboardingStep.OVERLAY to overlayDone,
            OnboardingStep.NOTIFICATION to notifDone,
            OnboardingStep.BATTLE_NAMES to battleDone,
            OnboardingStep.START to startDone,
        )

        val order = listOf(
            OnboardingStep.OVERLAY,
            OnboardingStep.NOTIFICATION,
            OnboardingStep.BATTLE_NAMES,
            OnboardingStep.START,
        )

        // 미완료 단계 중 순서상 첫 번째만 active.
        val firstIncomplete = order.firstOrNull { doneByStep[it] == false }

        val steps = order.map { step ->
            OnboardingStepState(
                step = step,
                done = doneByStep.getValue(step),
                active = step == firstIncomplete,
            )
        }
        return OnboardingState(steps)
    }
}

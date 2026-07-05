package com.pochamps.supporter.capture

/**
 * [K1 자동 진단] CaptureHealth — 캡처 프레임 스트림의 "건강 상태"를 분류하는 **순수 JVM** 로직.
 *
 * ## 문제(DESIGN.md 1장 K1 = 최대 리스크)
 * 게임이 `FLAG_SECURE` 로 화면 캡처를 막으면 MediaProjection 은 **검은(또는 균일 저휘도) 프레임**만
 * 준다. 현재 앱은 이 경우 조용히 아무 카드도 안 띄워서 사용자가 원인을 알 수 없다. 또 "프레임이
 * 아예 안 들어오는"(캡처 파이프 고장) 경우도 구분되지 않는다. 이 클래스가 두 실패를 갈라
 * 사용자에게 명확히 고지할 수 있게 한다.
 *
 * ## 상태
 *  - [Health.HEALTHY]  : 정상(프레임이 갱신되고 휘도가 정상).
 *  - [Health.BLACK_SCREEN] : 프레임은 들어오는데 연속 다수가 거의 검정/균일 저휘도가
 *    [blackHoldMs] 이상 지속 → FLAG_SECURE 차단의 강한 신호.
 *  - [Health.NO_FRAMES] : 캡처 시작 후 [noFramesMs] 동안 프레임 콜백이 0건 → 캡처 파이프 이상.
 *
 * ## 순수 함수화(JVM 테스트)
 * Android 의존성 없음. 호출부(RecognitionPipeline)가 프레임마다 **평균 휘도**(0..255)와 **도착시각**을
 * [onFrame] 으로 공급하고, 프레임이 없어도 주기적으로 [evaluate] 를 불러 경과를 판정한다.
 * 휘도 계산은 CaptureManager 가 이미 만드는 다운샘플(FrameGate.downsampleGray)을 재활용한다.
 *
 * ## 오탐 억제(보수적 임계/지속)
 *  - 로딩/전환 순간의 일시적 검정은 무시: BlackScreen 은 어두운 프레임이 [blackHoldMs] **연속 지속**
 *    되어야만 확정한다(중간에 밝은 프레임 1장이라도 오면 지속 타이머 리셋).
 *  - 정상 대전 화면은 밝으므로([blackLumaThreshold] 는 아주 낮게) BlackScreen 오판 가능성이 낮다.
 *
 * ## 스레딩
 * 파이프라인 워커 단일 스레드(+ 서비스가 evaluate 를 부를 수 있어 값 읽기는 @Volatile)에서 접근.
 * 상태 변이는 워커에서만 일어나므로 락 불필요. 읽기 원자성만 확보.
 *
 * @param noFramesMs 캡처 시작 후 이 시간(ms) 동안 프레임이 0건이면 NoFrames. 기본 4000.
 * @param blackHoldMs 어두운 프레임이 이 시간(ms) 연속 지속되면 BlackScreen. 기본 2500.
 * @param blackLumaThreshold 프레임 평균 휘도(0..255)가 이 값 이하이면 "어두운 프레임"으로 본다.
 *   정상 대전 화면은 훨씬 밝으므로 보수적으로 아주 낮게 잡는다. 기본 18.
 */
class CaptureHealth(
    private val noFramesMs: Long = DEFAULT_NO_FRAMES_MS,
    private val blackHoldMs: Long = DEFAULT_BLACK_HOLD_MS,
    private val blackLumaThreshold: Int = DEFAULT_BLACK_LUMA_THRESHOLD,
) {

    enum class Health { HEALTHY, BLACK_SCREEN, NO_FRAMES }

    /** 캡처(모니터) 시작 시각(ms). null=아직 시작 안 함. */
    private var startedAtMs: Long? = null

    /** 마지막으로 프레임이 도착한 시각(ms). null=아직 한 장도 없음. */
    private var lastFrameAtMs: Long? = null

    /**
     * 현재 진행 중인 "어두운 구간"의 시작 시각(ms). null=지금은 밝은(정상) 상태.
     * 밝은 프레임이 한 장이라도 오면 null 로 리셋 → 짧은 검정(로딩/전환)은 지속조건을 못 채운다.
     */
    private var darkSinceMs: Long? = null

    /** 직전에 보고한 상태(변화 감지용). 초기 HEALTHY(안내 없음이 기본). */
    @Volatile
    private var current: Health = Health.HEALTHY

    /** 캡처 시작(=모니터 리셋). 서비스가 파이프라인 시작 시 1회 호출. */
    fun start(nowMs: Long) {
        startedAtMs = nowMs
        lastFrameAtMs = null
        darkSinceMs = null
        current = Health.HEALTHY
    }

    /**
     * 프레임 1장 도착 보고. [avgLuma] 는 프레임 전체(또는 대표 영역) 평균 휘도(0..255).
     * @return 상태가 **바뀌었으면** 새 상태, 그대로면 null(호출부가 변화 시에만 UI/알림 갱신).
     */
    fun onFrame(avgLuma: Int, nowMs: Long): Health? {
        lastFrameAtMs = nowMs
        if (avgLuma <= blackLumaThreshold) {
            // 어두운 프레임 — 어두운 구간 진행 중이 아니면 시작 시각 기록.
            if (darkSinceMs == null) darkSinceMs = nowMs
        } else {
            // 밝은 프레임 1장이면 어두운 구간 종료(로딩/전환 순간의 검정 무시).
            darkSinceMs = null
        }
        return evaluate(nowMs)
    }

    /**
     * 프레임 도착과 무관하게 현재 시각 기준으로 상태를 재평가한다(프레임이 안 와도 NoFrames 판정 위해
     * 서비스가 주기적으로 호출). 상태가 바뀌면 새 상태, 아니면 null.
     */
    fun evaluate(nowMs: Long): Health? {
        val next = classify(nowMs)
        if (next == current) return null
        current = next
        return next
    }

    /** 현재(마지막으로 확정된) 상태. */
    fun currentHealth(): Health = current

    /** 상태 분류(순수). start 전이면 HEALTHY(중립). */
    private fun classify(nowMs: Long): Health {
        val started = startedAtMs ?: return Health.HEALTHY

        val lastFrame = lastFrameAtMs
        if (lastFrame == null) {
            // 아직 한 장도 안 옴 — 시작 후 유예시간이 지났으면 NoFrames.
            return if (nowMs - started >= noFramesMs) Health.NO_FRAMES else Health.HEALTHY
        }

        // 프레임이 한동안 끊겼는가?(파이프 정지) — 마지막 프레임 이후 noFramesMs 초과면 NoFrames.
        if (nowMs - lastFrame >= noFramesMs) return Health.NO_FRAMES

        // 어두운 구간이 blackHoldMs 이상 연속 지속됐는가? → BlackScreen(FLAG_SECURE 신호).
        val darkSince = darkSinceMs
        if (darkSince != null && nowMs - darkSince >= blackHoldMs) return Health.BLACK_SCREEN

        return Health.HEALTHY
    }

    /** 상태 초기화(캡처 중지). */
    fun reset() {
        startedAtMs = null
        lastFrameAtMs = null
        darkSinceMs = null
        current = Health.HEALTHY
    }

    companion object {
        const val DEFAULT_NO_FRAMES_MS = 4_000L
        const val DEFAULT_BLACK_HOLD_MS = 2_500L
        const val DEFAULT_BLACK_LUMA_THRESHOLD = 18

        /**
         * [P24] 이 건강 상태에 대해 **안내 카드를 표시해야 하는가**(순수 판정).
         * HEALTHY(정상)면 false → 인식 성공 평상시 화면에는 아무 안내도 뜨지 않는다(카드만).
         * 문제(BLACK_SCREEN/NO_FRAMES)일 때만 true → 안내 카드 표시.
         * OverlayRenderer.updateCaptureHealth 가 이 판정으로 카드 노출/해제를 결정한다.
         */
        fun shouldShowCard(health: Health): Boolean = health != Health.HEALTHY

        /**
         * 다운샘플 그레이 서명(FrameGate.downsampleGray 산출물, 각 셀 0..255)의 평균 휘도.
         * 빈 배열이면 0(=어두움)으로 본다. 프레임 전체 대표 밝기로 사용.
         */
        fun averageLuma(graySignature: IntArray): Int {
            if (graySignature.isEmpty()) return 0
            var sum = 0L
            for (v in graySignature) sum += v
            return (sum / graySignature.size).toInt()
        }
    }
}

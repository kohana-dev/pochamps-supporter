package com.pochamps.supporter.overlay

import kotlin.math.abs

/**
 * 오버레이 카드 스케일 로직(P16). 순수 JVM — Android 의존성 없음(유닛 테스트 가능).
 *
 * 설정의 카드 스케일을 허용 단계로 스냅/클램프한다. 스케일은 그래픽 변환(graphicsLayer)이 아니라
 * 폰트/패딩 등 밀도 기반으로 곱해져(카드 = 창 bounds 전략에서 창 크기가 자연히 따라가고 잘림이 없다),
 * OverlayCard 렌더가 이 배수를 각 dp/sp 에 적용한다.
 */
object OverlayScale {
    const val DEFAULT: Float = 1.0f
    const val MIN: Float = 0.8f
    const val MAX: Float = 1.5f

    /** 설정 UI 에 노출하는 스케일 단계(칩). */
    val STEPS: List<Float> = listOf(0.8f, 1.0f, 1.25f, 1.5f)

    /**
     * 임의 입력을 [MIN]..[MAX] 로 클램프한 뒤 가장 가까운 [STEPS] 단계로 스냅한다.
     * NaN/무한대 등 비정상값은 [DEFAULT] 로.
     */
    fun snap(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return DEFAULT
        val clamped = value.coerceIn(MIN, MAX)
        return STEPS.minByOrNull { abs(it - clamped) } ?: DEFAULT
    }

    /** 스케일 단계의 사람이 읽는 라벨(설정 칩 표시용). */
    fun label(step: Float): String = when {
        abs(step - 0.8f) < 0.01f -> "80%"
        abs(step - 1.0f) < 0.01f -> "100%"
        abs(step - 1.25f) < 0.01f -> "125%"
        abs(step - 1.5f) < 0.01f -> "150%"
        else -> "${(step * 100).toInt()}%"
    }
}

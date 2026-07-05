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

    /**
     * [P30] 모서리 드래그 연속 리사이즈의 클램프 범위. 설정 칩(스냅) 범위([MIN]..[MAX])보다 넓다 —
     * 칩은 특정값(80/100/125/150%), 드래그는 미세조정(60~200%)을 담당한다.
     */
    const val CONT_MIN: Float = 0.6f
    const val CONT_MAX: Float = 2.0f

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

    /**
     * [P30] 연속(드래그) 스케일 클램프. 스냅하지 않고 [CONT_MIN]..[CONT_MAX] 로만 제한한다.
     * NaN/무한대는 [DEFAULT] 로 안전 폴백.
     */
    fun clampCont(value: Float): Float {
        if (value.isNaN() || value.isInfinite()) return DEFAULT
        return value.coerceIn(CONT_MIN, CONT_MAX)
    }

    /**
     * [P30] 모서리 드래그 델타 → 새 연속 스케일(순수 로직, JVM 테스트 대상).
     *
     * 우하단 그립을 바깥으로(오른쪽·아래로) 끌면 커지고, 안으로 끌면 작아진다.
     * 드래그 이동량(px)을 기준 픽셀([refPx], 보통 화면 짧은 변 정도)로 나눠 비율 증감으로 환산해
     * 현재 스케일에 더한 뒤 [clampCont] 로 제한한다.
     *
     * @param current 현재 스케일(연속). 비정상이면 [DEFAULT] 로 간주.
     * @param dragDx  이번 프레임의 x 이동량(px, 오른쪽 +).
     * @param dragDy  이번 프레임의 y 이동량(px, 아래 +).
     * @param refPx   기준 픽셀(≤0 이면 델타 무시하고 현재값 유지). 화면 짧은 변 등.
     * @return 클램프된 새 스케일.
     */
    fun applyDragDelta(current: Float, dragDx: Float, dragDy: Float, refPx: Float): Float {
        val base = if (current.isNaN() || current.isInfinite()) DEFAULT else current
        if (refPx <= 0f || dragDx.isNaN() || dragDy.isNaN()) return clampCont(base)
        // 대각선 성분(우하=확대)을 하나의 스칼라로: x·y 평균 이동량을 기준 픽셀로 정규화.
        val avg = (dragDx + dragDy) / 2f
        return clampCont(base + avg / refPx)
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

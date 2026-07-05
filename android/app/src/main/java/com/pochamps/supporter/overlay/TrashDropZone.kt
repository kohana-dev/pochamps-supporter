package com.pochamps.supporter.overlay

import kotlin.math.hypot

/**
 * [P35 리포트3] 종료를 위한 "휴지통 드롭존"의 **순수 판정 로직**(Android 의존성 0 → JVM 테스트 가능).
 *
 * ## UX(메신저 챗헤드 패턴)
 * 핸들(또는 카드 그립)을 드래그하는 동안에만 화면 하단 중앙에 반투명 휴지통 드롭존이 나타난다.
 * 드래그 중인 핸들의 중심이 드롭존 근처(반경 안)로 오면 드롭존이 하이라이트되고, 그 상태에서 손을
 * 떼면(드롭) 앱을 완전히 종료한다. 드롭존 밖에서 떼면 평소처럼 위치만 이동한다.
 *
 * 이 클래스는 "드롭존 위치 계산"과 "지금 드롭존에 겹쳐 있는가(→하이라이트/종료 판정)"만 담당한다.
 * 실제 창 표시/드래그/종료 호출은 [OverlayRenderer] 가 이 판정을 이용해 수행한다.
 */
object TrashDropZone {

    /**
     * 드롭존 중심 좌표(px)를 계산한다 — 화면 하단 중앙, 아래 가장자리에서 [bottomMarginPx] 위.
     *
     * @param screenWidth   화면 폭(px).
     * @param screenHeight  화면 높이(px).
     * @param bottomMarginPx 화면 아래 가장자리에서 드롭존 중심까지의 여백(px).
     * @return (centerX, centerY) 픽셀 좌표.
     */
    fun center(screenWidth: Int, screenHeight: Int, bottomMarginPx: Int): Pair<Int, Int> {
        val cx = screenWidth / 2
        val cy = (screenHeight - bottomMarginPx).coerceAtLeast(0)
        return cx to cy
    }

    /**
     * 드래그 중인 핸들의 중심이 드롭존에 "걸렸는가"(하이라이트/종료 대상) 판정한다.
     *
     * 원형 히트 판정: 핸들 중심과 드롭존 중심 사이 거리가 [hitRadiusPx] 이하이면 겹친 것으로 본다.
     * 원형이라 상하좌우 어느 방향에서 접근해도 자연스럽고, 손가락 오차에 관대하다.
     *
     * @param handleCenterX 드래그 중인 핸들의 현재 중심 X(px).
     * @param handleCenterY 드래그 중인 핸들의 현재 중심 Y(px).
     * @param zoneCenterX   드롭존 중심 X(px) — [center] 결과.
     * @param zoneCenterY   드롭존 중심 Y(px).
     * @param hitRadiusPx   히트 반경(px). 이 거리 이내면 겹침.
     * @return true=드롭존에 겹침(하이라이트/드롭 시 종료), false=바깥(평소 이동).
     */
    fun isOver(
        handleCenterX: Float,
        handleCenterY: Float,
        zoneCenterX: Int,
        zoneCenterY: Int,
        hitRadiusPx: Float,
    ): Boolean {
        if (hitRadiusPx <= 0f) return false
        val dist = hypot(handleCenterX - zoneCenterX, handleCenterY - zoneCenterY)
        return dist <= hitRadiusPx
    }
}

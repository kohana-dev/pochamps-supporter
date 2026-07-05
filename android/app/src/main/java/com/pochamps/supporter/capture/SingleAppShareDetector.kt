package com.pochamps.supporter.capture

import kotlin.math.abs

/**
 * [P35 리포트2] "단일 앱 공유(Single app)" 감지의 **순수 판정 로직**(Android 의존성 0 → JVM 테스트 가능).
 *
 * ## 배경(실사용자 리포트 2)
 * Android 14+ 의 MediaProjection 동의 다이얼로그에서 "단일 앱"을 고르면, 캡처되는 콘텐츠 영역이
 * 전체 디스플레이가 아니라 그 앱 창(레터박스 포함)으로 한정된다. 결과적으로 실제 프레임에 담기는
 * 콘텐츠 크기가 디스플레이 크기와 달라져 ROI(이름표 위치)가 어긋나 인식이 실패한다.
 *
 * ## 감지 신호
 * API 34+ 의 `MediaProjection.Callback.onCapturedContentResize(width, height)` 는 캡처 콘텐츠가
 * 가상 디스플레이 크기와 다르게 리사이즈될 때 그 콘텐츠 크기를 알려준다. 이 콘텐츠 크기가 실제
 * 디스플레이 크기와 [toleranceRatio] 이상 차이 나면 "단일 앱 공유(또는 부분 캡처)"로 추정한다.
 *
 * 오탐 방지: 아주 작은 차이(상태바/제스처 영역 등)는 무시하도록 허용 비율을 둔다.
 */
object SingleAppShareDetector {

    /**
     * 캡처된 콘텐츠 크기가 디스플레이 크기와 유의미하게 다른가 → 단일 앱 공유(부분 캡처) 추정.
     *
     * @param contentWidth   onCapturedContentResize 로 통지된 콘텐츠 폭(px).
     * @param contentHeight  onCapturedContentResize 로 통지된 콘텐츠 높이(px).
     * @param displayWidth   실제 디스플레이 폭(px).
     * @param displayHeight  실제 디스플레이 높이(px).
     * @param toleranceRatio 허용 오차 비율(예 0.05 = 5%). 이보다 큰 차이면 불일치로 본다.
     * @return true=단일 앱 공유(부분 캡처) 추정, false=전체 화면 공유로 보임.
     */
    fun looksLikeSingleApp(
        contentWidth: Int,
        contentHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
        toleranceRatio: Double = DEFAULT_TOLERANCE_RATIO,
    ): Boolean {
        // 유효하지 않은 크기면 판정 불가(오탐 방지 — false).
        if (contentWidth <= 0 || contentHeight <= 0 || displayWidth <= 0 || displayHeight <= 0) {
            return false
        }
        val dw = abs(contentWidth - displayWidth).toDouble() / displayWidth
        val dh = abs(contentHeight - displayHeight).toDouble() / displayHeight
        // 가로/세로 중 하나라도 허용 오차를 넘으면 부분 캡처로 본다.
        return dw > toleranceRatio || dh > toleranceRatio
    }

    /** 기본 허용 오차 비율(5%). 상태바/제스처 영역 등 소소한 차이는 무시. */
    const val DEFAULT_TOLERANCE_RATIO = 0.05
}

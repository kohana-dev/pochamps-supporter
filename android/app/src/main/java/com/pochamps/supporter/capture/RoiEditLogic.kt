package com.pochamps.supporter.capture

/**
 * 인앱 ROI 보정 오버레이의 **순수 좌표 변환 로직**(Android 의존성 없음 — 유닛 테스트 가능).
 *
 * 보정 오버레이는 밴드(사각형)를 픽셀 화면 위에서 드래그(이동)/모서리 핸들로 리사이즈한다.
 * 그 조작 결과를 **화면비율(0~1) 좌표**로 클램프해 [RoiRect] 로 저장한다([PrefsRoiConfigStore]).
 *
 * 실제 터치/Compose 처리(픽셀 delta 수신)는 얇은 UI 어댑터가 담당하고,
 * "픽셀 delta → 비율 rect 클램프" 규칙 전부를 여기서 순수 함수로 갖는다.
 *
 * ## 불변식
 *  - 모든 좌표는 0.0~1.0 비율. left<right, top<bottom, 폭/높이 ≥ [MIN_SIZE].
 *  - 이동은 rect 크기를 보존하고 화면 안으로 클램프(밀려도 크기 유지).
 *  - 리사이즈는 반대변을 앵커로 고정하고 [MIN_SIZE] 이상 유지.
 */
object RoiEditLogic {

    /** ROI 최소 폭/높이(비율). 너무 작아 OCR 이 무의미해지는 것 방지. */
    const val MIN_SIZE = 0.02

    /** 리사이즈 핸들(모서리) 식별자. */
    enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    /**
     * 밴드 전체 이동(드래그). 픽셀 delta 를 비율로 환산해 rect 를 옮기되,
     * **크기를 보존**하면서 화면 [0,1] 안으로 클램프(가장자리에 닿으면 멈춤).
     *
     * @param rect 현재 밴드(비율).
     * @param dxPx 가로 이동 픽셀. @param dyPx 세로 이동 픽셀.
     * @param screenWidthPx 화면 폭(px). @param screenHeightPx 화면 높이(px).
     */
    fun move(rect: RoiRect, dxPx: Float, dyPx: Float, screenWidthPx: Int, screenHeightPx: Int): RoiRect {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) return rect
        val dx = dxPx / screenWidthPx
        val dy = dyPx / screenHeightPx
        val w = rect.right - rect.left
        val h = rect.bottom - rect.top
        // 좌상단을 [0, 1-크기] 로 클램프 → 크기 보존.
        val newLeft = (rect.left + dx).coerceIn(0.0, 1.0 - w)
        val newTop = (rect.top + dy).coerceIn(0.0, 1.0 - h)
        return RoiRect(newLeft, newTop, newLeft + w, newTop + h)
    }

    /**
     * 모서리 핸들 리사이즈. 잡은 모서리를 픽셀 delta 만큼 옮기고 **반대 모서리는 고정**한다.
     * 최소 크기([MIN_SIZE])와 화면 경계(0~1)를 지킨다.
     *
     * @param handle 잡은 모서리. @param dxPx/@param dyPx 그 모서리의 이동 픽셀.
     */
    fun resize(
        rect: RoiRect,
        handle: Handle,
        dxPx: Float,
        dyPx: Float,
        screenWidthPx: Int,
        screenHeightPx: Int,
    ): RoiRect {
        if (screenWidthPx <= 0 || screenHeightPx <= 0) return rect
        val dx = dxPx / screenWidthPx
        val dy = dyPx / screenHeightPx

        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom

        when (handle) {
            Handle.TOP_LEFT -> { left += dx; top += dy }
            Handle.TOP_RIGHT -> { right += dx; top += dy }
            Handle.BOTTOM_LEFT -> { left += dx; bottom += dy }
            Handle.BOTTOM_RIGHT -> { right += dx; bottom += dy }
        }

        // 잡은 변을, 반대변에서 최소 [MIN_SIZE] 떨어진 지점~화면경계 사이로 클램프.
        val movesLeft = handle == Handle.TOP_LEFT || handle == Handle.BOTTOM_LEFT
        val movesTop = handle == Handle.TOP_LEFT || handle == Handle.TOP_RIGHT
        if (movesLeft) {
            left = left.coerceIn(0.0, right - MIN_SIZE)
        } else {
            right = right.coerceIn(left + MIN_SIZE, 1.0)
        }
        if (movesTop) {
            top = top.coerceIn(0.0, bottom - MIN_SIZE)
        } else {
            bottom = bottom.coerceIn(top + MIN_SIZE, 1.0)
        }
        return RoiRect(left, top, right, bottom)
    }

    /**
     * 밴드 개수를 [count] 로 맞춘다(싱글=1 / 더블=2 탭 전환).
     *  - 늘리면 해당 포맷 기본값에서 부족분을 채운다.
     *  - 줄이면 앞에서부터 [count] 개만 유지.
     * 기존 밴드는 최대한 보존(유저 조정값 유실 방지).
     */
    fun resizeBandCount(rois: List<RoiRect>, count: Int): List<RoiRect> {
        require(count >= 1) { "밴드는 최소 1개" }
        if (rois.size == count) return rois
        if (rois.size > count) return rois.take(count)
        val defaults = if (count == 1)
            RoiConfig.DEFAULT_LANDSCAPE_SINGLE.rois
        else
            RoiConfig.DEFAULT_LANDSCAPE_DOUBLES.rois
        val result = rois.toMutableList()
        var i = rois.size
        while (result.size < count) {
            // 기본값에서 채우되, 인덱스가 넘치면 마지막 기본값 재사용.
            result.add(defaults.getOrElse(i) { defaults.last() })
            i++
        }
        return result
    }

    /** 포맷별 기본 밴드(초기화 버튼용). count=1→SINGLE, 그 외→DOUBLES. */
    fun defaultRois(count: Int): List<RoiRect> =
        if (count == 1) RoiConfig.DEFAULT_LANDSCAPE_SINGLE.rois
        else RoiConfig.DEFAULT_LANDSCAPE_DOUBLES.rois
}

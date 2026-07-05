package com.pochamps.supporter.overlay

/**
 * 후보/검색 시트(및 확장 패널)가 열릴 때의 배치·창위치 재조정 로직(P16). 순수 JVM — 유닛 테스트 가능.
 *
 * ## 문제(버그 리포트)
 * 창 = 카드 bounds 전략에서 시트가 열리면 창이 커진다. 세로(portrait)에선 카드 아래로 시트를 쌓으면
 * 되지만, 가로(landscape)에선 아래로 펼치면 화면 세로를 꽉 채우고 잘린다.
 *
 * ## 해법
 *  - **가로**: 시트를 카드 **옆(측면 flyout)** 으로 전개한다. 카드가 화면 오른쪽에 붙어 있어
 *    오른쪽에 시트 폭이 안 들어가면 **왼쪽으로 flip**. 그래도 창이 화면을 넘으면 창 x 를 왼쪽으로 당겨
 *    화면 안으로 clamp. 세로 넘침은 위로 당겨 clamp.
 *  - **세로**: 기존처럼 카드 **아래**로 전개. 다만 커진 창이 화면 아래를 넘으면 창 y 를 위로 당겨 clamp.
 *
 * 창은 단일 Compose 트리라 "측면 배치"는 카드+시트를 가로(Row)로 놓고, 창 x 를 왼쪽으로 이동하는 것으로
 * 구현된다. 이 객체는 (a) 시트를 옆에 둘지 아래 둘지([SheetSide]) 와 (b) 재조정된 창 위치를 계산한다.
 */
object SheetLayout {

    enum class Placement { BELOW, SIDE }

    /** 시트가 카드의 어느 쪽으로 flyout 되는지(SIDE 일 때만 의미). */
    enum class SideDir { RIGHT, LEFT }

    /**
     * 시트 열림 결과.
     * @param placement 아래로 쌓기(BELOW) vs 옆으로 flyout(SIDE).
     * @param sideDir SIDE 일 때 카드 기준 시트 방향(RIGHT=오른쪽, LEFT=왼쪽).
     * @param windowX/windowY 재조정된 창 좌상단 좌표(화면 경계 내로 clamp).
     */
    data class Result(
        val placement: Placement,
        val sideDir: SideDir,
        val windowX: Int,
        val windowY: Int,
    )

    /**
     * 시트가 열릴 때 배치와 창 위치를 계산한다.
     *
     * @param landscape 화면이 가로면 true → 측면 flyout, false → 아래 전개.
     * @param screenWidth/screenHeight 화면 크기(px).
     * @param winX/winY 현재 창(=카드) 좌상단.
     * @param cardWidth/cardHeight 카드(시트 열리기 전) 크기(px).
     * @param sheetWidth/sheetHeight 시트 크기(px).
     * @param gap 카드와 시트 사이 간격(px).
     */
    fun open(
        landscape: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        winX: Int,
        winY: Int,
        cardWidth: Int,
        cardHeight: Int,
        sheetWidth: Int,
        sheetHeight: Int,
        gap: Int = 0,
    ): Result {
        return if (landscape) {
            openSide(
                screenWidth, screenHeight, winX, winY,
                cardWidth, cardHeight, sheetWidth, sheetHeight, gap,
            )
        } else {
            openBelow(
                screenWidth, screenHeight, winX, winY,
                cardWidth, cardHeight, sheetWidth, sheetHeight,
            )
        }
    }

    /**
     * 세로: 카드 아래로 시트. 창 전체 높이 = cardHeight + gap? (아래 스택은 Column spacing 이 이미 있으니
     * 여기선 세로 총높이가 화면을 넘으면 창 y 를 위로 당긴다). 폭은 max(card, sheet).
     */
    private fun openBelow(
        screenWidth: Int,
        screenHeight: Int,
        winX: Int,
        winY: Int,
        cardWidth: Int,
        cardHeight: Int,
        sheetWidth: Int,
        sheetHeight: Int,
    ): Result {
        val totalW = maxOf(cardWidth, sheetWidth)
        val totalH = cardHeight + sheetHeight
        val x = clampStart(winX, totalW, screenWidth)
        val y = clampStart(winY, totalH, screenHeight)
        return Result(Placement.BELOW, SideDir.RIGHT, x, y)
    }

    /**
     * 가로: 시트를 카드 옆으로. 오른쪽에 (카드폭+gap+시트폭) 이 화면 안에 들어가면 RIGHT,
     * 아니면 LEFT flip. 어느 쪽이든 커진 창(Row)이 화면을 넘지 않게 x/y 를 clamp.
     * Row 배치라 창 좌상단은 "왼쪽 요소"의 좌상단이다.
     *  - RIGHT: 왼쪽 요소=카드 → 창 x = 카드 x. 총폭 = card+gap+sheet.
     *  - LEFT : 왼쪽 요소=시트 → 창 x = 카드 x - gap - sheet. 총폭 동일.
     * 세로는 max(cardHeight, sheetHeight) 로, 화면 아래 넘치면 위로 당김.
     */
    private fun openSide(
        screenWidth: Int,
        screenHeight: Int,
        winX: Int,
        winY: Int,
        cardWidth: Int,
        cardHeight: Int,
        sheetWidth: Int,
        sheetHeight: Int,
        gap: Int,
    ): Result {
        val totalW = cardWidth + gap + sheetWidth
        val totalH = maxOf(cardHeight, sheetHeight)

        // 오른쪽 여유가 시트를 담을 수 있으면 RIGHT, 아니면 LEFT flip.
        val rightEdgeIfRight = winX + cardWidth + gap + sheetWidth
        val fitsRight = rightEdgeIfRight <= screenWidth
        // 왼쪽으로 flip 했을 때 시트 왼쪽 끝이 화면 안(>=0)인지.
        val leftStartIfLeft = winX - gap - sheetWidth
        val fitsLeft = leftStartIfLeft >= 0

        val dir = when {
            fitsRight -> SideDir.RIGHT
            fitsLeft -> SideDir.LEFT
            // 양쪽 다 안 맞으면(아주 좁은 화면) 오른쪽 우선하되 아래 clamp 가 창을 화면 안으로 당긴다.
            else -> SideDir.RIGHT
        }

        val rawX = when (dir) {
            SideDir.RIGHT -> winX
            SideDir.LEFT -> leftStartIfLeft
        }
        val x = clampStart(rawX, totalW, screenWidth)
        val y = clampStart(winY, totalH, screenHeight)
        return Result(Placement.SIDE, dir, x, y)
    }

    /**
     * 시트가 닫힐 때 창 위치를 카드만 남는 크기 기준으로 되돌려 화면 안에 있게 clamp.
     * (열 때 왼쪽/위로 당겼던 것을 원위치로 강제하진 않는다 — 그냥 카드가 화면 안이면 유지.)
     */
    fun close(
        screenWidth: Int,
        screenHeight: Int,
        winX: Int,
        winY: Int,
        cardWidth: Int,
        cardHeight: Int,
    ): Result {
        val x = clampStart(winX, cardWidth, screenWidth)
        val y = clampStart(winY, cardHeight, screenHeight)
        return Result(Placement.BELOW, SideDir.RIGHT, x, y)
    }

    /** 시작좌표를 [0, screen-size] 로 clamp. 콘텐츠가 화면보다 크면 0(좌/상단 정렬). */
    private fun clampStart(start: Int, size: Int, screen: Int): Int {
        val max = (screen - size).coerceAtLeast(0)
        return start.coerceIn(0, max)
    }
}

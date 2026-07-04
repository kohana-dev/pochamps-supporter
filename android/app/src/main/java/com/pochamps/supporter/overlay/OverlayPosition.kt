package com.pochamps.supporter.overlay

/**
 * 오버레이 창의 위치(WindowManager.LayoutParams x/y)와 저장/복원 로직.
 *
 * ⚠️ 이 파일은 Android 의존성이 없다(순수 JVM). 실제 SharedPreferences I/O 는
 *    [OverlayPositionStore] 인터페이스 뒤로 감춰 두어, 클램프/기본값 계산 로직만
 *    유닛 테스트로 검증할 수 있게 한다.
 */
data class OverlayPosition(val x: Int, val y: Int) {

    /**
     * 화면 경계 안으로 위치를 클램프한다.
     * 드래그로 창이 화면 밖으로 나가 손댈 수 없게 되는 것을 방지.
     *
     * @param screenWidth  화면 폭(px)
     * @param screenHeight 화면 높이(px)
     * @param cardWidth    카드(=창) 폭(px)
     * @param cardHeight   카드(=창) 높이(px)
     */
    fun clampedTo(
        screenWidth: Int,
        screenHeight: Int,
        cardWidth: Int,
        cardHeight: Int,
    ): OverlayPosition {
        // 창이 화면보다 크면 0 으로(음수 상한 방지).
        val maxX = (screenWidth - cardWidth).coerceAtLeast(0)
        val maxY = (screenHeight - cardHeight).coerceAtLeast(0)
        return OverlayPosition(
            x = x.coerceIn(0, maxX),
            y = y.coerceIn(0, maxY),
        )
    }

    companion object {
        /**
         * 최초 실행 시 기본 위치: 화면 상단 중앙 근처(HP바/필드를 덜 가리는 위치, DESIGN.md 5장 원칙 1).
         * 가로 중앙 정렬 + 상단에서 약간 내려온 지점.
         */
        fun default(
            screenWidth: Int,
            screenHeight: Int,
            cardWidth: Int,
            cardHeight: Int,
        ): OverlayPosition {
            val x = ((screenWidth - cardWidth) / 2).coerceAtLeast(0)
            // 상단 12% 지점(상태바/노치 아래).
            val y = (screenHeight * 0.12f).toInt().coerceAtLeast(0)
            return OverlayPosition(x, y).clampedTo(screenWidth, screenHeight, cardWidth, cardHeight)
        }
    }
}

/**
 * 오버레이 위치 영속 저장소. Android 구현은 SharedPreferences,
 * 테스트 구현은 인메모리로 갈아끼운다.
 */
interface OverlayPositionStore {
    /** 저장된 위치. 없으면 null(→ 호출부가 default 사용). */
    fun load(): OverlayPosition?

    /** 위치 저장. */
    fun save(position: OverlayPosition)
}

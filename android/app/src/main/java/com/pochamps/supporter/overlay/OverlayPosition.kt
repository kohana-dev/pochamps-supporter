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

    /**
     * [P25] 화면 안에 완전히 들어와 있는지(음수/화면 밖 없음) 판정.
     * 회전 등 화면 구성 변경 후 저장된 위치가 새 화면을 벗어났는지 확인하는 데 쓴다.
     */
    fun isFullyInside(
        screenWidth: Int,
        screenHeight: Int,
        cardWidth: Int,
        cardHeight: Int,
    ): Boolean =
        x >= 0 && y >= 0 &&
            x + cardWidth <= screenWidth &&
            y + cardHeight <= screenHeight

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

        /**
         * [P25] 토글 핸들 기본 위치: **우측 세로 중앙, 가장자리에서 약간 안쪽**.
         *
         * 게임(가로 전체화면)에서도 항상 화면 안이고 손이 닿기 쉬운 안전 기본값.
         * 가장자리에 딱 붙이지 않고 [edgeInset] 만큼 안으로 들여, 삼성 One UI 몰입형/제스처
         * 안전영역이나 곡면 화면에서도 잘리지 않게 한다.
         *
         * @param edgeInset 화면 가장자리에서 안으로 들이는 여백(px).
         */
        fun handleDefault(
            screenWidth: Int,
            screenHeight: Int,
            handleWidth: Int,
            handleHeight: Int,
            edgeInset: Int,
        ): OverlayPosition {
            // 우측 가장자리에서 handleWidth + inset 만큼 왼쪽 → 화면 안, 오른쪽 구석 근처.
            val x = screenWidth - handleWidth - edgeInset
            // 세로 중앙(엄지가 닿기 쉬움).
            val y = (screenHeight - handleHeight) / 2
            return OverlayPosition(x, y)
                .clampedTo(screenWidth, screenHeight, handleWidth, handleHeight)
        }

        /**
         * [P25] 화면 구성 변경(회전 등) 시 위치 재보정.
         *
         * 규칙:
         *  1) 이전 화면 크기를 알면(oldW/oldH>0) 중심을 **비율로 새 화면에 매핑**해 상대 위치를 보존한다.
         *  2) 매핑/클램프 후에도 화면 안에 완전히 들어오면 그 값을 쓴다.
         *  3) 저장된 위치가 (비율 매핑을 못 하거나) 여전히 화면을 벗어나면 안전 기본값([handleDefault])으로.
         *
         * 이렇게 하면 세로→가로 전환 시 핸들이 화면 밖으로 나가 "게임 중 안 보임/못 누름" 상황을 막는다.
         *
         * @param current 현재(이전 화면 기준) 저장 위치.
         * @param oldW/oldH 이전 화면 크기(px). 0 이하면 비율 매핑 생략(클램프만).
         * @param newW/newH 새 화면 크기(px).
         * @param handleWidth/handleHeight 핸들 창 크기(px).
         * @param edgeInset 안전 기본값 계산용 가장자리 여백(px).
         */
        fun remapForScreen(
            current: OverlayPosition,
            oldW: Int,
            oldH: Int,
            newW: Int,
            newH: Int,
            handleWidth: Int,
            handleHeight: Int,
            edgeInset: Int,
        ): OverlayPosition {
            // 비율 매핑: 이전 화면에서의 중심 비율을 새 화면에 옮긴다(상대 위치 보존).
            val mapped = if (oldW > 0 && oldH > 0) {
                val cxRatio = (current.x + handleWidth / 2f) / oldW
                val cyRatio = (current.y + handleHeight / 2f) / oldH
                val nx = (cxRatio * newW - handleWidth / 2f).toInt()
                val ny = (cyRatio * newH - handleHeight / 2f).toInt()
                OverlayPosition(nx, ny)
            } else {
                current
            }
            val clamped = mapped.clampedTo(newW, newH, handleWidth, handleHeight)
            // 클램프 후 화면 안이면 채택. (클램프는 항상 화면 안으로 들이므로 사실상 항상 통과하지만,
            //  핸들이 화면보다 큰 비정상 상황 방어를 위해 명시적으로 확인 후 기본값 폴백.)
            return if (clamped.isFullyInside(newW, newH, handleWidth, handleHeight)) clamped
            else handleDefault(newW, newH, handleWidth, handleHeight, edgeInset)
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

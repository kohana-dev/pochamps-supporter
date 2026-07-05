package com.pochamps.supporter.capture

/**
 * [2] FrameGate — 이름 ROI 영역만 저비용 변화 감지로 감시해, "이름 영역이 바뀌었을 때만"
 * 다음 단계(크롭→OCR)를 트리거한다. 매 프레임 OCR 을 돌리지 않아 발열/전력을 최소화한다.
 *
 * ## 동작
 *  1) 캡처된 프레임에서 각 ROI 영역을 **다운샘플**해 작은 그레이스케일 격자로 만든다(호출부가 만든 해시 입력).
 *  2) 그 격자의 지각 해시(perceptual-ish, 여기선 단순 평균-임계 비트 + 합계 해시)를 이전 값과 비교.
 *  3) 변화량(다른 비트 수 또는 값 차분)이 임계 이상이고, 마지막 트리거로부터 최소 인터벌이 지났을 때만 통과.
 *
 * ## 순수 JVM
 *  Android 의존성 없음(비트맵/픽셀 접근은 호출부가 하고, 여기엔 int 배열/시각만 넘긴다).
 *  → 해시 계산·변화 판정·스로틀을 Robolectric 없이 유닛 테스트할 수 있다.
 *
 * @param minIntervalMs 연속 트리거 사이 최소 간격(ms). 이름이 계속 흔들려도 초당 트리거 수를 제한.
 * @param diffThreshold 변화로 간주할 최소 diff 비율(0.0~1.0). 다운샘플 셀 중 몇 % 가 바뀌어야 "변화"인지.
 * @param maxIntervalMs **하트비트 주기**(ms). 화면 변화가 없어도 이 간격이 지나면 강제로 한 번 통과시킨다.
 *
 * ## 왜 하트비트가 필요한가 (고착 버그 방지)
 * FrameGate 는 "변화가 있을 때만" OCR 을 돌린다. 그런데 PipelineDecider 의 저신뢰 전환
 * 히스테리시스는 **연속 N회 관측**이 있어야 다른 포켓몬으로 카드를 교체한다. 포켓몬이 바뀌면
 * FrameGate 가 딱 한 번만 트리거되고, 이후 새 이름표는 **정지 화면**이라 다시 트리거되지 않는다.
 * → 첫 관측이 약매칭이면 2번째 관측이 영원히 오지 않아 **카드가 이전 포켓몬에 영구 고착**된다.
 * 하트비트는 정지 화면에서도 주기적으로 재스캔을 공급해 이 교착을 깬다(임계값이 놓친 변화도 회수).
 */
class FrameGate(
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val diffThreshold: Double = DEFAULT_DIFF_THRESHOLD,
    private val maxIntervalMs: Long = DEFAULT_MAX_INTERVAL_MS,
) {

    /** ROI 별 마지막 서명(다운샘플 그레이 값 배열). 없으면 최초 프레임. */
    private val lastSignatures = HashMap<Int, IntArray>()

    /** ROI 별 마지막 트리거 시각(ms). */
    private val lastTriggerAt = HashMap<Int, Long>()

    /**
     * 한 ROI 의 다운샘플 그레이 서명으로 트리거 여부를 판정한다.
     *
     * @param roiIndex ROI 식별자(더블배틀이면 0/1 두 곳).
     * @param signature 다운샘플된 그레이스케일 값 배열(예: 8x8=64 요소, 각 0..255). 길이는 ROI 별로 일정해야 함.
     * @param nowMs 현재 시각(ms). 테스트 주입 가능.
     * @return true 면 이 ROI 에 대해 다음 단계(크롭→OCR)를 돌려야 함.
     */
    fun shouldProcess(roiIndex: Int, signature: IntArray, nowMs: Long): Boolean {
        val prev = lastSignatures[roiIndex]
        val last = lastTriggerAt[roiIndex]
        val sinceLast = if (last == null) Long.MAX_VALUE else nowMs - last

        val changed = prev == null || diffRatio(prev, signature) >= diffThreshold
        // 하트비트: 변화가 없어도 마지막 트리거로부터 maxIntervalMs 지나면 강제 재스캔(고착 방지).
        val heartbeatDue = sinceLast >= maxIntervalMs

        if (!changed && !heartbeatDue) return false

        // 변화 기반 트리거는 최소 인터벌 스로틀 적용. (하트비트는 이미 maxInterval≥minInterval 지났으므로 통과.)
        if (changed && !heartbeatDue && sinceLast < minIntervalMs) {
            // 서명은 갱신해 두어, 다음 프레임에서 "또 변화"로 오판하지 않게 한다.
            lastSignatures[roiIndex] = signature.copyOf()
            return false
        }

        // 트리거 확정 — 서명/시각 갱신.
        lastSignatures[roiIndex] = signature.copyOf()
        lastTriggerAt[roiIndex] = nowMs
        return true
    }

    /** 새 캡처 세션 시작 등으로 상태를 초기화한다. */
    fun reset() {
        lastSignatures.clear()
        lastTriggerAt.clear()
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 700L
        const val DEFAULT_DIFF_THRESHOLD = 0.10 // 다운샘플 셀의 10% 이상 바뀌면 변화로 간주.

        /**
         * 하트비트 주기(ms). 정지 화면에서도 이 간격마다 강제 재스캔 → 저신뢰 전환 확정/놓친 변화 회수.
         * ROI 당 ~0.67회/s 기저 OCR(실측 예산 ~1회/s 내). 히스테리시스 확정 지연 최대 ≈ 이 값.
         */
        const val DEFAULT_MAX_INTERVAL_MS = 1500L

        /**
         * 두 그레이 서명의 diff 비율(0.0~1.0). 셀 값 차가 [tolerance] 초과인 셀의 비율.
         * 길이가 다르면(=ROI 크기 변동) 무조건 1.0(=완전 변화).
         */
        fun diffRatio(a: IntArray, b: IntArray, tolerance: Int = GRAY_TOLERANCE): Double {
            if (a.size != b.size || a.isEmpty()) return 1.0
            var diff = 0
            for (i in a.indices) {
                if (kotlin.math.abs(a[i] - b[i]) > tolerance) diff++
            }
            return diff.toDouble() / a.size
        }

        /** 셀 값 차가 이 값 이하면 "같은 셀"로 본다(노이즈/압축 아티팩트 허용). */
        const val GRAY_TOLERANCE = 24

        /**
         * ARGB 픽셀 격자(width*height, ARGB_8888 int)를 targetW x targetH 그레이 격자로 다운샘플.
         * 블록 평균으로 축소한다. 순수 계산이라 JVM 테스트 가능(비트맵 불필요).
         *
         * @param pixels ARGB_8888 int 배열(길이 = width*height).
         */
        fun downsampleGray(
            pixels: IntArray,
            width: Int,
            height: Int,
            targetW: Int = SIGNATURE_W,
            targetH: Int = SIGNATURE_H,
        ): IntArray {
            require(pixels.size >= width * height) { "pixels 배열이 width*height 보다 작음" }
            val out = IntArray(targetW * targetH)
            if (width <= 0 || height <= 0) return out
            for (ty in 0 until targetH) {
                val y0 = ty * height / targetH
                val y1 = ((ty + 1) * height / targetH).coerceAtLeast(y0 + 1).coerceAtMost(height)
                for (tx in 0 until targetW) {
                    val x0 = tx * width / targetW
                    val x1 = ((tx + 1) * width / targetW).coerceAtLeast(x0 + 1).coerceAtMost(width)
                    var sum = 0L
                    var n = 0
                    var y = y0
                    while (y < y1) {
                        val rowBase = y * width
                        var x = x0
                        while (x < x1) {
                            val p = pixels[rowBase + x]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            // 지각 밝기(정수 근사).
                            sum += (r * 77 + g * 150 + b * 29) shr 8
                            n++
                            x++
                        }
                        y++
                    }
                    out[ty * targetW + tx] = if (n > 0) (sum / n).toInt() else 0
                }
            }
            return out
        }

        const val SIGNATURE_W = 12
        const val SIGNATURE_H = 4
    }
}

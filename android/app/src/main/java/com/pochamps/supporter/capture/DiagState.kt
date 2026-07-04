package com.pochamps.supporter.capture

/**
 * 진단 패널(adb 대체) 상태 모델 + 포매팅 로직 — **순수 JVM**(Android 의존성 없음, 유닛 테스트 가능).
 *
 * 필드테스트에서 인식이 계속 실패할 때, 사용자가 adb 로그 없이 폰 화면에서 바로
 * 원인을 판단할 수 있게 한다:
 *  - **빈 텍스트**(OCR 이 아무 라인도 못 뽑음) → ROI 가 이름표를 벗어남/글자 잘림/저대비. → ROI 보정 필요.
 *  - **미매칭 텍스트**(OCR 은 됐는데 DB 매칭 실패) → 표시명 문자열 불일치/닉네임/폼. → 수동 검색·DB 갱신.
 *  - **매칭됨**(root+editDistance) → 정상. editDistance 로 신뢰도 파악.
 *
 * ## 흐름
 *  [RecognitionPipeline] 이 프레임마다 슬롯별 [SlotDiag] 을 만들어 UI 콜백으로 넘긴다.
 *  UI(오버레이 진단 스트립)는 [format] 으로 한 줄 문자열을 만들어 표시한다.
 */

/** 크롭(ROI 슬롯) 하나의 진단 결과 스냅샷. */
data class SlotDiag(
    /** ROI 슬롯 인덱스(0/1, fallback 포함). */
    val slot: Int,
    /** OCR 이 뽑은 원문 라인들(빈 리스트=아무것도 못 읽음). */
    val ocrLines: List<String>,
    /** 매칭된 root(미매칭이면 null). */
    val matchedRoot: String?,
    /** fuzzy 편집거리(매칭 시). 미매칭이면 null. */
    val editDistance: Int?,
    /** 이 진단이 만들어진 시각(uptimeMs). */
    val atMs: Long,
) {
    /** 원인 분류(빈 텍스트 / 미매칭 / 매칭). */
    val outcome: Outcome
        get() = when {
            matchedRoot != null -> Outcome.MATCHED
            ocrLines.isEmpty() -> Outcome.EMPTY_TEXT
            else -> Outcome.UNMATCHED_TEXT
        }

    enum class Outcome { EMPTY_TEXT, UNMATCHED_TEXT, MATCHED }
}

/**
 * 진단 스트립에 표시할 집계 상태(슬롯별 최신 스냅샷 + OCR 실행 빈도).
 *
 * @param slots 슬롯 인덱스 → 최신 [SlotDiag].
 * @param ocrRunsPerSec 최근 창(window)의 OCR 실행 빈도(회/s). 발열/스로틀 판단용.
 * @param lastRecognitionAtMs 마지막으로 **매칭 성공**한 시각(uptimeMs, 없으면 0).
 * @param nowMs 포매팅 기준 현재 시각(uptimeMs) — "n초 전" 계산용.
 */
data class DiagState(
    val slots: Map<Int, SlotDiag> = emptyMap(),
    val ocrRunsPerSec: Double = 0.0,
    val lastRecognitionAtMs: Long = 0L,
    val nowMs: Long = 0L,
) {
    companion object {
        /** OCR 원문/이름 한 줄이 너무 길면 자른다(스트립 폭 보호). */
        const val MAX_TEXT = 24

        /** 슬롯 헤더 한 줄 포맷. 예: `S0 갸라도스 d1` / `S1 OCR:빈텍스트` / `S0 미매칭 "abcd"`. */
        fun formatSlot(d: SlotDiag): String {
            val head = "S${d.slot} "
            return head + when (d.outcome) {
                SlotDiag.Outcome.MATCHED ->
                    "${clip(d.matchedRoot ?: "?")} d${d.editDistance ?: 0}"
                SlotDiag.Outcome.EMPTY_TEXT -> "OCR:빈텍스트"
                SlotDiag.Outcome.UNMATCHED_TEXT ->
                    "미매칭 \"${clip(d.ocrLines.joinToString(" ").trim())}\""
            }
        }

        /** 마지막 인식 경과 문구. "방금"/"n초 전"/"—"(없음). */
        fun formatLastSeen(state: DiagState): String {
            if (state.lastRecognitionAtMs <= 0L) return "인식 없음"
            val elapsedMs = (state.nowMs - state.lastRecognitionAtMs).coerceAtLeast(0)
            val sec = (elapsedMs / 1000).toInt()
            return when {
                sec <= 0 -> "방금 인식"
                else -> "${sec}초 전 인식"
            }
        }

        /** OCR 빈도 문구. 예: `OCR 1.1회/s`. */
        fun formatRate(state: DiagState): String =
            "OCR %.1f회/s".format(state.ocrRunsPerSec)

        /** 문자열 클립(말줄임). */
        private fun clip(s: String): String =
            if (s.length <= MAX_TEXT) s else s.take(MAX_TEXT - 1) + "…"
    }
}

/**
 * OCR 실행 빈도(회/s) 슬라이딩 윈도우 계수기 — 순수 JVM.
 * [RecognitionPipeline] 이 OCR 실행마다 [record] 를 부르고, UI 갱신 시 [ratePerSec] 를 읽는다.
 * 스레드 안전이 필요하면 호출부에서 동기화(파이프라인 워커는 단일 스레드라 불필요).
 */
class OcrRateMeter(
    /** 집계 윈도우(ms). 이 시간 안의 실행만 카운트. */
    private val windowMs: Long = 3_000L,
) {
    private val timestamps = ArrayDeque<Long>()

    /** OCR 실행 1회 기록. */
    fun record(nowMs: Long) {
        timestamps.addLast(nowMs)
        prune(nowMs)
    }

    /** 현재 윈도우 기준 회/s. 실행 이력 없으면 0. */
    fun ratePerSec(nowMs: Long): Double {
        prune(nowMs)
        if (timestamps.isEmpty()) return 0.0
        return timestamps.size.toDouble() / (windowMs / 1000.0)
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
        }
    }

    fun reset() = timestamps.clear()
}

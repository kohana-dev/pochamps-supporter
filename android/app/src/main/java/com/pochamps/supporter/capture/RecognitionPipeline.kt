package com.pochamps.supporter.capture

import android.graphics.Bitmap
import android.util.Log
import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.data.PokedexRepository
import com.pochamps.supporter.ocr.OcrEngine
import com.pochamps.supporter.overlay.OverlayCardData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 캡처→인식 파이프라인 코디네이터. DESIGN.md 3장 [1]~[7] 을 하나로 잇는다.
 *
 * ## 데이터 플로우
 *  [1] CaptureManager 가 다운스케일 Bitmap 프레임을 [submitFrame] 으로 밀어넣는다(경량 콜백 스레드).
 *  → conflate 채널로 **최신 프레임만** 워커에 전달(backpressure — 밀린 프레임 폐기).
 *  → 워커(Dispatchers.Default)에서:
 *     [2] FrameGate: ROI 변화 감지(다운샘플 해시) — 바뀐 ROI 만 진행.
 *     [3] RoiCropper: 비율 ROI 크롭(+2x 업스케일). 전부 실패 시 상단 절반 fallback.
 *     [4] OcrEngine: ML Kit 로 텍스트 추출(suspend).
 *     [5] NameMatcher(Repository.match): 정규화+fuzzy → 후보.
 *     [6] Repository: key→카드 데이터 조립.
 *     [7] OverlayRenderer: 슬롯 카드 갱신(메인 스레드로 post 는 콜백 구현이 담당).
 *
 * ## 스레딩/백프레셔
 *  - 캡처 콜백은 절대 블록하지 않음(채널 trySend, 실패=버림).
 *  - 무거운 작업(해시/크롭/OCR/매칭)은 [scope] 의 Default 디스패처에서 순차 처리.
 *  - OCR 은 내부적으로 ML Kit Task 를 await 하므로, 한 프레임이 끝나야 다음 최신 프레임을 집는다.
 *
 * @param scope 파이프라인 워커 코루틴 스코프(서비스 수명과 함께 취소).
 * @param repository 로컬 DB(매칭/카드 조립).
 * @param ocr OCR 엔진(언어별 recognizer).
 * @param roiConfigProvider 감시/크롭할 ROI 설정 **공급자**. 프레임마다 최신값을 읽으므로
 *   인앱 ROI 보정 저장(P14)이 다음 프레임부터 즉시 반영된다(재구성 불필요).
 * @param lang 표시 언어(카드 데이터 조립용).
 * @param formatProvider 배틀 포맷 **공급자**(사용률 소스, P20). 프레임/카드 조립마다 최신값을 읽으므로
 *   싱글/더블 토글이 다음 프레임부터 사용률(싱글 vs 더블 메타)에 즉시 반영된다.
 * @param onCardUpdate 슬롯 카드 갱신 콜백(구현이 메인 스레드로 post). (slot, cardData, meta).
 * @param onDiag 진단 스냅샷 콜백(P14 진단 패널). 프레임 처리 후 슬롯별 [SlotDiag] + OCR 빈도를 넘긴다.
 *   기본 no-op(진단 모드 off 여도 오버헤드 무시 가능 — 문자열 조립뿐).
 */
class RecognitionPipeline(
    private val scope: CoroutineScope,
    private val repository: PokedexRepository,
    private val ocr: OcrEngine,
    private val roiConfigProvider: () -> RoiConfig,
    private val lang: String,
    private val formatProvider: () -> BattleFormat,
    private val frameGate: FrameGate = FrameGate(),
    private val cropper: RoiCropper = RoiCropper(),
    private val decider: PipelineDecider = PipelineDecider(),
    private val health: CaptureHealth = CaptureHealth(),
    private val onCardUpdate: (slot: Int, card: OverlayCardData, meta: SlotMeta) -> Unit,
    private val onDiag: (DiagState) -> Unit = {},
    /**
     * 캡처 건강 상태 변화 콜백(K1 자동 진단, P17). 상태가 바뀔 때만 호출된다.
     * 서비스가 오버레이/알림 안내로 전달한다. 기본 no-op(상태 무관 경로 하위호환).
     */
    private val onHealth: (CaptureHealth.Health) -> Unit = {},
) {

    /** OCR 실행 빈도 계수기(진단 패널 "회/s"). 단일 워커 스레드에서만 접근. */
    private val rateMeter = OcrRateMeter()

    /** 슬롯별 최신 진단 스냅샷(진단 패널 표시용). 단일 워커 스레드에서만 접근. */
    private val slotDiags = HashMap<Int, SlotDiag>()

    /** 마지막 매칭 성공 시각(uptimeMs) — 진단 "n초 전 인식". */
    private var lastMatchAtMs: Long = 0L

    /** 슬롯 카드 갱신에 딸려오는 메타(후보 선택 시트/바꾸기 진입점 노출용). */
    data class SlotMeta(
        /** 매칭된 species root(후보 리스트 조회 키). 수동 지정 카드면 null. */
        val root: String?,
        /** 후보가 여럿이라 "바꾸기"를 노출할지. */
        val hasMoreCandidates: Boolean,
        /** 수동 핀 상태인지(핀 해제 버튼 노출용). */
        val pinned: Boolean = false,
    )

    /**
     * 유저가 후보 선택 시트에서 후보를 골랐을 때(오버레이 UI 스레드에서 호출).
     * 선택을 Decider 에 기억시키고 즉시 카드를 갱신한다(같은 root 재인식 시 유지됨).
     */
    fun chooseCandidate(slot: Int, root: String, key: String) {
        decider.rememberChoice(slot, root, key)
        val card = OverlayCardData.fromRepository(repository, key, lang, formatProvider()) ?: return
        onCardUpdate(slot, card, SlotMeta(root = root, hasMoreCandidates = true))
    }

    /**
     * 유저가 수동 검색으로 슬롯을 고정(핀)했을 때. 파이프라인이 이 슬롯을 덮어쓰지 않는다.
     */
    fun pinSlot(slot: Int, key: String) {
        decider.pin(slot, key)
        val card = OverlayCardData.fromRepository(repository, key, lang, formatProvider()) ?: return
        onCardUpdate(slot, card, SlotMeta(root = null, hasMoreCandidates = false, pinned = true))
    }

    /** 슬롯 핀 해제(다음 인식이 다시 갱신 가능). */
    fun unpinSlot(slot: Int) {
        decider.unpin(slot)
    }

    /**
     * 슬롯 강제 재인식(P18 회복 사다리). 오인식이 고착/정지 화면에 굳었을 때 즉시 다시 읽게 한다.
     *  1) 핀 상태면 먼저 핀을 해제한다 — "지금 다시 읽어"는 자동 인식 복귀를 의미하므로 자연스러운 선택
     *     (핀을 유지한 채 재인식하면 결과가 무시되어 아무 일도 안 일어난다). UI 에선 핀 중 ↻ 를 눌러도
     *     이 경로로 핀이 풀리고 재인식된다.
     *  2) Decider 의 해당 슬롯 판정 상태 초기화(lastKey/pending/기억 선택) → 다음 인식 즉시 반영.
     *  3) FrameGate 해당 ROI 무효화 → **다음 프레임에서 게이트 1회 우회**(정지 화면이라도 즉시 OCR).
     *
     * 카드 자체는 지우지 않는다(다음 인식이 올 때까지 직전 정보 유지 — 깜빡임 방지). 잘못 굳은 카드는
     * 다음 프레임 OCR 결과로 교체된다.
     */
    fun forceRescan(slot: Int) {
        if (decider.isPinned(slot)) decider.unpin(slot)
        decider.resetSlot(slot)
        frameGate.invalidate(slot)
    }
    /** 최신 프레임만 유지하는 conflate 채널(backpressure). */
    private val frameChannel = Channel<FrameJob>(Channel.CONFLATED)

    /** 워커 시작. 서비스가 캡처 시작 시 1회 호출. */
    fun start() {
        // 캡처 건강 모니터 시작(K1 자동 진단, P17). 이후 프레임/폴링으로 상태를 갱신한다.
        health.start(android.os.SystemClock.uptimeMillis())
        scope.launch(Dispatchers.Default) {
            for (job in frameChannel) {
                if (!isActive) break
                runCatching { processFrame(job.bitmap, job.timestampMs) }
                    .onFailure { e ->
                        // ML Kit 온디바이스 OCR 모델은 최초 실행 시 Play 서비스에서 다운로드된다.
                        // 그 동안 프레임마다 "module downloading" 예외가 나는데(정상·일시적),
                        // 전체 스택트레이스를 매 프레임 찍으면 로그가 폭주하므로 짧게만 남긴다.
                        val msg = e.message ?: ""
                        if (msg.contains("module", ignoreCase = true) ||
                            msg.contains("download", ignoreCase = true)
                        ) {
                            Log.i(TAG, "OCR 모델 다운로드 대기 중(최초 1회) — 프레임 스킵")
                        } else {
                            Log.w(TAG, "프레임 처리 실패", e)
                        }
                    }
            }
        }
    }

    /**
     * 캡처 콜백이 호출. 절대 블록하지 않고 최신 프레임만 남긴다.
     * Bitmap 은 콜백 후 재사용될 수 있으므로 방어적으로 복사해 소유권을 넘긴다.
     */
    fun submitFrame(bitmap: Bitmap, timestampMs: Long) {
        // 캡처 건강 감시(K1 자동 진단, P17)는 **여기서**(경량 캡처 콜백, ~초당 3회) 공급한다.
        // OCR 은 프레임당 수 초가 걸려 processFrame 은 드물게 돌므로(그 주기가 noFramesMs 를 넘겨
        // 정상인데도 NoFrames 오판), 프레임 생존/휘도 신호는 OCR 병목 이전 지점에서 재야 정확하다.
        runCatching { reportFrameHealth(bitmap, timestampMs) }

        // 재사용 Bitmap 을 워커가 나중에 읽으면 깨질 수 있으니 불변 복사본을 만든다.
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        val sent = frameChannel.trySend(FrameJob(copy, timestampMs)).isSuccess
        if (!sent) copy.recycle() // 채널이 이전 값을 밀어내면 대체 실패 시 회수.
    }

    /** 한 프레임 처리: ROI 별 게이트→크롭→OCR→매칭→판정→갱신. */
    private suspend fun processFrame(frame: Bitmap, timestampMs: Long) {
        try {
            var anyGatePassed = false
            var anyCropSucceeded = false

            // 프레임마다 최신 ROI 설정을 읽는다(인앱 보정 저장 즉시 반영, P14).
            val roiConfig = roiConfigProvider()
            for ((roiIndex, roi) in roiConfig.rois.withIndex()) {
                val rect = roi.toPixels(frame.width, frame.height)
                // [2] FrameGate: ROI 픽셀을 다운샘플해 변화 감지.
                val signature = roiSignature(frame, rect)
                if (!frameGate.shouldProcess(roiIndex, signature, timestampMs)) continue
                anyGatePassed = true

                // [3] 크롭(+업스케일).
                val crop = cropper.crop(frame, roi) ?: continue
                anyCropSucceeded = true
                processCrop(roiIndex, crop)
            }

            // ROI 크롭이 모두 실패했고, 게이트는 한 번이라도 통과했다면 상단 절반 fallback OCR.
            if (anyGatePassed && !anyCropSucceeded) {
                cropper.cropFullTopHalf(frame)?.let { fallback ->
                    processCrop(RoiCropper.FALLBACK_ROI_INDEX.coerceAtLeast(0), fallback.bitmap)
                }
            }
        } finally {
            frame.recycle()
        }
    }

    /** 크롭 하나를 OCR→매칭→판정→갱신. */
    private suspend fun processCrop(roiIndex: Int, crop: Bitmap) {
        try {
            // [4] OCR — 모든 라인 추출(단일 pickNameLine 대신 전체 라인).
            val lines = ocr.recognizeAllLines(crop)
            // OCR 실행 빈도 계수(진단 패널 "회/s" — 발열/스로틀 판단).
            val nowMs = android.os.SystemClock.uptimeMillis()
            rateMeter.record(nowMs)
            if (DIAG) Log.i(TAG, "diag roi#$roiIndex ocr=${if (lines.isEmpty()) "-" else lines} crop=${crop.width}x${crop.height}")

            if (lines.isEmpty()) {
                // 빈 텍스트 진단(ROI 이탈/글자잘림/저대비 판단 근거).
                emitDiag(SlotDiag(roiIndex, ocrLines = emptyList(), matchedRoot = null, editDistance = null, atMs = nowMs))
                return
            }
            // [5] 매칭 — 라인들 중 최적 매칭(UI 텍스트는 사전 매칭이 걸러줌, ROI 강건화 P12).
            val match = repository.matchBest(lines)
            if (DIAG) Log.i(TAG, "diag roi#$roiIndex match=$match")

            // 진단 스냅샷(원문 라인 + 매칭 root/editDistance). 미매칭이면 root=null.
            val matched = match as? com.pochamps.supporter.matching.MatchResult.Matched
            if (matched != null) lastMatchAtMs = nowMs
            emitDiag(
                SlotDiag(
                    slot = roiIndex,
                    ocrLines = lines,
                    matchedRoot = matched?.root,
                    editDistance = matched?.editDistance,
                    atMs = nowMs,
                ),
            )

            // 판정(순수 로직): 미매칭 유지 / 후보 갱신 / 동일 스킵.
            when (val action = decider.decide(roiIndex, match)) {
                is PipelineAction.UpdateCard -> {
                    // [6] 카드 데이터 조립 → [7] 오버레이 슬롯 갱신. 포맷은 매 갱신 시점 최신값(P20).
                    val card = OverlayCardData.fromRepository(repository, action.key, lang, formatProvider())
                    if (card != null) {
                        onCardUpdate(
                            action.roiIndex,
                            card,
                            SlotMeta(
                                root = action.root,
                                hasMoreCandidates = action.hasMoreCandidates,
                            ),
                        )
                    }
                }
                PipelineAction.KeepCurrent, PipelineAction.NoChange -> Unit
            }
        } finally {
            crop.recycle()
        }
    }

    /** 슬롯 진단 갱신 후 전체 [DiagState] 를 콜백으로 넘긴다(워커 스레드에서 호출). */
    private fun emitDiag(d: SlotDiag) {
        slotDiags[d.slot] = d
        onDiag(
            DiagState(
                slots = HashMap(slotDiags),
                ocrRunsPerSec = rateMeter.ratePerSec(d.atMs),
                lastRecognitionAtMs = lastMatchAtMs,
                nowMs = d.atMs,
                health = health.currentHealth(),
            ),
        )
    }

    /**
     * 프레임 전체를 저해상 그레이로 다운샘플해 평균 휘도를 구하고 [CaptureHealth] 에 공급한다.
     * 상태가 바뀌면 [onHealth] 로 서비스에 알린다(오버레이/알림 안내). 저비용(작은 격자 평균).
     */
    private fun reportFrameHealth(frame: Bitmap, timestampMs: Long) {
        val pixels = IntArray(frame.width * frame.height)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        val gray = FrameGate.downsampleGray(pixels, frame.width, frame.height)
        val avgLuma = CaptureHealth.averageLuma(gray)
        health.onFrame(avgLuma, timestampMs)?.let { onHealth(it) }
    }

    /**
     * 프레임이 안 와도(파이프 정지) NoFrames 를 판정하도록 서비스가 주기적으로 호출한다.
     * 상태가 바뀌면 [onHealth] 로 알린다. 워커 밖(메인 핸들러)에서 호출될 수 있으나
     * CaptureHealth 는 읽기/판정만 하고 변이는 원자적이라 안전.
     */
    fun evaluateHealth(nowMs: Long) {
        health.evaluate(nowMs)?.let { onHealth(it) }
    }

    /** ROI 픽셀 영역을 읽어 FrameGate 용 다운샘플 그레이 서명을 만든다. */
    private fun roiSignature(frame: Bitmap, rect: PixelRect): IntArray {
        val pixels = IntArray(rect.width * rect.height)
        frame.getPixels(pixels, 0, rect.width, rect.x, rect.y, rect.width, rect.height)
        return FrameGate.downsampleGray(pixels, rect.width, rect.height)
    }

    /**
     * 배틀 형식 전환 시 파이프라인 상태 리셋(P20). 슬롯 수(싱글 1 / 더블 2)와 사용률이 바뀌므로
     * Decider/FrameGate/진단을 초기화해 이전 형식의 슬롯 판정/선택 기억/핀이 고착되지 않게 한다.
     * (채널은 열어 둔 채 — 다음 프레임부터 새 형식 ROI/사용률로 재인식.)
     *
     * ⚠️ 카드 슬롯 제거(더블→싱글 시 2번째 슬롯 카드 정리)는 오버레이가 담당한다(여기선 판정 상태만).
     */
    fun resetForFormatChange() {
        frameGate.reset()
        decider.reset()
        slotDiags.clear()
        lastMatchAtMs = 0L
    }

    /** 파이프라인 종료: 채널 닫고 게이트/판정 초기화. OCR close 는 서비스가 담당. */
    fun stop() {
        frameChannel.close()
        frameGate.reset()
        health.reset()
        decider.reset()
        rateMeter.reset()
        slotDiags.clear()
        lastMatchAtMs = 0L
    }

    private data class FrameJob(val bitmap: Bitmap, val timestampMs: Long)

    private companion object {
        const val TAG = "RecognitionPipeline"

        /**
         * P10 E2E 진단 로그 토글. debug 빌드에서 BuildConfig.DEBUG 로 켜져, 프레임별 OCR 라인/매칭을
         * logcat 에 남긴다(에뮬 전체 파이프라인 E2E 추적용). release 는 BuildConfig.DEBUG=false 라 무음.
         */
        val DIAG = com.pochamps.supporter.BuildConfig.DEBUG
    }
}

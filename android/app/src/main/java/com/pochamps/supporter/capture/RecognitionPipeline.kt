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
 * @param roiConfig 감시/크롭할 ROI 설정.
 * @param lang 표시 언어(카드 데이터 조립용).
 * @param format 배틀 포맷(사용률 소스).
 * @param onCardUpdate 슬롯 카드 갱신 콜백(구현이 메인 스레드로 post). (slot, cardData, meta).
 */
class RecognitionPipeline(
    private val scope: CoroutineScope,
    private val repository: PokedexRepository,
    private val ocr: OcrEngine,
    private val roiConfig: RoiConfig,
    private val lang: String,
    private val format: BattleFormat,
    private val frameGate: FrameGate = FrameGate(),
    private val cropper: RoiCropper = RoiCropper(),
    private val decider: PipelineDecider = PipelineDecider(),
    private val onCardUpdate: (slot: Int, card: OverlayCardData, meta: SlotMeta) -> Unit,
) {

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
        val card = OverlayCardData.fromRepository(repository, key, lang, format) ?: return
        onCardUpdate(slot, card, SlotMeta(root = root, hasMoreCandidates = true))
    }

    /**
     * 유저가 수동 검색으로 슬롯을 고정(핀)했을 때. 파이프라인이 이 슬롯을 덮어쓰지 않는다.
     */
    fun pinSlot(slot: Int, key: String) {
        decider.pin(slot, key)
        val card = OverlayCardData.fromRepository(repository, key, lang, format) ?: return
        onCardUpdate(slot, card, SlotMeta(root = null, hasMoreCandidates = false, pinned = true))
    }

    /** 슬롯 핀 해제(다음 인식이 다시 갱신 가능). */
    fun unpinSlot(slot: Int) {
        decider.unpin(slot)
    }
    /** 최신 프레임만 유지하는 conflate 채널(backpressure). */
    private val frameChannel = Channel<FrameJob>(Channel.CONFLATED)

    /** 워커 시작. 서비스가 캡처 시작 시 1회 호출. */
    fun start() {
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
            if (DIAG) Log.i(TAG, "diag roi#$roiIndex ocr=${if (lines.isEmpty()) "-" else lines} crop=${crop.width}x${crop.height}")
            if (lines.isEmpty()) return
            // [5] 매칭 — 라인들 중 최적 매칭(UI 텍스트는 사전 매칭이 걸러줌, ROI 강건화 P12).
            val match = repository.matchBest(lines)
            if (DIAG) Log.i(TAG, "diag roi#$roiIndex match=$match")
            // 판정(순수 로직): 미매칭 유지 / 후보 갱신 / 동일 스킵.
            when (val action = decider.decide(roiIndex, match)) {
                is PipelineAction.UpdateCard -> {
                    // [6] 카드 데이터 조립 → [7] 오버레이 슬롯 갱신.
                    val card = OverlayCardData.fromRepository(repository, action.key, lang, format)
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

    /** ROI 픽셀 영역을 읽어 FrameGate 용 다운샘플 그레이 서명을 만든다. */
    private fun roiSignature(frame: Bitmap, rect: PixelRect): IntArray {
        val pixels = IntArray(rect.width * rect.height)
        frame.getPixels(pixels, 0, rect.width, rect.x, rect.y, rect.width, rect.height)
        return FrameGate.downsampleGray(pixels, rect.width, rect.height)
    }

    /** 파이프라인 종료: 채널 닫고 게이트/판정 초기화. OCR close 는 서비스가 담당. */
    fun stop() {
        frameChannel.close()
        frameGate.reset()
        decider.reset()
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

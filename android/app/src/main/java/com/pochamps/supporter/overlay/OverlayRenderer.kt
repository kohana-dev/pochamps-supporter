package com.pochamps.supporter.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * [7] OverlayRenderer — WindowManager(TYPE_APPLICATION_OVERLAY) 위에 Compose 카드를 렌더한다.
 *
 * ## 터치 모델 (DESIGN.md 2장 준수)
 * 창 자체를 카드 bounds 크기로만 유지(WRAP_CONTENT). 창 밖은 자동으로 게임에 통과.
 * 시트/확장 패널도 이 창 내부에서 세로로 확장되므로(창=카드 스택), 터치 통과가 보존된다.
 *
 * ## P4: 3단계 점진 공개 + 후보 선택/메가/수동 검색
 *  - 슬롯별 표시 단계(칩/카드/확장)를 [stageBySlot] 으로 독립 관리.
 *  - 후보 선택 시트/수동 검색 시트를 창 안에서 오버레이(같은 창에 확장).
 *  - 메가 토글 상태는 슬롯별 [megaSelBySlot].
 *  - 확장 패널은 N초 무조작 시 자동 축소([autoCollapseMs]).
 *
 * ## 콜백(파이프라인 연결)
 *  UI 상호작용(후보 선택/핀/검색)은 여기서 콜백으로 위임한다.
 *  - [onChooseCandidate]: (slot, root, key) 후보 확정.
 *  - [onPinSlot]: (slot, key) 수동 고정.
 *  - [onUnpinSlot]: (slot) 핀 해제.
 *  - [searchProvider]: (query) → 검색 결과. 수동 검색 시트가 사용.
 */
class OverlayRenderer(
    private val context: Context,
    private val positionStore: OverlayPositionStore,
    /** 후보 선택 확정 콜백. */
    private val onChooseCandidate: (slot: Int, root: String, key: String) -> Unit = { _, _, _ -> },
    /** 수동 검색 결과를 슬롯에 고정. */
    private val onPinSlot: (slot: Int, key: String) -> Unit = { _, _ -> },
    /** 슬롯 핀 해제. */
    private val onUnpinSlot: (slot: Int) -> Unit = {},
    /**
     * 슬롯 강제 재인식(P18): 이 슬롯을 즉시 다시 읽는다(오인식 고착 탈출).
     * 서비스가 파이프라인 [RecognitionPipeline.forceRescan] 으로 위임(decider 리셋 + FrameGate 무효화).
     */
    private val onForceRescan: (slot: Int) -> Unit = {},
    /** 후보 리스트 조회(root → 후보 카드 데이터). 시트가 표시할 후보 칩. */
    private val candidateProvider: (root: String) -> List<CandidateChoice> = { emptyList() },
    /** 이름 부분일치 검색(query → 결과). 수동 검색 시트. */
    private val searchProvider: (query: String) -> List<SearchChoice> = { emptyList() },
    /** 확장 패널 자동 축소 지연(ms). 0 이하면 자동 축소 안 함. */
    private val autoCollapseMs: Long = 8_000L,
    /** 캡처 중단 카드의 "재시작" 탭 콜백(서비스가 MainActivity 를 다시 연다). */
    onRestart: () -> Unit = {},
    /**
     * 종료(P16): 오버레이 카드 그립 오래누르기/×/알림 종료로 앱·오버레이·캡처를 완전히 끈다.
     * 서비스가 주입 — 캡처 중지 + 오버레이 제거 + stopSelf.
     */
    onExit: () -> Unit = {},
    /** 오버레이 카드 초기 스케일(P16, 설정값). 이후 [setScale] 로 즉시 갱신. */
    initialScale: Float = OverlayScale.DEFAULT,
    /**
     * 배틀 형식 빠른 토글 콜백(P20). 오버레이의 싱글/더블 세그먼트를 탭하면 호출된다.
     * 서비스가 설정 저장 + 파이프라인 리셋 + 남는 슬롯 정리를 수행한다. 기본 no-op.
     */
    onSelectFormat: (com.pochamps.supporter.data.BattleFormat) -> Unit = {},
    /** 오버레이 형식 토글 초기 상태(P20, 설정값). */
    initialFormat: com.pochamps.supporter.data.BattleFormat =
        com.pochamps.supporter.data.BattleFormat.DOUBLES,
    /**
     * 이름 영역 보정(ROI) 진입(P21). 컨트롤 바의 보정 아이콘 탭 → 서비스가 ACTION_CALIBRATE 재사용으로
     * 보정 오버레이를 연다(인식 실패 현장에서 즉시 ROI 맞춤). 기본 no-op.
     */
    onCalibrate: () -> Unit = {},
    /**
     * 진단 ON/OFF 즉시 토글(P21). 컨트롤 바의 진단 버튼 탭 → 서비스가 설정 영속 + 스트립 표시 반영.
     * 인자는 새 상태(true=켬). 기본 no-op.
     */
    onToggleDiag: (Boolean) -> Unit = {},
    /**
     * 최소화 상태 영속 저장소(P21). 컨트롤 바 최소화/복원 시 SharedPreferences 로 상태를 남긴다.
     * 재시작 후에도 최소화 상태가 유지된다. 기본은 인메모리(비영속) — 서비스가 실제 store 주입.
     */
    private val minimizeStore: MinimizeStore = object : MinimizeStore {
        private var s = MinimizeState.DEFAULT
        override fun load() = s
        override fun save(state: MinimizeState) { s = state }
    },
) : LifecycleOwner, SavedStateRegistryOwner {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // --- 소유자(lifecycle / savedState) ---
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    // --- 창/뷰 상태 ---
    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var added = false

    /** ROI 슬롯별 카드 데이터(더블배틀 대응 — 최대 2장). */
    private val cardsBySlot = mutableStateMapOf<Int, OverlayCardData>()

    /** 슬롯별 표시 단계(칩/카드/확장). 각 카드 독립. */
    private val stageBySlot = mutableStateMapOf<Int, CardStage>()

    /** 슬롯별 메타(후보 여부/핀 상태 — 바꾸기·핀해제 버튼 노출). */
    private val metaBySlot = mutableStateMapOf<Int, SlotUiMeta>()

    /** 슬롯별 메가 선택(-1=base, 0/1=megaForms 인덱스). */
    private val megaSelBySlot = mutableStateMapOf<Int, Int>()

    /** 열려 있는 시트 상태(null=없음). 한 번에 하나만. */
    private val openSheet = mutableStateOf<SheetState?>(null)

    /** 확장 진입 시각(슬롯별) — 자동 축소 판정용. */
    private val expandedAtBySlot = HashMap<Int, Long>()

    /** 현재 창이 포커스를 잡고 있는지(IME 입력용). 검색 시트 열릴 때만 true. */
    private var focusable = false

    /** 인식 실패(수동 검색 진입) 상태 슬롯. 카드 대신 FailureCard 렌더. */
    private val failureSlots = mutableStateListOf<Int>()

    /** 표시 순서를 안정화하기 위한 슬롯 목록(등장 순). */
    private val slotOrder = mutableStateListOf<Int>()

    /**
     * 캡처 중단 상태(화면잠금/사용자 중단). null=정상, non-null=중단 카드 표시(DESIGN.md 5장).
     * 중단되면 프로젝션 토큰이 죽으므로 재시작은 콜백([onRestart])이 MainActivity 를 다시 연다.
     */
    private val captureStopped = mutableStateOf(false)

    /** 캡처 재시작 콜백(중단 카드의 "재시작" 탭). 서비스가 주입. */
    private var onRestart: () -> Unit = {}

    /** 종료 콜백(P16). 카드 오래누르기/×/알림 종료. 서비스가 주입. */
    private var onExit: () -> Unit = {}

    /** 오버레이 카드 스케일(P16). 설정 변경 시 [setScale] 로 즉시 반영. */
    private val scale = mutableStateOf(OverlayScale.DEFAULT)

    /** 현재 배틀 형식(P20, 빠른 토글 세그먼트 표시용). 토글/설정 변경 시 [setFormat] 로 갱신. */
    private val battleFormat =
        mutableStateOf(com.pochamps.supporter.data.BattleFormat.DOUBLES)

    /**
     * 캡처 세션 활성 여부(P20). true 면 카드가 아직 없어도 형식 빠른 토글을 표시한다
     * (대전 시작~첫 인식 전에도 형식을 미리 바꿀 수 있게). 데모/보정 전용 세션에서는 false 로 두어
     * 토글을 숨긴다. 서비스가 실캡처 파이프라인 시작 시 [setCaptureActive] 로 켠다.
     */
    private val captureActive = mutableStateOf(false)

    /**
     * 시트 측면 flyout 방향(P16). null=아직 미정(또는 세로/시트 없음), RIGHT/LEFT=측면 배치.
     * [repositionForSheet] 가 SheetLayout 계산 후 세팅 → Row 배치 순서와 창 위치가 함께 갱신된다.
     */
    private val sheetSideDir = mutableStateOf<SheetLayout.SideDir?>(null)

    /**
     * 시트 열기 직전(=카드만 있을 때)의 창 크기 스냅샷(px). 시트가 열리며 커진 창을 화면 안으로
     * 재조정할 때 카드 크기 기준으로 SheetLayout 을 계산하려고 보관한다. 시트 열림 콜백에서 갱신.
     */
    private var cardOnlyW = 0
    private var cardOnlyH = 0

    /**
     * 장시간 미인식 시 "배틀명 표시 ON" 1회 안내 배너 노출 여부(DESIGN.md 5장 이름 미표시).
     * true 면 배너 표시. [dismissBattleNamesHint] 로 닫으면 세션 동안 다시 안 뜸.
     */
    private val showBattleNamesHint = mutableStateOf(false)
    private var battleNamesHintShown = false

    /**
     * 진단 모드(P14). true 면 오버레이 하단에 소형 진단 스트립을 표시한다(설정 토글).
     * 카드가 없어도(빈 텍스트 계속) 스트립은 뜨므로 필드테스트에서 원인 판단이 가능하다.
     */
    private val diagEnabled = mutableStateOf(false)

    /** 최신 진단 스냅샷(진단 스트립 표시용). null=아직 없음. */
    private val diagState = mutableStateOf<com.pochamps.supporter.capture.DiagState?>(null)

    /**
     * 캡처 건강 상태(K1 자동 진단, P17). null=정상(안내 없음).
     * BLACK_SCREEN=FLAG_SECURE 차단 안내, NO_FRAMES=프레임 미수신 안내. Healthy 복귀 시 null 로 해제.
     */
    private val captureHealth =
        mutableStateOf<com.pochamps.supporter.capture.CaptureHealth.Health?>(null)

    /** 형식 토글 콜백(P20). 서비스가 주입. */
    private var onSelectFormat: (com.pochamps.supporter.data.BattleFormat) -> Unit = {}

    /** 보정 진입 콜백(P21). 서비스가 주입(ACTION_CALIBRATE). */
    private var onCalibrate: () -> Unit = {}

    /** 진단 ON/OFF 토글 콜백(P21). 서비스가 주입(설정 영속 + 스트립 반영). */
    private var onToggleDiag: (Boolean) -> Unit = {}

    /**
     * 최소화 상태(P21). true=최소화(작은 핸들만), false=펼침(컨트롤 바+카드).
     * 초기값은 영속 저장소에서 로드 → 재시작 후에도 유지.
     */
    private val minimized = mutableStateOf(false)

    init {
        savedStateController.performRestore(null)
        this.onRestart = onRestart
        this.onExit = onExit
        this.onSelectFormat = onSelectFormat
        this.onCalibrate = onCalibrate
        this.onToggleDiag = onToggleDiag
        scale.value = OverlayScale.snap(initialScale)
        battleFormat.value = initialFormat
        minimized.value = minimizeStore.load().minimized
    }

    /** 최소화 ↔ 복원 토글(P21). 상태를 영속 저장 후 다음 렌더에 반영. */
    private fun toggleMinimized() {
        val next = MinimizeState(minimized.value).toggled()
        minimized.value = next.minimized
        minimizeStore.save(next)
    }

    /** 진단 상태를 토글하고 콜백 통지(P21, 컨트롤 바 진단 버튼). */
    private fun toggleDiagnostics() {
        val next = !diagEnabled.value
        diagEnabled.value = next
        onToggleDiag(next)
    }

    /**
     * 배틀 형식 표시 갱신(P20). 서비스가 설정 반영 후 호출 → 세그먼트 하이라이트가 즉시 바뀐다.
     * 슬롯 정리(더블→싱글 시 2번째 카드 제거)는 [pruneSlotsAbove] 로 별도 수행.
     */
    fun setFormat(format: com.pochamps.supporter.data.BattleFormat) {
        battleFormat.value = format
    }

    /** 캡처 세션 활성 표시(P20). 실캡처 시작 시 켜서 카드 전에도 형식 토글을 노출. */
    fun setCaptureActive(active: Boolean) {
        captureActive.value = active
    }

    /**
     * 형식 전환 시 남는 슬롯 카드 정리(P20). 더블(2슬롯)→싱글(1슬롯) 전환 시 두 번째 슬롯(index≥1)의
     * 카드/시트/상태를 제거해 싱글에서 유령 카드가 남지 않게 한다. [maxSlot] 이상 슬롯을 모두 제거.
     */
    fun pruneSlotsAbove(maxSlot: Int) {
        val toRemove = slotOrder.filter { it > maxSlot }.toList()
        for (s in toRemove) removeCard(s)
    }

    /**
     * 오버레이 카드 스케일 갱신(P16). 설정에서 변경 즉시 다음 렌더에 반영된다(상태 반영 → recompose).
     * 스케일은 밀도 기반이라 창(WRAP_CONTENT) 크기가 자연히 따라가고 잘림이 없다.
     */
    fun setScale(newScale: Float) {
        scale.value = OverlayScale.snap(newScale)
    }

    fun show() {
        if (added) return
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        val lp = buildLayoutParams()
        layoutParams = lp
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayRenderer)
            setViewTreeSavedStateRegistryOwner(this@OverlayRenderer)
            setContent { OverlayRoot() }
        }
        composeView = view
        windowManager.addView(view, lp)
        added = true
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** 단일 카드 갱신(데모/싱글 경로 하위호환). 슬롯 0. */
    fun updateCard(data: OverlayCardData?) {
        if (data == null) removeCard(0)
        else updateSlot(0, data, SlotUiMeta(root = null, hasMoreCandidates = false, pinned = false))
    }

    /**
     * 인식 실패 카드 표시(수동 검색 진입). 해당 슬롯에 카드가 없을 때 안내용.
     * 이미 카드가 있으면(직전 인식 성공) 그 카드를 유지하는 게 나으므로 무시.
     */
    fun showFailure(slot: Int) {
        if (cardsBySlot.containsKey(slot)) return
        if (slot !in slotOrder) slotOrder.add(slot)
        if (slot !in failureSlots) failureSlots.add(slot)
    }

    /** ROI 슬롯 카드 갱신(메타 포함). 파이프라인이 호출. */
    fun updateSlot(slot: Int, data: OverlayCardData, meta: SlotUiMeta = SlotUiMeta()) {
        // 인식 성공 → 중단/미인식 안내 상태 해제.
        captureStopped.value = false
        showBattleNamesHint.value = false
        failureSlots.remove(slot)
        if (slot !in slotOrder) slotOrder.add(slot)
        // key 가 바뀌면(다른 포켓몬으로 교체) 메가 선택을 base(-1)로 초기화한다.
        // (안 하면 직전 포켓몬의 메가 선택 인덱스가 새 포켓몬에 남아, 유저가 토글한 적 없는데도
        //  effectiveCard 가 새 포켓몬의 메가 폼을 표시하는 오표시가 난다 — 더블배틀 슬롯 교체 시 재현.)
        val prevKey = cardsBySlot[slot]?.key
        cardsBySlot[slot] = data
        metaBySlot[slot] = meta
        megaSelBySlot[slot] = MegaSelection.resolveOnUpdate(prevKey, data.key, megaSelBySlot[slot])
        if (stageBySlot[slot] == null) stageBySlot[slot] = CardStage.CHIP
    }

    /** 하위호환 오버로드(메타 없이). */
    fun updateSlot(slot: Int, data: OverlayCardData) =
        updateSlot(slot, data, SlotUiMeta())

    fun removeCard(slot: Int) {
        cardsBySlot.remove(slot)
        stageBySlot.remove(slot)
        metaBySlot.remove(slot)
        megaSelBySlot.remove(slot)
        expandedAtBySlot.remove(slot)
        failureSlots.remove(slot)
        slotOrder.remove(slot)
        if (openSheet.value?.slot == slot) openSheet.value = null
    }

    fun clearCards() {
        cardsBySlot.clear()
        stageBySlot.clear()
        metaBySlot.clear()
        megaSelBySlot.clear()
        expandedAtBySlot.clear()
        failureSlots.clear()
        slotOrder.clear()
        openSheet.value = null
    }

    /**
     * 캡처 중단 상태 표시(DESIGN.md 5장). 화면잠금/사용자 중단 시 포켓몬 카드를 걷고
     * "캡처 중단됨 + 재시작" 상태 카드만 남긴다. 재시작 탭은 [onRestart] 로 위임.
     */
    fun showCaptureStopped() {
        cardsBySlot.clear()
        stageBySlot.clear()
        metaBySlot.clear()
        megaSelBySlot.clear()
        expandedAtBySlot.clear()
        failureSlots.clear()
        slotOrder.clear()
        openSheet.value = null
        showBattleNamesHint.value = false
        captureHealth.value = null // 중단 카드가 우선 — 건강 안내는 걷는다.
        captureStopped.value = true
    }

    /** 장시간 미인식 시 "배틀명 표시 ON" 1회 안내 배너(세션당 1회). */
    fun showBattleNamesHintOnce() {
        if (battleNamesHintShown) return
        if (captureStopped.value) return
        battleNamesHintShown = true
        showBattleNamesHint.value = true
    }

    /** 배너 닫기(유저 탭 or 인식 성공 시). */
    fun dismissBattleNamesHint() {
        showBattleNamesHint.value = false
    }

    /** 진단 모드 on/off(설정 토글). off 면 스트립 숨김. */
    fun setDiagnosticsEnabled(enabled: Boolean) {
        diagEnabled.value = enabled
    }

    /** 진단 스냅샷 갱신(파이프라인 콜백 → 메인 스레드). 진단 모드 off 여도 저장만(표시 안 함). */
    fun updateDiag(state: com.pochamps.supporter.capture.DiagState) {
        diagState.value = state
    }

    /**
     * 캡처 건강 상태 갱신(K1 자동 진단, P17). 파이프라인 → 서비스 → 메인 스레드.
     *  - HEALTHY → 안내 카드 자동 해제(null).
     *  - BLACK_SCREEN → FLAG_SECURE 차단 안내 카드.
     *  - NO_FRAMES → 프레임 미수신 안내 카드(재시작 진입점).
     * 캡처 중단(showCaptureStopped) 상태에서는 그 카드가 우선이므로 건강 안내를 덮지 않는다.
     */
    fun updateCaptureHealth(h: com.pochamps.supporter.capture.CaptureHealth.Health) {
        captureHealth.value =
            if (h == com.pochamps.supporter.capture.CaptureHealth.Health.HEALTHY) null else h
    }

    fun destroy() {
        if (!added) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            return
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        composeView?.let { runCatching { windowManager.removeView(it) } }
        composeView = null
        layoutParams = null
        added = false
    }

    // --- 내부 ---

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            baseFlags(focusable = false),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = positionStore.load() ?: defaultPosition()
            x = saved.x
            y = saved.y
        }
    }

    /**
     * 창 플래그 조립. 기본은 `FLAG_NOT_FOCUSABLE`(게임 키 포커스 보존).
     * [focusable] 이 true 면 그 플래그를 빼서 창이 IME/키 포커스를 잡을 수 있게 한다(검색 입력용).
     * `FLAG_LAYOUT_NO_LIMITS` 는 소형 창 전략(창=카드)에서 화면 밖 배치 허용 위해 항상 유지.
     */
    private fun baseFlags(focusable: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return flags
    }

    private fun defaultPosition(): OverlayPosition {
        val metrics = context.resources.displayMetrics
        val approxCardW = (220 * metrics.density).toInt()
        val approxCardH = (120 * metrics.density).toInt()
        return OverlayPosition.default(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            cardWidth = approxCardW,
            cardHeight = approxCardH,
        )
    }

    private fun onDrag(dx: Float, dy: Float) {
        val lp = layoutParams ?: return
        val view = composeView ?: return
        val metrics = context.resources.displayMetrics
        val newPos = OverlayPosition(lp.x + dx.toInt(), lp.y + dy.toInt())
            .clampedTo(
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                cardWidth = view.width.takeIf { it > 0 } ?: (metrics.widthPixels / 2),
                cardHeight = view.height.takeIf { it > 0 } ?: (metrics.heightPixels / 2),
            )
        lp.x = newPos.x
        lp.y = newPos.y
        windowManager.updateViewLayout(view, lp)
    }

    private fun persistPosition() {
        val lp = layoutParams ?: return
        positionStore.save(OverlayPosition(lp.x, lp.y))
    }

    /** 화면이 가로 방향인지(측면 flyout 판정). */
    private fun isLandscape(): Boolean {
        val m = context.resources.displayMetrics
        return m.widthPixels > m.heightPixels
    }

    /** 시트 열기 직전 카드만 있는 창 크기를 스냅샷(측면 flyout 계산 기준). */
    private fun captureCardBounds() {
        val view = composeView ?: return
        if (view.width > 0) cardOnlyW = view.width
        if (view.height > 0) cardOnlyH = view.height
    }

    /**
     * 시트가 열려 커진 창(=카드+시트)이 화면을 넘지 않도록 위치를 재조정한다(P16).
     *  - 가로: SheetLayout 이 측면 방향(RIGHT/LEFT)을 정하고, Row 배치 순서를 위해 [sheetSideDir] 세팅.
     *          그 후 실제 측면 배치가 recompose 되면 한 번 더 측정해 창 x/y 를 화면 안으로 clamp.
     *  - 세로: 아래 전개 → 커진 창 높이가 화면 아래를 넘으면 창 y 를 위로 당겨 clamp.
     */
    private fun repositionForSheet() {
        val lp = layoutParams ?: return
        val view = composeView ?: return
        val m = context.resources.displayMetrics
        val cardW = cardOnlyW.takeIf { it > 0 } ?: view.width
        val cardH = cardOnlyH.takeIf { it > 0 } ?: view.height

        if (isLandscape()) {
            // 측면 flyout: 시트 폭은 아직 미측정 → 스케일 반영한 시트 기본폭 추정(widthIn max 320dp).
            val sheetW = (280 * m.density * scale.value).toInt()
            val sheetH = cardH // 대략 카드 높이만큼(시트 리스트는 화면 높이로 clamp됨).
            val result = SheetLayout.open(
                landscape = true,
                screenWidth = m.widthPixels,
                screenHeight = m.heightPixels,
                winX = lp.x,
                winY = lp.y,
                cardWidth = cardW,
                cardHeight = cardH,
                sheetWidth = sheetW,
                sheetHeight = sheetH,
                gap = (8 * m.density).toInt(),
            )
            sheetSideDir.value = result.sideDir
            lp.x = result.windowX
            lp.y = result.windowY
            runCatching { windowManager.updateViewLayout(view, lp) }
            // 측면 배치가 실제로 그려진 뒤 최종 크기로 다시 한 번 화면 안으로 clamp.
            mainHandler.postDelayed({ clampWindowIntoScreen() }, 32L)
        } else {
            sheetSideDir.value = null
            // 세로: 실제로 커진 뒤 clamp(시트가 아래에 붙어 창 높이가 늘어남).
            mainHandler.postDelayed({ clampWindowIntoScreen() }, 32L)
        }
    }

    /** 시트가 닫히면 측면 방향을 해제하고, 남은 카드가 화면 안에 있게 clamp. */
    private fun repositionForClose() {
        sheetSideDir.value = null
        mainHandler.postDelayed({ clampWindowIntoScreen() }, 32L)
    }

    /** 현재 창(측정된 실제 크기)이 화면을 넘으면 좌/상단으로 당겨 화면 안으로 clamp. */
    private fun clampWindowIntoScreen() {
        val lp = layoutParams ?: return
        val view = composeView ?: return
        val m = context.resources.displayMetrics
        val w = view.width.takeIf { it > 0 } ?: return
        val h = view.height.takeIf { it > 0 } ?: return
        val clamped = OverlayPosition(lp.x, lp.y)
            .clampedTo(m.widthPixels, m.heightPixels, w, h)
        if (clamped.x != lp.x || clamped.y != lp.y) {
            lp.x = clamped.x
            lp.y = clamped.y
            runCatching { windowManager.updateViewLayout(view, lp) }
        }
    }

    /**
     * IME 포커스 토글(P5 핵심 수정).
     *
     * 오버레이 창은 평소 `FLAG_NOT_FOCUSABLE` 로 게임에서 키 포커스를 뺏지 않는다.
     * 그러나 이 플래그가 켜져 있으면 `BasicTextField` 가 소프트 키보드 포커스를 잡지 못해
     * 수동 검색 입력이 불가능하다(P4 가 남긴 최대 리스크).
     *
     * 해결: 검색 시트가 열릴 때만 `FLAG_NOT_FOCUSABLE` 를 잠시 제거하고(=창이 포커스/IME 획득),
     * 닫히면 즉시 복원한다. `updateViewLayout` 으로 즉시 반영.
     *
     * @param wantFocus true=포커스 획득(검색 시트 열림), false=포커스 반납(닫힘/기본).
     */
    private fun setFocusable(wantFocus: Boolean) {
        if (focusable == wantFocus) return
        val lp = layoutParams ?: return
        val view = composeView ?: return
        focusable = wantFocus
        lp.flags = baseFlags(focusable = wantFocus)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    // --- 단계 전환 로직(탭 순환: 칩→카드→확장→칩) ---

    private fun cycleStage(slot: Int) {
        val next = when (stageBySlot[slot] ?: CardStage.CHIP) {
            CardStage.CHIP -> CardStage.CARD
            CardStage.CARD -> CardStage.EXPANDED
            CardStage.EXPANDED -> CardStage.CHIP
        }
        stageBySlot[slot] = next
        if (next == CardStage.EXPANDED) expandedAtBySlot[slot] = now()
        else expandedAtBySlot.remove(slot)
    }

    /** 확장 패널에서 유저가 조작할 때 자동 축소 타이머를 리셋(무조작 시간 재기). */
    private fun touchExpanded(slot: Int) {
        if (stageBySlot[slot] == CardStage.EXPANDED) expandedAtBySlot[slot] = now()
    }

    private fun now(): Long = android.os.SystemClock.uptimeMillis()

    /** 현재 표시할(메가 선택 반영) 카드 데이터. */
    private fun effectiveCard(slot: Int): OverlayCardData? {
        val base = cardsBySlot[slot] ?: return null
        val sel = megaSelBySlot[slot] ?: -1
        return if (sel >= 0 && sel < base.megaForms.size) base.megaForms[sel].card else base
    }

    // --- 오버레이 루트 컴포저블 ---

    @androidx.compose.runtime.Composable
    private fun OverlayRoot() {
        // 캡처 중단 상태 카드(포켓몬 카드보다 우선). 단독 렌더.
        if (captureStopped.value) {
            CaptureStoppedCard(onRestart = { onRestart() })
            return
        }

        // 캡처 건강 안내 카드(K1 자동 진단, P17). 검정/프레임미수신 시 원인을 명확히 고지.
        // 포켓몬 카드보다 우선(캡처가 사실상 무용한 상태이므로 다른 카드는 신뢰할 수 없음).
        val healthState by captureHealth
        healthState?.let { h ->
            CaptureHealthCard(
                health = h,
                onRestart = { onRestart() },
                onDismiss = { captureHealth.value = null },
            )
            return
        }

        val hintVisible by showBattleNamesHint
        val diagOn by diagEnabled
        val diag by diagState
        val diagVisible = diagOn && diag != null
        val captureOn by captureActive
        val isMinimized by minimized

        val dragModForHandle = Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDrag = { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                },
                onDragEnd = { persistPosition() },
            )
        }

        // P21: 최소화 상태 — 캡처가 켜져 있으면 카드 유무와 무관하게 작은 핸들만 렌더(거의 안 가림).
        // 캡처가 꺼진 상태(데모 종료 등)면 핸들도 숨겨 화면을 완전히 비운다.
        if (isMinimized) {
            if (!captureOn) return
            val scaleValue by scale
            CompositionLocalProvider(LocalOverlayScale provides scaleValue) {
                MinimizedHandle(
                    dragModifier = dragModForHandle,
                    onRestore = { toggleMinimized() },
                )
            }
            return
        }

        // 캡처 활성(P20/P21)이면 카드가 없어도 컨트롤 바(형식/검색/보정/진단/최소화)를 위해 렌더한다.
        if (slotOrder.isEmpty() && !hintVisible && !diagVisible && !captureOn) return

        // 자동 축소 타이머: 주기적으로 확장 시각을 확인해 무조작 N초 경과 슬롯을 CARD 로 축소.
        if (autoCollapseMs > 0) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(1_000L)
                    val cutoff = now() - autoCollapseMs
                    val toCollapse = expandedAtBySlot.filter { it.value <= cutoff }.keys.toList()
                    for (s in toCollapse) {
                        if (stageBySlot[s] == CardStage.EXPANDED) stageBySlot[s] = CardStage.CARD
                        expandedAtBySlot.remove(s)
                    }
                }
            }
        }

        val dragMod = Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDrag = { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                },
                onDragEnd = { persistPosition() },
            )
        }

        val sheet by openSheet

        // IME 포커스 토글: 검색 시트가 열려 있는 동안만 창이 포커스를 잡도록 한다.
        // (닫히거나 후보 시트로 바뀌면 즉시 포커스 반납 → 게임 키 입력 보존.)
        val searchOpen = sheet is SheetState.Search
        androidx.compose.runtime.LaunchedEffect(searchOpen) {
            setFocusable(searchOpen)
        }

        // P16: 가로에서 시트가 열리면 옆으로(측면 flyout) 전개하고 창 위치를 화면 안으로 재조정한다.
        //  - 시트가 열릴 때/방향이 정해질 때 창 x/y 를 SheetLayout 계산값으로 옮긴다.
        //  - 세로면 아래 전개(기존) + 커진 창 아래 넘침 clamp.
        val sheetOpen = sheet != null
        val sideDir by sheetSideDir
        androidx.compose.runtime.LaunchedEffect(sheetOpen) {
            // 카드가 그려진 뒤(다음 프레임) 위치를 잡기 위해 한 틱 양보.
            kotlinx.coroutines.delay(16L)
            if (sheetOpen) repositionForSheet() else repositionForClose()
        }

        val scaleValue by scale

        CompositionLocalProvider(LocalOverlayScale provides scaleValue) {
            // 측면 flyout 여부: 가로 + 시트 열림 + 방향이 정해짐.
            if (sheetOpen && isLandscape() && sideDir != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (sideDir == SheetLayout.SideDir.LEFT) {
                        SheetContent(sheet, sideFlyout = true)
                        CardStack(dragMod, hintVisible, diagVisible, diag)
                    } else {
                        CardStack(dragMod, hintVisible, diagVisible, diag)
                        SheetContent(sheet, sideFlyout = true)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardStack(dragMod, hintVisible, diagVisible, diag)
                    SheetContent(sheet, sideFlyout = false)
                }
            }
        }
    }

    /** 카드 스택(배너 + 슬롯 카드들 + 진단 스트립). 측면/아래 배치 공통. */
    @androidx.compose.runtime.Composable
    private fun CardStack(
        dragMod: Modifier,
        hintVisible: Boolean,
        diagVisible: Boolean,
        diag: com.pochamps.supporter.capture.DiagState?,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 항상 보이는 컨트롤 바(P21): 최소화 · 형식(P20 통합) · 검색 · 보정 · 진단.
            // 인식 실패로 카드가 없어도(slotOrder.isEmpty) 캡처가 켜져 있으면 이 바가 진입점을 유지한다.
            // 바만 터치를 받고 그 밖은 게임으로 통과(창=카드 전략 보존). 실캡처/데모 세션에서만 노출.
            val fmt by battleFormat
            val captureOn by captureActive
            val diagNow by diagEnabled
            if (captureOn) {
                ControlBar(
                    isDoubles = fmt == com.pochamps.supporter.data.BattleFormat.DOUBLES,
                    diagOn = diagNow,
                    dragModifier = dragMod,
                    onMinimize = { toggleMinimized() },
                    onSelectFormat = { doubles ->
                        onSelectFormat(
                            if (doubles) com.pochamps.supporter.data.BattleFormat.DOUBLES
                            else com.pochamps.supporter.data.BattleFormat.SINGLES,
                        )
                    },
                    // 카드가 없어도 검색: ControlSearch 로 핀 대상 슬롯을 정해 검색 시트를 연다.
                    onSearch = {
                        val target = ControlSearch.targetSlot(fmt, slotOrder.toSet())
                        captureCardBounds()
                        openSheet.value = SheetState.Search(target, "")
                    },
                    onCalibrate = { onCalibrate() },
                    onToggleDiag = { toggleDiagnostics() },
                )
            }
            // 장시간 미인식 안내 배너(1회). 인식 성공 시 자동으로 사라진다.
            if (hintVisible) {
                BattleNamesHintBanner(onDismiss = { dismissBattleNamesHint() })
            }
            val primarySlot = slotOrder.minOrNull()
            for (slot in slotOrder.sorted()) {
                // 인식 실패 슬롯 → FailureCard(수동 검색 진입).
                if (slot in failureSlots && !cardsBySlot.containsKey(slot)) {
                    FailureCard(
                        dragModifier = if (slot == primarySlot) dragMod else Modifier,
                        onOpenSearchSheet = { captureCardBounds(); openSheet.value = SheetState.Search(slot, "") },
                        // ↻ 강제 재인식(P18): 검색 없이 즉시 다시 읽기(일시적 미인식 탈출).
                        onForceRescan = { onForceRescan(slot) },
                    )
                    continue
                }
                val card = effectiveCard(slot) ?: continue
                val baseCard = cardsBySlot[slot] ?: continue
                val meta = metaBySlot[slot] ?: SlotUiMeta()
                val stage = stageBySlot[slot] ?: CardStage.CHIP
                val megaSel = megaSelBySlot[slot] ?: -1

                OverlayCard(
                    data = card,
                    stage = stage,
                    meta = meta,
                    megaForms = baseCard.megaForms,
                    megaSelection = megaSel,
                    dragModifier = if (slot == primarySlot) dragMod else Modifier,
                    onTapCycle = { cycleStage(slot) },
                    onInteract = { touchExpanded(slot) },
                    onSelectMega = { idx ->
                        megaSelBySlot[slot] = idx
                        touchExpanded(slot)
                    },
                    onOpenCandidateSheet = {
                        meta.root?.let { root ->
                            captureCardBounds()
                            openSheet.value = SheetState.Candidates(slot, root)
                        }
                    },
                    onUnpin = { onUnpinSlot(slot); metaBySlot[slot] = meta.copy(pinned = false) },
                    // 🔍 수동 지정(P18): 어떤 카드가 떠 있든(정상/오인식) 검색 시트로 올바른 포켓몬 지정 → 핀.
                    onOpenSearch = { captureCardBounds(); openSheet.value = SheetState.Search(slot, "") },
                    // ↻ 강제 재인식(P18): 이 슬롯 즉시 다시 읽기. 핀 상태면 파이프라인이 핀 해제 후 재인식하므로
                    // 여기서도 UI 핀 배지를 걷어 상태를 일치시킨다.
                    onForceRescan = {
                        if (meta.pinned) metaBySlot[slot] = meta.copy(pinned = false)
                        onForceRescan(slot)
                    },
                    // 종료 진입점은 주 카드(첫 슬롯)에만 붙인다(× + 그립 오래누르기).
                    onExit = if (slot == primarySlot) ({ onExit() }) else null,
                )
            }

            // 진단 스트립(P14) — 설정 토글 on 일 때만. 카드 밑에 고정.
            if (diagVisible) {
                diag?.let { DiagnosticStrip(it) }
            }
        }
    }

    /**
     * 시트(창 내부 확장): 후보 선택 / 수동 검색. 한 번에 하나.
     * @param sideFlyout 가로 측면 flyout 이면 세로 높이가 잘리지 않게 시트 최대 높이를 화면에 맞춰 clamp.
     */
    @androidx.compose.runtime.Composable
    private fun SheetContent(sheet: SheetState?, sideFlyout: Boolean) {
        // 측면 flyout 이면 세로 잘림 방지: 시트 리스트 최대 높이를 화면 높이의 대략 절반~7할로 제한.
        val maxH = if (sideFlyout) {
            val metrics = context.resources.displayMetrics
            val density = metrics.density
            val cap = (metrics.heightPixels * 0.70f / density).dp
            cap
        } else Dp.Unspecified
        when (val s = sheet) {
            is SheetState.Candidates -> CandidateSheet(
                choices = remember(s.root) { candidateProvider(s.root) },
                onPick = { key ->
                    onChooseCandidate(s.slot, s.root, key)
                    openSheet.value = null
                },
                onDismiss = { openSheet.value = null },
                maxSheetHeight = if (maxH == Dp.Unspecified) 280.dp else maxH,
            )
            is SheetState.Search -> SearchSheet(
                query = s.query,
                onQueryChange = { openSheet.value = SheetState.Search(s.slot, it) },
                results = searchProvider(s.query),
                onPick = { key ->
                    onPinSlot(s.slot, key)
                    openSheet.value = null
                },
                onDismiss = { openSheet.value = null },
                maxSheetHeight = if (maxH == Dp.Unspecified) 240.dp else maxH,
            )
            null -> Unit
        }
    }

    /** 열려 있는 시트 상태. */
    private sealed interface SheetState {
        val slot: Int
        data class Candidates(override val slot: Int, val root: String) : SheetState
        data class Search(override val slot: Int, val query: String) : SheetState
    }
}

/**
 * 슬롯 메가 선택 결정 로직(순수 JVM — Android 의존성 없음, 유닛 테스트 가능).
 * OverlayRenderer.updateSlot 에서 카드 교체 시 직전 포켓몬의 메가 선택이 새 포켓몬에 누수되지 않게 한다.
 */
internal object MegaSelection {
    /**
     * 카드 갱신 시 메가 선택 인덱스(-1=base, 0/1=megaForms).
     *  - 최초 표시(prevKey==null) → base(-1).
     *  - 같은 포켓몬 유지(prevKey==newKey) → 유저가 고른 선택 유지(currentSel).
     *  - 다른 포켓몬으로 교체(prevKey!=newKey) → base(-1)로 초기화(직전 메가 선택 누수 방지).
     */
    fun resolveOnUpdate(prevKey: String?, newKey: String, currentSel: Int?): Int = when {
        prevKey == null -> -1
        prevKey != newKey -> -1
        else -> currentSel ?: -1
    }
}

/** 슬롯 UI 메타(오버레이가 보관하는 형태 — 파이프라인 SlotMeta 와 대응). */
data class SlotUiMeta(
    val root: String? = null,
    val hasMoreCandidates: Boolean = false,
    val pinned: Boolean = false,
)

/** 후보 선택 시트에 표시할 후보 한 종. */
data class CandidateChoice(
    val key: String,
    val name: String,
    val typeChips: List<OverlayCardData.TypeChip>,
    /** 사용률(%) — 최상위 추천 배지 표시용. null 이면 미상. */
    val usagePct: Double?,
    /** 사용률 최상위(=추천 기본 선택)인지. */
    val recommended: Boolean,
)

/** 수동 검색 시트 결과 한 종. */
data class SearchChoice(val key: String, val name: String)

/** 카드 표시 단계(3단계 점진 공개). */
enum class CardStage { CHIP, CARD, EXPANDED }

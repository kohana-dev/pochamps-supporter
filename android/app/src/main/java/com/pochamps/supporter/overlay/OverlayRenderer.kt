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
    /**
     * [P24] 상호작용 토글 핸들 창의 위치 저장소(메인 창과 별도로 저장 → 각자 위치 유지).
     * 서비스가 `PrefsOverlayPositionStore(ctx, "handle_")` 를 주입. 기본은 인메모리(테스트/데모).
     */
    private val handlePositionStore: OverlayPositionStore = object : OverlayPositionStore {
        private var p: OverlayPosition? = null
        override fun load() = p
        override fun save(position: OverlayPosition) { p = position }
    },
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
    /**
     * [P25] 상호작용 모드 자동복귀 지연(ms). 0 이하면 자동복귀 안 함(설정에서 끔).
     * 서비스가 설정([AppSettings.autoRevertEnabled])에 따라 [InteractionMode.DEFAULT_TIMEOUT_MS]
     * 또는 [InteractionMode.TIMEOUT_DISABLED] 를 주입. 기본은 12초(안전장치).
     */
    private val autoRevertMs: Long = InteractionMode.DEFAULT_TIMEOUT_MS,
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
     * [P30] 모서리 드래그 리사이즈로 카드 스케일이 바뀔 때 호출(연속값). 서비스가
     * [com.pochamps.supporter.data.AppSettings.overlayScaleContinuous] 로 영속 저장한다. 기본 no-op.
     */
    onScaleChanged: (Float) -> Unit = {},
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

    /**
     * [P24] 상호작용 토글 핸들 창(별도 창). 메인 창이 기본 `FLAG_NOT_TOUCHABLE`(통과)라
     * 유일하게 상시 터치를 받는 아주 작은 창. 탭하면 상호작용 모드를 토글한다.
     */
    private var handleView: ComposeView? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var handleAdded = false

    /**
     * [P35 리포트3] 휴지통 드롭존 창(전체화면·비터치·투명). 핸들 드래그 중에만 잠깐 뜬다.
     * 메신저 챗헤드 패턴 — 드래그 중인 핸들을 여기로 끌어다 놓으면(드롭) 앱을 완전 종료한다.
     * 비터치(FLAG_NOT_TOUCHABLE)라 드래그 제스처는 계속 핸들 창이 받는다(이 창은 순수 시각 표시).
     */
    private var trashView: ComposeView? = null
    private var trashParams: WindowManager.LayoutParams? = null
    private var trashAdded = false

    /** [P35] 드래그 중 핸들 중심이 드롭존에 겹쳤는지(하이라이트/드롭 종료 판정). */
    private val trashHovering = mutableStateOf(false)

    /** [P35] 휴지통 드롭존이 표시 중인지(핸들 드래그 중). */
    private val trashVisible = mutableStateOf(false)

    /**
     * [P24] 터치 모드 상태 기계(순수 로직 [InteractionMode]).
     *  - interactive=false(기본): 메인 창 `FLAG_NOT_TOUCHABLE` → 모든 터치 게임 통과.
     *  - interactive=true: 핸들 탭으로 진입. 메인 창 touchable → 카드/시트 조작 가능.
     *    [InteractionMode.timeoutMs] 무조작이면 자동 통과 복귀.
     * Compose 가 관찰하도록 mutableState 로 감싼다(핸들 잠금 아이콘/타이머 반영).
     */
    private val interactionMode = mutableStateOf(InteractionMode(timeoutMs = autoRevertMs))

    /**
     * [P35] 마지막 사용자 조작 시각(uptimeMs). 워치독의 하드 타임아웃 기준.
     * 핸들 탭/드래그, 카드 조작 등 어떤 상호작용에서든 [markActivity] 로 갱신한다.
     * 자동복귀 타이머([InteractionMode.lastTouchMs])와 별도 — 워치독은 설정(자동복귀 on/off)과
     * 무관한 최종 안전망이라 자체 시각을 둔다.
     */
    @Volatile private var lastActivityMs: Long = 0L

    /**
     * [P35] 자동복귀(auto-revert) 타이머 — **핸들 창 컴포지션과 무관한** Handler 루프.
     *
     * 기존 버그: 자동복귀 타이머가 `HandleRoot` 의 `LaunchedEffect` 안에 있어, 핸들 창 컴포지션이
     * 멈추면(회전/최소화/시스템 사정) 타이머가 사라져 `interactive` 가 고착 → 터치 영구 차단.
     * 이제 타이머를 렌더러 수명에 붙은 Handler 루프로 옮겨, 컴포지션 생존과 무관하게 항상 평가한다.
     */
    private var autoRevertRunning = false
    private val autoRevertTick = object : Runnable {
        override fun run() {
            // 상호작용 모드일 때만 자동복귀를 평가한다. 통과 모드면 루프를 멈춘다(재진입 시 재기동).
            val cur = interactionMode.value
            if (!cur.interactive) {
                autoRevertRunning = false
                return
            }
            val next = cur.evaluate(now())
            if (next !== cur) setInteraction(next)
            // 여전히 상호작용이면 계속 폴링.
            if (interactionMode.value.interactive) {
                mainHandler.postDelayed(this, AUTO_REVERT_POLL_MS)
            } else {
                autoRevertRunning = false
            }
        }
    }

    /** [P35] 자동복귀 폴링 루프 기동(상호작용 진입 시). 이미 돌고 있으면 no-op. */
    private fun ensureAutoRevertRunning() {
        if (autoRevertRunning) return
        if (!interactionMode.value.interactive) return
        autoRevertRunning = true
        mainHandler.postDelayed(autoRevertTick, AUTO_REVERT_POLL_MS)
    }

    /** [P35] 사용자 조작 시각 갱신(워치독 하드 타임아웃 기준). 모든 상호작용 경로에서 호출. */
    private fun markActivity() {
        lastActivityMs = now()
    }

    /**
     * [P25] 마지막으로 반영한 화면 크기(px). 회전/구성 변경 재보정([onScreenConfigChanged]) 시
     * 이전 화면 대비 비율로 위치를 옮기는 기준. 최초 [showHandle] 시점에 세팅된다. 0=미설정.
     */
    private var lastScreenW = 0
    private var lastScreenH = 0

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

    /**
     * [P35 리포트2] "단일 앱 공유" 감지 안내 카드 표시 여부(세션당 1회). true 면 안내 카드 표시.
     * 전체 화면 공유로 다시 시작하도록 유도(재시작 진입점 = P7 재동의 재사용, [onRestart]).
     */
    private val showSingleAppHint = mutableStateOf(false)
    private var singleAppHintShown = false

    /** 형식 토글 콜백(P20). 서비스가 주입. */
    private var onSelectFormat: (com.pochamps.supporter.data.BattleFormat) -> Unit = {}

    /** 보정 진입 콜백(P21). 서비스가 주입(ACTION_CALIBRATE). */
    private var onCalibrate: () -> Unit = {}

    /** 진단 ON/OFF 토글 콜백(P21). 서비스가 주입(설정 영속 + 스트립 반영). */
    private var onToggleDiag: (Boolean) -> Unit = {}

    /** [P30] 리사이즈 스케일 변경 콜백. 서비스가 주입(overlayScaleContinuous 영속). */
    private var onScaleChanged: (Float) -> Unit = {}

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
        this.onScaleChanged = onScaleChanged
        scale.value = OverlayScale.clampCont(initialScale) // P30: 연속값 보존(칩/드래그 공용)
        battleFormat.value = initialFormat
        minimized.value = minimizeStore.load().minimized
    }

    /** 최소화 ↔ 복원 토글(P21). 상태를 영속 저장 후 다음 렌더에 반영. */
    private fun toggleMinimized() {
        val next = MinimizeState(minimized.value).toggled()
        minimized.value = next.minimized
        minimizeStore.save(next)
        // [P35] 최소화 시 열린 시트를 닫고 터치 플래그를 통과로 강제 리셋한다.
        //  기존 버그: 시트(검색) 열린 채 최소화하면 OverlayRoot 가 조기 반환(if isMinimized return)해
        //  focusable 복원 LaunchedEffect 에 도달하지 못하고 focusable 가 고착 → touchable 영구 true.
        //  최소화는 "메인 창 완전 비움"이므로 시트/포커스/상호작용을 모두 통과 상태로 되돌린다.
        if (next.minimized) {
            openSheet.value = null
            resetToPassthrough("minimize")
        }
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
        // [P30] 스냅 대신 연속 클램프 — 설정 칩(스냅된 특정값)도, 모서리 드래그(연속값)도 그대로 반영.
        scale.value = OverlayScale.clampCont(newScale)
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
        showHandle()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * [P24] 상호작용 토글 핸들 창을 띄운다(메인 창과 별도). 아주 작은 상시 touchable 창이라
     * 이 창만 게임 터치를 가져가고, 메인 창은 기본 통과다. 위치는 [handlePositionStore] 에 저장.
     */
    private fun showHandle() {
        if (handleAdded) return
        val lp = buildHandleParams()
        handleParams = lp
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayRenderer)
            setViewTreeSavedStateRegistryOwner(this@OverlayRenderer)
            setContent { HandleRoot() }
        }
        handleView = view
        windowManager.addView(view, lp)
        handleAdded = true
    }

    private fun buildHandleParams(): WindowManager.LayoutParams {
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
            // 핸들은 상시 touchable(FLAG_NOT_TOUCHABLE 없음). 키 포커스는 안 뺏게 NOT_FOCUSABLE 유지.
            // [P25] FLAG_LAYOUT_NO_LIMITS 를 빼서 창이 화면(안전영역) 밖으로 나가지 못하게 한다 —
            //   저장 좌표가 조금 어긋나도 시스템이 화면 안으로 붙여, 몰입형 전체화면에서도 안 잘린다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val m = context.resources.displayMetrics
            // [P25] 저장 위치가 있으면 현재 화면 안으로 clamp, 없으면 안전 기본값(우측 세로 중앙).
            val approx = handleApproxSizePx()
            val saved = handlePositionStore.load()
                ?.clampedTo(m.widthPixels, m.heightPixels, approx, approx)
                ?: defaultHandlePosition()
            x = saved.x
            y = saved.y
            // 이후 회전 재보정의 기준 화면 크기 기록.
            lastScreenW = m.widthPixels
            lastScreenH = m.heightPixels
        }
    }

    /** [P25] 핸들 창의 대략 크기(px). 실제 측정 전(창 추가 시점) clamp/기본값 계산에 쓴다. */
    private fun handleApproxSizePx(): Int =
        (HANDLE_APPROX_DP * context.resources.displayMetrics.density).toInt()

    /** [P25] 가장자리 안전 여백(px). 몰입형/곡면 화면 대비 가장자리에서 약간 안으로. */
    private fun handleEdgeInsetPx(): Int =
        (HANDLE_EDGE_INSET_DP * context.resources.displayMetrics.density).toInt()

    /** [P25] 핸들 기본 위치: 화면 우측 세로 중앙, 가장자리에서 약간 안쪽(가로/세로 모두 화면 안·손 닿기 쉬움). */
    private fun defaultHandlePosition(): OverlayPosition {
        val m = context.resources.displayMetrics
        val size = handleApproxSizePx()
        return OverlayPosition.handleDefault(
            screenWidth = m.widthPixels,
            screenHeight = m.heightPixels,
            handleWidth = size,
            handleHeight = size,
            edgeInset = handleEdgeInsetPx(),
        )
    }

    private fun onHandleDrag(dx: Float, dy: Float) {
        val lp = handleParams ?: return
        val view = handleView ?: return
        val m = context.resources.displayMetrics
        val hw = view.width.takeIf { it > 0 } ?: handleApproxSizePx()
        val hh = view.height.takeIf { it > 0 } ?: handleApproxSizePx()
        val newPos = OverlayPosition(lp.x + dx.toInt(), lp.y + dy.toInt())
            .clampedTo(
                screenWidth = m.widthPixels,
                screenHeight = m.heightPixels,
                cardWidth = hw,
                cardHeight = hh,
            )
        lp.x = newPos.x
        lp.y = newPos.y
        windowManager.updateViewLayout(view, lp)
        // [P35 리포트3] 드래그 중 휴지통 겹침 판정 → 하이라이트 갱신.
        updateTrashHover(newPos.x + hw / 2f, newPos.y + hh / 2f)
    }

    // --- [P35 리포트3] 휴지통 드롭존(핸들 드래그 종료 = 종료) ---

    /** 드래그 시작 시 휴지통 드롭존 창을 띄운다(전체화면·비터치·시각 표시 전용). */
    private fun onHandleDragStart() {
        showTrash()
    }

    /** 드래그 델타마다 핸들 중심 vs 드롭존 겹침을 판정해 하이라이트 상태를 갱신한다. */
    private fun updateTrashHover(handleCenterX: Float, handleCenterY: Float) {
        if (!trashAdded) return
        val m = context.resources.displayMetrics
        val (zx, zy) = TrashDropZone.center(m.widthPixels, m.heightPixels, trashBottomMarginPx())
        trashHovering.value = TrashDropZone.isOver(
            handleCenterX = handleCenterX,
            handleCenterY = handleCenterY,
            zoneCenterX = zx,
            zoneCenterY = zy,
            hitRadiusPx = trashHitRadiusPx(),
        )
    }

    /**
     * 드래그 종료: 드롭존에 겹쳐 있으면(hovering) **완전 종료**([onExit] = ACTION_STOP 경로),
     * 아니면 평소처럼 위치를 저장한다. 어느 쪽이든 휴지통 창을 걷는다.
     */
    private fun onHandleDragEnd() {
        val dropExit = trashHovering.value && trashAdded
        hideTrash()
        if (dropExit) {
            android.util.Log.i("OverlayRenderer", "휴지통 드롭 → 완전 종료(onExit)")
            onExit()
        } else {
            persistHandlePosition()
        }
    }

    private fun showTrash() {
        trashVisible.value = true
        trashHovering.value = false
        if (trashAdded) return
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 비터치·비포커스: 드래그 제스처는 계속 핸들 창이 받고, 이 창은 순수 시각 표시.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayRenderer)
            setViewTreeSavedStateRegistryOwner(this@OverlayRenderer)
            setContent { TrashRoot() }
        }
        trashParams = lp
        trashView = view
        runCatching { windowManager.addView(view, lp) }
        trashAdded = true
    }

    private fun hideTrash() {
        trashVisible.value = false
        trashHovering.value = false
        trashView?.let { runCatching { windowManager.removeView(it) } }
        trashView = null
        trashParams = null
        trashAdded = false
    }

    /** 드롭존 중심의 화면 하단 여백(px). */
    private fun trashBottomMarginPx(): Int =
        (TRASH_BOTTOM_MARGIN_DP * context.resources.displayMetrics.density).toInt()

    /** 드롭존 히트 반경(px) — 손가락 오차에 관대하게 넉넉히. */
    private fun trashHitRadiusPx(): Float =
        TRASH_HIT_RADIUS_DP * context.resources.displayMetrics.density

    /**
     * [P25] 화면 구성 변경(회전/크기 변화) 재보정. 서비스가 `onConfigurationChanged` 에서 호출한다.
     *
     * 세로↔가로 전환 시 저장/현재 핸들 위치가 새 화면을 벗어나 "게임(가로)에서 핸들이 안 보이고
     * 못 누르는" 문제를 막는다. 이전 화면 크기 대비 비율로 위치를 옮기고, 그래도 벗어나면
     * 안전 기본값(우측 세로 중앙)으로 되돌린 뒤 새 좌표를 저장한다. 메인 정보 창도 함께 화면 안으로 clamp.
     */
    fun onScreenConfigChanged() {
        mainHandler.post {
            val m = context.resources.displayMetrics
            val newW = m.widthPixels
            val newH = m.heightPixels
            // --- 핸들 창 재보정 ---
            val hlp = handleParams
            val hview = handleView
            if (hlp != null && hview != null) {
                val hw = hview.width.takeIf { it > 0 } ?: handleApproxSizePx()
                val hh = hview.height.takeIf { it > 0 } ?: handleApproxSizePx()
                val remapped = OverlayPosition.remapForScreen(
                    current = OverlayPosition(hlp.x, hlp.y),
                    oldW = lastScreenW,
                    oldH = lastScreenH,
                    newW = newW,
                    newH = newH,
                    handleWidth = hw,
                    handleHeight = hh,
                    edgeInset = handleEdgeInsetPx(),
                )
                hlp.x = remapped.x
                hlp.y = remapped.y
                runCatching { windowManager.updateViewLayout(hview, hlp) }
                handlePositionStore.save(remapped)
            }
            // --- 메인 정보 창도 화면 안으로 clamp(회전 시 화면 밖 방지) ---
            clampWindowIntoScreen()
            // 다음 재보정 기준 갱신.
            lastScreenW = newW
            lastScreenH = newH
        }
    }

    private fun persistHandlePosition() {
        val lp = handleParams ?: return
        handlePositionStore.save(OverlayPosition(lp.x, lp.y))
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
        // 기본 단계 = CARD(타입+특성+주요기술). 터치는 통과되므로(P24) 카드가 커도 게임을 안 막고,
        // 인식/수동지정 즉시 유용한 정보가 보인다. 더 줄이려면 탭해서 CHIP, 더 보려면 EXPANDED.
        if (stageBySlot[slot] == null) stageBySlot[slot] = CardStage.CARD
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
        // [P35] 캡처 중단 시 터치 플래그를 통과로 강제 리셋(고착 방지 — 중단 카드만 남아도 게임 터치 통과).
        resetToPassthrough("captureStopped")
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

    /**
     * [P35 리포트2] "단일 앱 공유" 감지 안내 카드 1회 표시. 캡처 콜백이 부분 캡처를 감지하면 호출한다.
     * 전체 화면 공유로 다시 시작해야 인식됨을 알리고, 재시작 진입점을 제공한다(세션당 1회).
     */
    fun showSingleAppHintOnce() {
        if (singleAppHintShown) return
        if (captureStopped.value) return
        singleAppHintShown = true
        showSingleAppHint.value = true
    }

    /** 단일 앱 안내 닫기(유저 탭). */
    fun dismissSingleAppHint() {
        showSingleAppHint.value = false
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
        // P24: 정상(HEALTHY)이면 안내 카드 없음(null) — 인식 성공 평상시엔 카드만 보인다.
        captureHealth.value =
            if (com.pochamps.supporter.capture.CaptureHealth.shouldShowCard(h)) h else null
    }

    fun destroy() {
        // [P35] 자동복귀/워치독 Handler 콜백 정리(누수·좀비 타이머 방지).
        mainHandler.removeCallbacks(autoRevertTick)
        autoRevertRunning = false
        // [P35 리포트3] 휴지통 드롭존 창 정리(드래그 중 종료돼도 창이 남지 않게).
        hideTrash()
        if (!added && !handleAdded) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            return
        }
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        composeView?.let { runCatching { windowManager.removeView(it) } }
        composeView = null
        layoutParams = null
        added = false
        // P24: 핸들 창도 함께 제거.
        handleView?.let { runCatching { windowManager.removeView(it) } }
        handleView = null
        handleParams = null
        handleAdded = false
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
            // P24: 기본 통과(NOT_TOUCHABLE) — 상호작용 모드가 아니면 카드/컨트롤도 터치를 안 받는다.
            baseFlags(focusable = false, touchable = interactionMode.value.interactive),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = positionStore.load() ?: defaultPosition()
            x = saved.x
            y = saved.y
        }
    }

    /**
     * 메인 창 플래그 조립.
     *  - `FLAG_LAYOUT_NO_LIMITS`: 소형 창 전략(창=카드)에서 화면 밖 배치 허용 위해 항상 유지.
     *  - `FLAG_NOT_FOCUSABLE`: 기본(게임 키 포커스 보존). [focusable] true 면 제거(검색 IME 입력용).
     *  - `FLAG_NOT_TOUCHABLE`(P24 근본 수정): [touchable] false(기본)면 추가 →
     *    **보이는 카드/컨트롤 영역 전체가 게임 터치를 통과시킨다**(게임 100% 조작 가능).
     *    상호작용 모드([interactionMode].interactive)일 때만 이 플래그를 빼서 조작을 허용한다.
     *
     * ## 2창 모델 선택 근거(설계 대안 대비)
     * "컨트롤 바만 별도 touchable 소형 창" 대안은 카드 상호작용(탭 순환·메가·후보 선택·검색 시트·
     * 종료 그립)을 전부 컨트롤 바 버튼으로 옮겨야 해 기존 카드 UX/코드(P4·P16·P18·P20·P23)를
     * 대규모로 재작성해야 한다. 반면 2창 모델은 메인 창의 플래그 하나만 토글하므로
     * 기존 카드 컴포저블/콜백을 **그대로 보존**하면서 "평소 게임 터치 100% 통과"를 달성한다.
     * 유일한 상시 터치 영역은 아주 작은 핸들 창뿐 → 게임을 거의 안 가린다.
     */
    private fun baseFlags(focusable: Boolean, touchable: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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

    /**
     * [P30] 모서리 그립 드래그 → 카드 스케일 연속 조절. 기준 픽셀은 화면 짧은 변(회전 무관 일관 감도).
     * 순수 로직([OverlayScale.applyDragDelta])으로 델타→스케일 클램프를 계산하고 즉시 반영한다.
     */
    private fun onResizeDrag(dx: Float, dy: Float) {
        touchInteraction() // 조작 → 상호작용 모드 자동 복귀 타이머 리셋.
        val m = context.resources.displayMetrics
        val refPx = minOf(m.widthPixels, m.heightPixels).toFloat()
        scale.value = OverlayScale.applyDragDelta(scale.value, dx, dy, refPx)
    }

    /** [P30] 리사이즈 드래그 종료 → 스케일 연속 영속 저장(서비스 콜백). */
    private fun persistResize() {
        onScaleChanged(scale.value)
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
        focusable = wantFocus
        markActivity() // P35: 포커스 전환도 조작 — 워치독 기준 시각 갱신.
        applyMainFlags()
    }

    /**
     * [P24] 메인 창 플래그를 현재 [focusable]/[interactionMode] 상태로 재계산해 반영한다.
     * 검색 시트가 열리면(focusable) 자동으로 touchable 로 취급해 IME/필드 탭이 되게 한다.
     */
    private fun applyMainFlags() {
        val lp = layoutParams ?: return
        val view = composeView ?: return
        // 검색 입력 중(focusable)이면 반드시 터치도 받아야 하므로 touchable 강제.
        val touchable = interactionMode.value.interactive || focusable
        lp.flags = baseFlags(focusable = focusable, touchable = touchable)
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    /**
     * [P24] 상호작용 모드 전이 반영(핸들 탭/자동 복귀/유저 조작 후).
     * 상태 기계 [InteractionMode] 결과를 저장하고, 변화가 있으면 메인 창 플래그를 즉시 갱신한다.
     * [P35] 상호작용 진입 시 자동복귀 Handler 루프를 기동한다(컴포지션 무관).
     */
    private fun setInteraction(next: InteractionMode) {
        val prev = interactionMode.value
        interactionMode.value = next
        if (prev.interactive != next.interactive) applyMainFlags()
        if (next.interactive) ensureAutoRevertRunning()
    }

    /** 핸들 탭 → 통과 ↔ 상호작용 토글. */
    private fun toggleInteraction() {
        markActivity() // P35: 조작 시각 갱신(워치독 기준).
        setInteraction(interactionMode.value.toggle(now()))
    }

    /**
     * [P35] **터치 플래그 단일 리셋 경로** — 무조건 통과(비-touchable)+비포커스로 강제 복원한다.
     *
     * 리포트 1(치명 버그)의 근본 대응: interactive/focusable 플래그가 어떤 경로로 고착되든
     * (핸들 컴포지션 정지, 시트 열린 채 최소화, 컴포지션 이탈, 워치독 감지) 이 한 함수가
     * 상태를 초기화하고 창 플래그를 즉시 통과로 되돌린다. 게임 터치가 다시 100% 통과된다.
     *
     * @param reason 로그용 원인 식별자(어느 경로에서 리셋됐는지 추적).
     */
    private fun resetToPassthrough(reason: String) {
        var changed = false
        if (interactionMode.value.interactive) {
            interactionMode.value = interactionMode.value.copy(interactive = false, lastTouchMs = 0L)
            changed = true
        }
        if (focusable) {
            focusable = false
            changed = true
        }
        // 플래그를 항상 통과+비포커스로 강제 반영(고착 방지 — 상태가 이미 false여도 창 플래그가
        // 어긋나 있을 수 있으므로 무조건 재적용).
        applyMainFlags()
        autoRevertRunning = false
        if (changed) {
            android.util.Log.i(
                "OverlayRenderer",
                "resetToPassthrough($reason) — 터치 통과+비포커스 강제 복원",
            )
        }
    }

    /**
     * [P35] 워치독(이중 안전망). 서비스가 주기적으로([PassthroughWatchdog.POLL_MS]) 호출한다.
     *
     * 메인 창이 터치를 받는 상태(interactive 또는 focusable)인데 마지막 조작 후 하드 타임아웃을
     * 초과했으면(=어떤 미지의 경로로 고착) 강제 통과 리셋하고 로그를 남긴다. 검색 시트가 실제
     * 열려 있는 동안은 IME 입력 중이므로 리셋을 보류한다(입력 방해 방지).
     *
     * 자동복귀 설정(on/off)과 무관하게 항상 동작한다 — 최종 안전망이기 때문이다.
     */
    fun runWatchdog(nowMs: Long = now()) {
        val sheetOpen = openSheet.value is SheetState.Search
        val stuck = PassthroughWatchdog.shouldForceReset(
            interactive = interactionMode.value.interactive,
            focusable = focusable,
            sheetOpen = sheetOpen,
            lastActivityMs = lastActivityMs,
            nowMs = nowMs,
            hardTimeoutMs = PassthroughWatchdog.DEFAULT_HARD_TIMEOUT_MS,
        )
        if (stuck) {
            android.util.Log.w(
                "OverlayRenderer",
                "워치독: 터치 고착 감지(interactive=${interactionMode.value.interactive}, " +
                    "focusable=$focusable, idleMs=${nowMs - lastActivityMs}) — 강제 통과 복귀",
            )
            resetToPassthrough("watchdog")
        }
    }

    /**
     * 상호작용 모드 중 카드/시트 조작 시 자동 복귀 타이머 리셋(무조작 시간 재기).
     * 통과 모드면 no-op(상호작용 아닐 때 이 창은 터치를 안 받으므로 호출도 안 됨).
     */
    private fun touchInteraction() {
        markActivity() // P35: 조작 시각 갱신(워치독 기준).
        val next = interactionMode.value.touched(now())
        if (next !== interactionMode.value) interactionMode.value = next
    }

    // --- 단계 전환 로직(탭 순환: 칩→카드→확장→칩) ---

    private fun cycleStage(slot: Int) {
        touchInteraction() // P24: 조작 → 상호작용 모드 자동 복귀 타이머 리셋.
        val next = when (stageBySlot[slot] ?: CardStage.CHIP) {
            CardStage.CHIP -> CardStage.CARD
            CardStage.CARD -> CardStage.EXPANDED
            CardStage.EXPANDED -> CardStage.CHIP
        }
        stageBySlot[slot] = next
        if (next == CardStage.EXPANDED) {
            expandedAtBySlot[slot] = now()
            // [P30] 한 번에 한 카드만 EXPANDED: 다른 슬롯이 EXPANDED 면 CARD 로 자동 축소
            //  (더블배틀에서 둘 다 세로로 길어져 화면이 잘리는 것 방지).
            for (other in stageBySlot.keys.toList()) {
                if (other != slot && stageBySlot[other] == CardStage.EXPANDED) {
                    stageBySlot[other] = CardStage.CARD
                    expandedAtBySlot.remove(other)
                }
            }
        } else {
            expandedAtBySlot.remove(slot)
        }
    }

    /** 확장 패널에서 유저가 조작할 때 자동 축소 타이머를 리셋(무조작 시간 재기). */
    private fun touchExpanded(slot: Int) {
        touchInteraction() // P24: 상호작용 모드 자동 복귀 타이머도 함께 리셋.
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

        // P24: 최소화 상태 — 메인 창에는 아무것도 렌더하지 않는다(완전 비움).
        // 최소화 복원은 별도 상시 touchable 핸들 창(길게 누르기)이 담당한다. 메인 창은 기본 통과라
        // 메인 창 안에 복원 핸들을 두면 탭이 안 먹기 때문이다(과거 MinimizedHandle 은 제거).
        if (isMinimized) return

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
        // [P35] focusable 복원을 컴포지션 이탈에도 보장한다.
        //  - LaunchedEffect(searchOpen): 시트 열림/닫힘에 따라 명시적으로 setFocusable 호출.
        //  - DisposableEffect(Unit).onDispose: 이 컴포저블이 어떤 이유로든 컴포지션을 떠날 때
        //    (최소화 조기반환·회전·프로세스 재구성) 반드시 setFocusable(false) 를 실행 → focusable 고착 방지.
        //  이렇게 이중으로 두어, 시트 열린 채 이탈해도 touchable = interactive || focusable 이 고착되지 않는다.
        val searchOpen = sheet is SheetState.Search
        androidx.compose.runtime.LaunchedEffect(searchOpen) {
            setFocusable(searchOpen)
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { setFocusable(false) }
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
        // [P30] 확장 2컬럼 패널 안전망(최대 높이 ~85%) 계산용 화면 높이(dp).
        val screenHeightDp = run {
            val m = context.resources.displayMetrics
            if (m.density > 0f) m.heightPixels / m.density else 0f
        }

        CompositionLocalProvider(
            LocalOverlayScale provides scaleValue,
            LocalOverlayScreenHeightDp provides screenHeightDp,
        ) {
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

    /**
     * [P24] 상호작용 토글 핸들의 루트 컴포저블(별도 창). 아주 작은 상시 touchable 버튼.
     *  - 탭: 통과 ↔ 상호작용 모드 토글([toggleInteraction]).
     *  - 드래그: 위치 이동(저장).
     *  - 잠금 아이콘: 🔒(통과중=게임 터치 통과) / ✋(조작중=카드 조작 가능).
     *  - 상호작용 모드 중 무조작 [InteractionMode.timeoutMs] 경과 시 자동 통과 복귀(주기 평가).
     */
    @androidx.compose.runtime.Composable
    private fun HandleRoot() {
        val mode by interactionMode
        val scaleValue by scale

        // [P35] 자동복귀 타이머는 더 이상 이 컴포지션에 두지 않는다(핸들 컴포지션 정지 시 타이머가
        //  사라져 interactive 고착 → 터치 영구 차단하던 버그). 타이머는 렌더러 수명에 붙은 Handler
        //  루프([autoRevertTick])로 옮겨 컴포지션 생존과 무관하게 항상 평가한다. 상호작용 진입은
        //  [setInteraction]/[ensureAutoRevertRunning] 이 루프를 기동한다.

        val handleDrag = Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = {
                    markActivity()
                    onHandleDragStart() // P35 리포트3: 휴지통 드롭존 표시.
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    markActivity() // P35: 핸들 드래그도 조작 — 워치독 기준 갱신.
                    onHandleDrag(dragAmount.x, dragAmount.y)
                },
                // P35 리포트3: 드롭존 위면 완전 종료, 아니면 위치 저장. 어느 쪽이든 드롭존 걷기.
                onDragEnd = { onHandleDragEnd() },
                onDragCancel = { hideTrash(); persistHandlePosition() },
            )
        }

        val isMinimized by minimized
        CompositionLocalProvider(LocalOverlayScale provides scaleValue) {
            InteractionHandle(
                interactive = mode.interactive,
                minimized = isMinimized,
                dragModifier = handleDrag,
                // 최소화 상태(👁 "복원")에서는 탭이 곧 복원이어야 한다(라벨과 일치, 실기기 버그 수정).
                //  - 최소화중: 탭 → 복원(toggleMinimized).
                //  - 평상시: 탭 → 통과↔조작 전환.
                onToggle = { if (minimized.value) toggleMinimized() else toggleInteraction() },
                // 길게 누르기 → 카드/컨트롤 전체 최소화 ↔ 복원(P21 통합, 어느 상태서든 동작).
                onLongPress = { toggleMinimized() },
            )
        }
    }

    /**
     * [P35 리포트3] 휴지통 드롭존 창의 루트 컴포저블(전체화면·비터치). 핸들 드래그 중에만 뜬다.
     * 하이라이트 상태([trashHovering])는 렌더러가 드래그마다 갱신한다.
     */
    @androidx.compose.runtime.Composable
    private fun TrashRoot() {
        val hovering by trashHovering
        val scaleValue by scale
        CompositionLocalProvider(LocalOverlayScale provides scaleValue) {
            TrashDropZoneOverlay(
                hovering = hovering,
                bottomMarginDp = TRASH_BOTTOM_MARGIN_DP,
            )
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
            if (captureOn) {
                ControlBar(
                    isDoubles = fmt == com.pochamps.supporter.data.BattleFormat.DOUBLES,
                    dragModifier = dragMod,
                    onMinimize = { touchInteraction(); toggleMinimized() },
                    onSelectFormat = { doubles ->
                        touchInteraction()
                        onSelectFormat(
                            if (doubles) com.pochamps.supporter.data.BattleFormat.DOUBLES
                            else com.pochamps.supporter.data.BattleFormat.SINGLES,
                        )
                    },
                    // 카드가 없어도 검색: ControlSearch 로 핀 대상 슬롯을 정해 검색 시트를 연다.
                    onSearch = {
                        touchInteraction()
                        val target = ControlSearch.targetSlot(fmt, slotOrder.toSet())
                        captureCardBounds()
                        openSheet.value = SheetState.Search(target, "")
                    },
                    onCalibrate = { touchInteraction(); onCalibrate() },
                )
            }
            // 장시간 미인식 안내 배너(1회). 인식 성공 시 자동으로 사라진다.
            if (hintVisible) {
                BattleNamesHintBanner(onDismiss = { dismissBattleNamesHint() })
            }
            // [P35 리포트2] 단일 앱 공유 감지 안내 카드(1회). 전체 화면 공유로 재시작 유도.
            val singleAppVisible by showSingleAppHint
            if (singleAppVisible) {
                SingleAppShareCard(
                    onRestart = { touchInteraction(); onRestart() },
                    onDismiss = { touchInteraction(); dismissSingleAppHint() },
                )
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
                    // [P32] 예상 팀원 칩 탭 → 그 포켓몬을 슬롯에 핀(기존 pinSlot 재사용).
                    // 대상 슬롯은 ControlSearch.targetSlot(빈 슬롯 우선, 없으면 슬롯0 덮어쓰기).
                    onPinTeammate = { key ->
                        touchInteraction()
                        val target = ControlSearch.targetSlot(battleFormat.value, slotOrder.toSet())
                        onPinSlot(target, key)
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
                    // [P30] 모서리 리사이즈 그립도 주 카드에만. 조작 모드에서만 창이 터치를 받으므로 통과 보존.
                    onResizeDrag = if (slot == primarySlot) ({ dx, dy -> onResizeDrag(dx, dy) }) else null,
                    onResizeEnd = if (slot == primarySlot) ({ persistResize() }) else null,
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

    private companion object {
        /** [P25] 핸들 창 대략 크기(dp). 실제 컴포저블 크기와 대략 일치(clamp/기본값 계산용). */
        const val HANDLE_APPROX_DP = 60

        /** [P25] 핸들 가장자리 안전 여백(dp). 몰입형/곡면 대비 가장자리에서 약간 안으로. */
        const val HANDLE_EDGE_INSET_DP = 8

        /** [P35] 자동복귀 Handler 폴링 주기(ms). 상호작용 모드에서 이 주기로 타임아웃을 평가. */
        const val AUTO_REVERT_POLL_MS = 500L

        /** [P35 리포트3] 휴지통 드롭존 중심의 화면 하단 여백(dp). */
        const val TRASH_BOTTOM_MARGIN_DP = 72

        /** [P35 리포트3] 휴지통 드롭존 히트 반경(dp) — 손가락 오차에 관대하게 넉넉히. */
        const val TRASH_HIT_RADIUS_DP = 88f
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

/**
 * [P30] "한 번에 한 카드만 EXPANDED" stage 전환 로직(순수 JVM — Android 의존성 없음, 유닛 테스트 가능).
 *
 * 한 슬롯을 EXPANDED 로 펼치면 다른 EXPANDED 슬롯은 CARD 로 자동 축소한다(더블배틀에서 둘 다 세로로
 * 길어져 화면 아래가 잘리는 것 방지). CHIP/CARD 슬롯은 건드리지 않는다.
 */
internal object ExpandExclusive {
    /**
     * [current] 상태에서 [slot] 을 [next] 로 바꾼 결과 stage 맵을 반환한다.
     * next==EXPANDED 이면 slot 을 제외한 다른 EXPANDED 슬롯을 CARD 로 낮춘다.
     */
    fun apply(
        current: Map<Int, CardStage>,
        slot: Int,
        next: CardStage,
    ): Map<Int, CardStage> {
        val result = current.toMutableMap()
        result[slot] = next
        if (next == CardStage.EXPANDED) {
            for ((other, stage) in current) {
                if (other != slot && stage == CardStage.EXPANDED) {
                    result[other] = CardStage.CARD
                }
            }
        }
        return result
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

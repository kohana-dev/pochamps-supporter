package com.pochamps.supporter.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    /** 후보 리스트 조회(root → 후보 카드 데이터). 시트가 표시할 후보 칩. */
    private val candidateProvider: (root: String) -> List<CandidateChoice> = { emptyList() },
    /** 이름 부분일치 검색(query → 결과). 수동 검색 시트. */
    private val searchProvider: (query: String) -> List<SearchChoice> = { emptyList() },
    /** 확장 패널 자동 축소 지연(ms). 0 이하면 자동 축소 안 함. */
    private val autoCollapseMs: Long = 8_000L,
    /** 캡처 중단 카드의 "재시작" 탭 콜백(서비스가 MainActivity 를 다시 연다). */
    onRestart: () -> Unit = {},
) : LifecycleOwner, SavedStateRegistryOwner {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

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

    /**
     * 장시간 미인식 시 "배틀명 표시 ON" 1회 안내 배너 노출 여부(DESIGN.md 5장 이름 미표시).
     * true 면 배너 표시. [dismissBattleNamesHint] 로 닫으면 세션 동안 다시 안 뜸.
     */
    private val showBattleNamesHint = mutableStateOf(false)
    private var battleNamesHintShown = false

    init {
        savedStateController.performRestore(null)
        this.onRestart = onRestart
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

        val hintVisible by showBattleNamesHint
        if (slotOrder.isEmpty() && !hintVisible) return

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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 장시간 미인식 안내 배너(1회). 인식 성공 시 자동으로 사라진다.
            if (hintVisible) {
                BattleNamesHintBanner(onDismiss = { dismissBattleNamesHint() })
            }
            for (slot in slotOrder.sorted()) {
                // 인식 실패 슬롯 → FailureCard(수동 검색 진입).
                if (slot in failureSlots && !cardsBySlot.containsKey(slot)) {
                    FailureCard(
                        dragModifier = if (slot == slotOrder.minOrNull()) dragMod else Modifier,
                        onOpenSearchSheet = { openSheet.value = SheetState.Search(slot, "") },
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
                    dragModifier = if (slot == slotOrder.minOrNull()) dragMod else Modifier,
                    onTapCycle = { cycleStage(slot) },
                    onInteract = { touchExpanded(slot) },
                    onSelectMega = { idx ->
                        megaSelBySlot[slot] = idx
                        touchExpanded(slot)
                    },
                    onOpenCandidateSheet = {
                        meta.root?.let { root ->
                            openSheet.value = SheetState.Candidates(slot, root)
                        }
                    },
                    onUnpin = { onUnpinSlot(slot); metaBySlot[slot] = meta.copy(pinned = false) },
                )
            }

            // 시트(창 내부 확장): 후보 선택 / 수동 검색. 한 번에 하나.
            when (val s = sheet) {
                is SheetState.Candidates -> CandidateSheet(
                    choices = remember(s.root) { candidateProvider(s.root) },
                    onPick = { key ->
                        onChooseCandidate(s.slot, s.root, key)
                        openSheet.value = null
                    },
                    onDismiss = { openSheet.value = null },
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
                )
                null -> Unit
            }
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

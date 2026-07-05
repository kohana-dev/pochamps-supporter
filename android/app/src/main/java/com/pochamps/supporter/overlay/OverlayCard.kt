package com.pochamps.supporter.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.pochamps.supporter.R

/**
 * [7] OverlayCard — 게임 위에 뜨는 오버레이 카드의 Compose UI.
 *
 * DESIGN.md 5장 3단계 점진 공개(탭 순환: 칩 → 카드 → 확장 → 칩):
 *  - CHIP: 이름 + 타입 칩.
 *  - CARD: + 특성 + 주요기술 4개(사용률%) + 메가 토글/후보 바꾸기.
 *  - EXPANDED: + 종족값(6스탯, 메가 증감) + 전체 기술(사용률순, 스크롤) + 방어 상성 + 도감번호.
 *
 * 인식 실패(수동 검색) 상태 카드는 [FailureCard] 로 별도 렌더한다.
 *
 * ## P16: 스케일 + 종료 진입점
 *  - 모든 dp/sp 를 [LocalOverlayScale] 배수로 곱한다(밀도 기반 — graphicsLayer 아님 → 창 bounds 가
 *    잘림 없이 따라감).
 *  - 그립 바를 **오래누르기(long-press)** 하면 앱/오버레이/캡처를 완전히 끄는 [onExit] 를 호출한다.
 */

private val CardBg = Color(0xE6_1A1A1A)
private val ChipTextColor = Color(0xFF_FFFFFF)
private val SubTextColor = Color(0xFF_D0D0D0)
private val AccentColor = Color(0xFF_8AB4F8)
private val MegaColor = Color(0xFF_7E57C2)
private val PosDelta = Color(0xFF_66BB6A)
private val NegDelta = Color(0xFF_EF5350)
private val ExitColor = Color(0xFF_E57373)
// P21: 컨트롤 바/최소화 핸들 배경(카드보다 조금 더 투명 — 게임을 덜 가리게).
private val ControlBarBg = Color(0xCC_1A1A1A)
// [P25] 토글 핸들 배경(불투명 — 화려한 게임 위에서도 잘 보이게). 조작중=강조 파랑, 통과중=짙은 회색.
private val HandleActiveBg = Color(0xFF_2C6FE0)
private val HandlePassiveBg = Color(0xF2_1A1A1A)
// P23: 보정(조준) 아이콘/라벨 색 — 게임 중 눈에 확 띄도록 밝은 노랑.
private val AimIconColor = Color(0xFF_FFD54F)

/**
 * 오버레이 카드 스케일(P16). 밀도 기반 배수 — 모든 dp/sp 를 이 값으로 곱한다(graphicsLayer 아님 →
 * 잘림 없이 창 bounds 가 따라감). [OverlayScale] 의 허용 단계 중 하나. 기본 1.0.
 */
val LocalOverlayScale = compositionLocalOf { 1.0f }

/**
 * [P30] 화면 높이(dp). 확장 2컬럼 패널의 안전망 최대 높이(≈85%)를 계산할 때 쓴다.
 * 0(기본)이면 높이를 모른다는 뜻 → 제한 없이 wrap-content(스크롤 없음).
 */
val LocalOverlayScreenHeightDp = compositionLocalOf { 0f }

/** 스케일 적용 dp. */
@Composable
private fun Dp.scaled(): Dp = this * LocalOverlayScale.current

/** 스케일 적용 sp. */
@Composable
private fun TextUnit.scaled(): TextUnit = this * LocalOverlayScale.current

@Composable
fun OverlayCard(
    data: OverlayCardData,
    stage: CardStage,
    meta: SlotUiMeta,
    megaForms: List<OverlayCardData.MegaForm>,
    megaSelection: Int,
    dragModifier: Modifier,
    /** 카드 본문 탭 → 단계 순환. */
    onTapCycle: () -> Unit,
    /** 확장 패널 내 임의 조작 → 자동 축소 타이머 리셋. */
    onInteract: () -> Unit = {},
    /** 메가 세그먼트 선택(-1=base, 0/1=megaForms). */
    onSelectMega: (Int) -> Unit = {},
    /** "바꾸기"(후보 선택 시트) 진입. */
    onOpenCandidateSheet: () -> Unit = {},
    /** 핀 해제. */
    onUnpin: () -> Unit = {},
    /**
     * 수동 지정(P18): 이 슬롯의 이름 검색 시트 열기. 정상/오인식 무관하게 올바른 포켓몬으로 교정.
     * 그립 바 우측의 🔍 아이콘. null 이면 미노출(하위호환).
     */
    onOpenSearch: (() -> Unit)? = null,
    /**
     * 강제 재인식(P18): 이 슬롯을 즉시 다시 읽는다(오인식 고착 탈출). 그립 바 우측의 ↻ 아이콘.
     * 핀 상태면 ↻ 는 핀을 풀고 재인식한다(파이프라인 forceRescan). null 이면 미노출(하위호환).
     */
    onForceRescan: (() -> Unit)? = null,
    /** 그립 바 오래누르기 → 앱/오버레이/캡처 완전 종료(P16). null 이면 종료 진입점 없음. */
    onExit: (() -> Unit)? = null,
    /**
     * [P30] 우하단 모서리 리사이즈 그립 드래그(px 이동량). 카드 크기를 연속 조절한다.
     * null 이면 그립 미노출(주 카드에만 붙인다). 조작 모드에서만 창이 터치를 받으므로 터치통과는 보존된다.
     */
    onResizeDrag: ((dragDx: Float, dragDy: Float) -> Unit)? = null,
    /** [P30] 리사이즈 드래그 종료 → 스케일 영속 저장. */
    onResizeEnd: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
      Column(
        modifier = Modifier
            .widthIn(min = 160.dp.scaled(), max = 300.dp.scaled())
            .background(CardBg, RoundedCornerShape(12.dp.scaled())),
    ) {
        // --- 상단 그립 바(드래그 핸들 + 이름 + 메가배지 + chevron) ---
        // 오래누르기(long-press) → 종료 진입점(P16). 짧은 탭은 단계 순환.
        val gripMod = if (onExit != null) {
            dragModifier.pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onExit() },
                    onTap = { onInteract(); onTapCycle() },
                )
            }
        } else dragModifier
        Row(
            modifier = gripMod.padding(horizontal = 12.dp.scaled(), vertical = 8.dp.scaled()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = data.name,
                color = ChipTextColor,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp.scaled(),
                modifier = Modifier.weight(1f),
            )
            if (meta.pinned) {
                PinBadge(onClick = onUnpin)
                Spacer(Modifier.width(6.dp.scaled()))
            }
            if (data.canMega) {
                Text(
                    stringResource(R.string.overlay_mega_badge),
                    color = Color.White, fontSize = 10.sp.scaled(), fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(MegaColor, RoundedCornerShape(4.dp.scaled()))
                        .padding(horizontal = 5.dp.scaled(), vertical = 1.dp.scaled()),
                )
                Spacer(Modifier.width(6.dp.scaled()))
            }
            // ↻ 강제 재인식(P18): 이 슬롯을 즉시 다시 읽는다(오인식 고착 탈출). 정상/오인식 무관 노출.
            // 창=카드/터치통과 보존 — 아이콘 터치 영역만 clickable, 그 밖은 게임으로 통과.
            if (onForceRescan != null) {
                Text(
                    stringResource(R.string.overlay_force_rescan),
                    color = AccentColor, fontSize = 15.sp.scaled(), fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onForceRescan() }
                        .padding(horizontal = 4.dp.scaled()),
                )
                Spacer(Modifier.width(2.dp.scaled()))
            }
            // 🔍 수동 지정(P18): 어떤 카드가 떠 있든 검색 시트로 올바른 포켓몬 지정(→ 핀).
            if (onOpenSearch != null) {
                Text(
                    stringResource(R.string.overlay_manual_search),
                    fontSize = 14.sp.scaled(),
                    modifier = Modifier
                        .clickable { onOpenSearch() }
                        .padding(horizontal = 4.dp.scaled()),
                )
                Spacer(Modifier.width(2.dp.scaled()))
            }
            // 종료(×) 버튼: 게임 중에도 손쉽게 끌 수 있는 진입점(터치 영역만 focusable, P5 패턴 유지).
            if (onExit != null) {
                Text(
                    stringResource(R.string.overlay_exit_x),
                    color = ExitColor, fontSize = 15.sp.scaled(), fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { onExit() }
                        .padding(horizontal = 4.dp.scaled()),
                )
                Spacer(Modifier.width(4.dp.scaled()))
            }
            Icon(
                imageVector = if (stage == CardStage.CHIP) Icons.Filled.KeyboardArrowDown
                else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (stage == CardStage.CHIP) stringResource(R.string.overlay_expand)
                else stringResource(R.string.overlay_collapse),
                tint = SubTextColor,
                modifier = Modifier.clickable { onInteract(); onTapCycle() },
            )
        }

        // --- 타입 칩 줄(항상 표시 = 1단계 핵심) ---
        Row(
            modifier = Modifier
                .padding(start = 12.dp.scaled(), end = 12.dp.scaled(), bottom = 8.dp.scaled())
                .clickable { onInteract(); onTapCycle() },
            horizontalArrangement = Arrangement.spacedBy(6.dp.scaled()),
        ) {
            data.typeChips.forEach { chip -> TypeChipView(chip) }
        }

        // --- 2단계(CARD): 특성 + 주요기술 + (메가/바꾸기) 세로 배치 ---
        // EXPANDED 는 아래 2컬럼 패널이 특성/기술까지 함께 렌더하므로 여기선 CARD 만.
        if (stage == CardStage.CARD) {
            Column(
                modifier = Modifier.padding(start = 12.dp.scaled(), end = 12.dp.scaled(), bottom = 10.dp.scaled()),
                verticalArrangement = Arrangement.spacedBy(4.dp.scaled()),
            ) {
                AbilityMovesBlock(data)

                // 메가 세그먼트 토글([기본][메가]/[메가 X][메가 Y]).
                if (megaForms.isNotEmpty()) {
                    MegaSegments(
                        forms = megaForms,
                        selection = megaSelection,
                        onSelect = { onInteract(); onSelectMega(it) },
                    )
                }

                // 후보 여럿 → "바꾸기" 진입점(DESIGN.md 5장).
                if (meta.hasMoreCandidates) {
                    Text(
                        stringResource(R.string.overlay_change_candidate),
                        color = AccentColor, fontSize = 11.sp.scaled(),
                        modifier = Modifier.clickable { onInteract(); onOpenCandidateSheet() },
                    )
                }

                // 3단계 진입 힌트.
                Text(
                    stringResource(R.string.overlay_more_detail),
                    color = AccentColor, fontSize = 11.sp.scaled(),
                    modifier = Modifier.clickable { onInteract(); onTapCycle() },
                )
            }
        }

        // --- 3단계(EXPANDED): 가로 2컬럼 패널(P30) ---
        // 왼쪽: 특성 + 주요기술(+메가/바꾸기).  오른쪽: 종족값(가로 한 줄) + 방어 상성.
        // 세로로 쌓이던 종족값 6줄·상성·기술을 가로로 펼쳐 카드 높이를 대략 절반으로 줄인다.
        if (stage == CardStage.EXPANDED) {
            data.expanded?.let { ex ->
                ExpandedTwoColumnPanel(
                    data = data,
                    ex = ex,
                    megaForms = megaForms,
                    megaSelection = megaSelection,
                    hasMoreCandidates = meta.hasMoreCandidates,
                    onInteract = onInteract,
                    onSelectMega = onSelectMega,
                    onOpenCandidateSheet = onOpenCandidateSheet,
                )
            }
        }
      } // Column

      // [P30] 우하단 모서리 리사이즈 그립. 주 카드에만(onResizeDrag != null). 드래그로 카드 크기 연속 조절.
      if (onResizeDrag != null) {
          ResizeGrip(
              onDrag = onResizeDrag,
              onDragEnd = onResizeEnd ?: {},
              modifier = Modifier.align(Alignment.BottomEnd),
          )
      }
    } // Box
}

/**
 * [P30] 우하단 모서리 리사이즈 그립. 작은 대각선 핸들 — 드래그하면 카드 스케일을 연속 조절한다.
 * 조작(interact) 모드에서만 메인 창이 터치를 받으므로 평소 게임 터치 통과는 보존된다(P24).
 * 눈에 띄되(대비 배경 + 대각선) 게임 정보를 가리지 않게 작게(≈18dp).
 */
@Composable
private fun ResizeGrip(
    onDrag: (dragDx: Float, dragDy: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gripSize = 18.dp.scaled()
    Box(
        modifier = modifier
            .padding(2.dp.scaled())
            .size(gripSize)
            .background(HandlePassiveBg, RoundedCornerShape(topStart = 8.dp.scaled(), bottomEnd = 10.dp.scaled()))
            .border(1.dp.scaled(), AccentColor, RoundedCornerShape(topStart = 8.dp.scaled(), bottomEnd = 10.dp.scaled()))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.x, amount.y)
                    },
                    onDragEnd = { onDragEnd() },
                )
            },
    ) {
        // 대각선 리사이즈 표시(우하단 방향 빗금 2개).
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize().padding(4.dp.scaled())) {
            val s = size.minDimension * 0.16f
            val c = AccentColor
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width, size.height * 0.35f),
                androidx.compose.ui.geometry.Offset(size.width * 0.35f, size.height), s)
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width, size.height * 0.7f),
                androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height), s)
        }
    }
}

/** 특성 줄 + 주요 기술(사용률%) 목록. CARD/EXPANDED 공용. */
@Composable
private fun AbilityMovesBlock(data: OverlayCardData) {
    if (data.abilities.isNotEmpty()) {
        Text(
            stringResource(R.string.overlay_ability_prefix) + data.abilities.joinToString(" / "),
            color = SubTextColor, fontSize = 12.sp.scaled(),
        )
    }
    data.topMoves.forEach { move -> MoveRow(move) }
}

/**
 * 배틀 형식 빠른 토글(P20). 오버레이 상단의 소형 세그먼트 [싱글][더블].
 * 대전마다 즉시 형식을 바꾼다 — 탭하면 [onSelect] → 서비스가 ROI(밴드 수)/사용률/슬롯을 전환한다.
 *
 * 창=카드 bounds/터치 통과 전략 보존: 이 세그먼트 자체만 터치를 받고(clickable),
 * 그 밖 영역은 여전히 게임으로 통과(창은 WRAP_CONTENT + FLAG_NOT_FOCUSABLE 유지).
 *
 * ⚠️ 향후 확장 지점: 여기 표시되는 형식을 자동 감지(장면/슬롯 수 추론) 결과로 자동 갱신하는 훅을 걸 수 있다.
 *    현재는 수동 토글만(P20 스코프).
 */
@Composable
fun FormatToggle(
    isDoubles: Boolean,
    onSelect: (doubles: Boolean) -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(CardBg, RoundedCornerShape(10.dp.scaled()))
            .then(dragModifier)
            .padding(horizontal = 6.dp.scaled(), vertical = 4.dp.scaled()),
        horizontalArrangement = Arrangement.spacedBy(4.dp.scaled()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FormatSegment(stringResource(R.string.format_single), selected = !isDoubles) { onSelect(false) }
        FormatSegment(stringResource(R.string.format_doubles), selected = isDoubles) { onSelect(true) }
    }
}

/**
 * [P21] 항상 보이는 오버레이 컨트롤 바.
 *
 * 캡처가 켜져 있으면(captureActive) 카드 유무와 무관하게 항상 렌더된다 — 인식이 실패해
 * 슬롯 카드가 없더라도 검색·보정·진단·형식전환·최소화 진입점이 사라지지 않는다.
 *
 * 창=카드 bounds/터치 통과 전략 보존: 이 바 자체(Row)만 터치를 받고, 그 밖 영역은 게임으로 통과한다
 * (창은 WRAP_CONTENT + 기본 FLAG_NOT_FOCUSABLE 유지). 반투명·아이콘 위주로 눈에 덜 거슬리게 한다.
 *
 * 구성(좌→우): [−]최소화 · [싱글/더블]형식 · [🔍]검색 · [⃞]보정 · [진단ON/OFF].
 * 배틀 형식 토글(P20)을 이 바에 통합해 항상 접근 가능하게 한다.
 */
@Composable
fun ControlBar(
    isDoubles: Boolean,
    dragModifier: Modifier,
    /** [−] 전체 오버레이를 작은 핸들로 최소화. */
    onMinimize: () -> Unit,
    /** 형식 세그먼트 선택(싱글/더블). */
    onSelectFormat: (doubles: Boolean) -> Unit,
    /** [🔍] 카드가 없어도 검색 시트 열기(수동 지정 → 핀). */
    onSearch: () -> Unit,
    /** [⃞] 이름 영역 보정(ROI) 오버레이 열기(ACTION_CALIBRATE). */
    onCalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(ControlBarBg, RoundedCornerShape(10.dp.scaled()))
            .then(dragModifier)
            .padding(horizontal = 6.dp.scaled(), vertical = 4.dp.scaled()),
        horizontalArrangement = Arrangement.spacedBy(6.dp.scaled()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 최소화(−): 전체를 작은 핸들로 접는다.
        ControlIcon(
            label = stringResource(R.string.overlay_minimize),
            contentDesc = stringResource(R.string.overlay_minimize_desc),
            color = SubTextColor,
            onClick = onMinimize,
        )
        ControlDivider()
        // 형식 빠른 토글(P20 통합).
        FormatSegment(stringResource(R.string.format_single), selected = !isDoubles) { onSelectFormat(false) }
        FormatSegment(stringResource(R.string.format_doubles), selected = isDoubles) { onSelectFormat(true) }
        ControlDivider()
        // 보정(주 해결책, P23): 인식 위치가 어긋났을 때 게임 화면 위에서 박스를 직접 맞춘다.
        //  깨지던 combining-char(⃞) 대신 벡터로 그린 조준 사각형 아이콘 + "보정" 라벨로 확실히 보이게 한다.
        CalibrateButton(
            label = stringResource(R.string.overlay_calibrate_label),
            contentDesc = stringResource(R.string.overlay_calibrate_desc),
            onClick = onCalibrate,
        )
        // 검색(최후 수단, P23): 인식/보정으로도 안 되면 이름을 직접 검색해 지정한다.
        LabeledControl(
            icon = stringResource(R.string.overlay_manual_search),
            label = stringResource(R.string.overlay_search_label),
            contentDesc = stringResource(R.string.overlay_manual_search_desc),
            color = AccentColor,
            onClick = onSearch,
        )
        // [진단]은 디버그용이라 컨트롤 바에서 제거하고 앱 설정(고급)으로 이동했다(P27).
        //  평소 사용자가 실수로 켜서 하단 "ocr/s" 스트립이 뜨는 혼란을 없앤다.
    }
}

/** 컨트롤 바의 아이콘 버튼(터치 영역만 clickable — 그 밖은 게임 통과). */
@Composable
private fun ControlIcon(
    label: String,
    contentDesc: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = color,
        fontSize = 15.sp.scaled(),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp.scaled(), vertical = 2.dp.scaled()),
    )
}

/**
 * 아이콘 문자 + 짧은 라벨을 함께 보여주는 컨트롤(P23).
 * 아이콘 하나만으로는 무슨 버튼인지 알기 어려운 게임 중 상황을 위해 라벨을 붙인다.
 * 터치 영역만 clickable — 그 밖은 게임 통과.
 */
@Composable
private fun LabeledControl(
    icon: String,
    label: String,
    contentDesc: String,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp.scaled()),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp.scaled(), vertical = 2.dp.scaled()),
    ) {
        Text(icon, color = color, fontSize = 14.sp.scaled(), fontWeight = FontWeight.Bold)
        Text(label, color = color, fontSize = 12.sp.scaled(), fontWeight = FontWeight.Bold)
    }
}

/**
 * [P23] 보정 진입 버튼. 깨지던 combining-char 대신 **벡터로 직접 그린 조준 사각형**
 * (모서리 브래킷 + 중앙 점)과 "보정"/"Aim" 라벨을 나란히 둔다 — 폰트/글리프에 의존하지 않아
 * 어떤 기기에서도 안정적으로 렌더된다. 게임 중 눈에 잘 띄고 탭하기 쉬운 크기.
 * 창=카드/터치통과 보존 — 이 Row 만 clickable.
 */
@Composable
private fun CalibrateButton(
    label: String,
    contentDesc: String,
    onClick: () -> Unit,
) {
    val iconSize = 15.dp.scaled()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp.scaled()),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp.scaled(), vertical = 2.dp.scaled()),
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(iconSize),
        ) {
            val stroke = size.minDimension * 0.14f
            val seg = size.minDimension * 0.32f // 모서리 브래킷 길이
            val c = AimIconColor
            // 4모서리 브래킷(조준 사각형).
            // 좌상
            drawLine(c, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(seg, 0f), stroke)
            drawLine(c, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, seg), stroke)
            // 우상
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width - seg, 0f), stroke)
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width, seg), stroke)
            // 좌하
            drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(seg, size.height), stroke)
            drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(0f, size.height - seg), stroke)
            // 우하
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width, size.height), androidx.compose.ui.geometry.Offset(size.width - seg, size.height), stroke)
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height - seg), stroke)
            // 중앙 점.
            drawCircle(c, radius = size.minDimension * 0.11f, center = center)
        }
        Text(label, color = AimIconColor, fontSize = 12.sp.scaled(), fontWeight = FontWeight.Bold)
    }
}

/** 컨트롤 바 구획 구분자(얇은 세로선 대체 — 반투명 점/여백). */
@Composable
private fun ControlDivider() {
    Text("·", color = Color(0x55_FFFFFF), fontSize = 13.sp.scaled())
}

/**
 * [P24] 상호작용 토글 핸들. **메인 창과 별도의 아주 작은 상시 touchable 창**에 렌더된다.
 *
 * 메인 정보/컨트롤 창은 기본 `FLAG_NOT_TOUCHABLE` 라 평소 모든 터치가 게임으로 통과한다.
 * 이 핸들만 유일하게 상시 터치를 받아, 탭하면 메인 창을 잠시 상호작용 모드로 전환한다
 * (카드 펼치기/검색/보정/후보 선택 등). 다시 탭하거나 무조작 N초면 통과 모드로 자동 복귀.
 *
 *  - 🔒(통과중): 게임 터치 100% 통과 — 카드는 순수 표시.
 *  - ✋(조작중): 메인 창 터치 활성 — 카드/컨트롤 조작 가능.
 *
 * 드래그로 위치 이동(구석에 붙일 수 있음, 위치 저장). 게임을 거의 안 가리는 크기.
 */
@Composable
fun InteractionHandle(
    interactive: Boolean,
    minimized: Boolean,
    dragModifier: Modifier,
    onToggle: () -> Unit,
    /** 길게 누르기 → 카드/컨트롤 전체 최소화 ↔ 복원(P21 통합). */
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // [P25] 화려한 게임 위에서도 눈에 띄게: 불투명 배경 + 대비 테두리 + 상태색 + 아이콘 + 짧은 라벨.
    //  - 조작중(✋): 강조색 배경/흰 글자 — "지금 오버레이 조작 가능".
    //  - 통과중(🔒): 짙은 불투명 배경/강조색 글자 — "게임 터치 100% 통과".
    //  - 최소화(👁): 카드 전체 숨김 상태 — 탭하면 조작, 롱프레스로 복원.
    val bg = if (interactive) HandleActiveBg else HandlePassiveBg
    val fg = if (interactive) Color.White else AccentColor
    val borderColor = if (interactive) Color.White else AccentColor
    // 아이콘 글리프.
    val glyphRes = when {
        minimized -> R.string.overlay_handle_minimized
        interactive -> R.string.overlay_handle_interacting
        else -> R.string.overlay_handle_passthrough
    }
    // 짧은 상태 라벨(현지화): "게임"(통과중)/"조작"(조작중)/"복원"(최소화).
    val labelRes = when {
        minimized -> R.string.overlay_handle_label_minimized
        interactive -> R.string.overlay_handle_label_interacting
        else -> R.string.overlay_handle_label_passthrough
    }
    Column(
        modifier = modifier
            .then(dragModifier)
            // 최소 탭 영역 확보(≥48dp; 스케일 반영). 화려한 배경 위 대비를 위해 불투명 + 테두리.
            .size(60.dp.scaled())
            .background(bg, RoundedCornerShape(16.dp.scaled()))
            .border(2.dp.scaled(), borderColor, RoundedCornerShape(16.dp.scaled()))
            .pointerInput(interactive, minimized) {
                detectTapGestures(
                    onTap = { onToggle() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(horizontal = 4.dp.scaled(), vertical = 4.dp.scaled()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(glyphRes),
            color = fg,
            fontSize = 20.sp.scaled(),
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(labelRes),
            color = fg,
            fontSize = 11.sp.scaled(),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun FormatSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else SubTextColor,
        fontSize = 11.sp.scaled(),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(
                if (selected) AccentColor.copy(alpha = 0.85f) else Color(0x33_FFFFFF),
                RoundedCornerShape(6.dp.scaled()),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp.scaled(), vertical = 3.dp.scaled()),
    )
}

/**
 * 인식 실패 상태 카드(수동 검색 진입). DESIGN.md 5장 상태별 UX.
 * P18: 카드 본문 탭 = 검색 시트, 우측 ↻ = 강제 재인식(즉시 다시 읽기 — 일시적 미인식 탈출).
 */
@Composable
fun FailureCard(
    dragModifier: Modifier,
    onOpenSearchSheet: () -> Unit,
    /** 강제 재인식(P18). null 이면 ↻ 미노출(데모/하위호환). */
    onForceRescan: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 160.dp.scaled(), max = 300.dp.scaled())
            .background(CardBg, RoundedCornerShape(12.dp.scaled()))
            .clickable(onClick = onOpenSearchSheet)
            .then(dragModifier)
            .padding(horizontal = 12.dp.scaled(), vertical = 10.dp.scaled()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.overlay_recognition_fail), color = ChipTextColor,
            fontSize = 14.sp.scaled(), fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp.scaled()))
        Text(stringResource(R.string.overlay_tap_to_search), color = AccentColor, fontSize = 12.sp.scaled(),
            modifier = Modifier.weight(1f))
        // ↻ 강제 재인식: 검색 없이 곧바로 "지금 다시 읽어"(정지 화면이라도 즉시 OCR).
        if (onForceRescan != null) {
            Spacer(Modifier.width(8.dp.scaled()))
            Text(
                stringResource(R.string.overlay_force_rescan),
                color = AccentColor, fontSize = 15.sp.scaled(), fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onForceRescan() }
                    .padding(horizontal = 4.dp.scaled()),
            )
        }
    }
}

/**
 * 캡처 중단 상태 카드(DESIGN.md 5장). 화면잠금/사용자 중단 시 표시.
 * MediaProjection 토큰은 1회성이라 재시작하려면 재동의가 필요 → 탭하면 [onRestart](앱 재실행).
 */
@Composable
fun CaptureStoppedCard(
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 180.dp.scaled(), max = 300.dp.scaled())
            .background(CardBg, RoundedCornerShape(12.dp.scaled()))
            .padding(horizontal = 14.dp.scaled(), vertical = 12.dp.scaled()),
        verticalArrangement = Arrangement.spacedBy(6.dp.scaled()),
    ) {
        Text(stringResource(R.string.overlay_capture_stopped_title), color = ChipTextColor,
            fontSize = 14.sp.scaled(), fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.overlay_capture_stopped_body),
            color = SubTextColor, fontSize = 12.sp.scaled(),
        )
        Text(
            stringResource(R.string.overlay_capture_restart),
            color = AccentColor, fontSize = 13.sp.scaled(), fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color(0x33_8AB4F8), RoundedCornerShape(8.dp.scaled()))
                .clickable(onClick = onRestart)
                .padding(horizontal = 12.dp.scaled(), vertical = 6.dp.scaled()),
        )
    }
}

/**
 * 캡처 건강 안내 카드(K1 자동 진단, P17). DESIGN.md 1장 K1 최대 리스크의 앱 내 고지.
 *  - BLACK_SCREEN: FLAG_SECURE 로 캡처가 차단된 것으로 보임(검은 화면). 이 게임에선 오버레이 불가.
 *  - NO_FRAMES: 화면 프레임을 못 받음(캡처 권한/재시작 확인). "재시작" 진입점(P7 재동의 재사용).
 * Healthy 복귀 시 호출부가 이 카드를 걷는다(자동 해제).
 */
@Composable
fun CaptureHealthCard(
    health: com.pochamps.supporter.capture.CaptureHealth.Health,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBlack = health == com.pochamps.supporter.capture.CaptureHealth.Health.BLACK_SCREEN
    val titleRes = if (isBlack) R.string.overlay_health_black_title
    else R.string.overlay_health_noframes_title
    val bodyRes = if (isBlack) R.string.overlay_health_black_body
    else R.string.overlay_health_noframes_body
    // 검정(차단)은 배경색을 경고톤으로, 프레임미수신은 정보톤으로 구분.
    val cardColor = if (isBlack) Color(0xF2_3A1F1F) else Color(0xE6_1A1A1A)

    Column(
        modifier = modifier
            .widthIn(min = 200.dp.scaled(), max = 320.dp.scaled())
            .background(cardColor, RoundedCornerShape(12.dp.scaled()))
            .padding(horizontal = 14.dp.scaled(), vertical = 12.dp.scaled()),
        verticalArrangement = Arrangement.spacedBy(6.dp.scaled()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(titleRes), color = ChipTextColor,
                fontSize = 14.sp.scaled(), fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // 닫기(오탐 대비 사용자 수동 해제). Healthy 복귀 시엔 호출부가 자동 해제.
            Text(
                stringResource(R.string.sheet_close), color = AccentColor,
                fontSize = 12.sp.scaled(),
                modifier = Modifier.clickable(onClick = onDismiss),
            )
        }
        Text(stringResource(bodyRes), color = SubTextColor, fontSize = 12.sp.scaled())
        // NoFrames 는 재시작(재동의)이 회복 경로 → 버튼 노출. BlackScreen 은 재시작해도 소용없으므로 미노출.
        if (!isBlack) {
            Text(
                stringResource(R.string.overlay_capture_restart),
                color = AccentColor, fontSize = 13.sp.scaled(), fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color(0x33_8AB4F8), RoundedCornerShape(8.dp.scaled()))
                    .clickable(onClick = onRestart)
                    .padding(horizontal = 12.dp.scaled(), vertical = 6.dp.scaled()),
            )
        }
    }
}

/**
 * "배틀명 표시 ON" 1회 안내 배너(DESIGN.md 5장 이름 미표시).
 * 장시간 미인식 시 노출. 탭하면 닫힘([onDismiss]).
 */
@Composable
fun BattleNamesHintBanner(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 180.dp.scaled(), max = 300.dp.scaled())
            .background(Color(0xE6_37474F), RoundedCornerShape(12.dp.scaled()))
            .padding(horizontal = 12.dp.scaled(), vertical = 10.dp.scaled()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.overlay_battlenames_hint),
            color = ChipTextColor, fontSize = 12.sp.scaled(), modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp.scaled()))
        Text(
            stringResource(R.string.sheet_close), color = AccentColor, fontSize = 12.sp.scaled(),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onDismiss),
        )
    }
}

/**
 * 진단 스트립(P14, adb 대체). 진단 모드 on 일 때 카드 밑에 뜨는 소형 패널.
 * 슬롯별 마지막 OCR 원문/매칭 root+editDistance, 마지막 인식 경과, OCR 빈도(회/s)를 표시한다.
 * 인식이 계속 실패할 때 원인(빈 텍스트 vs 미매칭)을 사용자가 바로 본다.
 */
@Composable
fun DiagnosticStrip(state: com.pochamps.supporter.capture.DiagState) {
    Column(
        modifier = Modifier
            .widthIn(min = 180.dp.scaled(), max = 320.dp.scaled())
            .background(Color(0xF0_101820), RoundedCornerShape(10.dp.scaled()))
            .padding(horizontal = 10.dp.scaled(), vertical = 8.dp.scaled()),
        verticalArrangement = Arrangement.spacedBy(3.dp.scaled()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.diag_title), color = AccentColor, fontSize = 11.sp.scaled(),
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                com.pochamps.supporter.capture.DiagState.formatRate(state),
                color = SubTextColor, fontSize = 10.sp.scaled(),
            )
        }
        // 슬롯별 한 줄(정렬).
        state.slots.keys.sorted().forEach { slot ->
            val d = state.slots[slot] ?: return@forEach
            val color = when (d.outcome) {
                com.pochamps.supporter.capture.SlotDiag.Outcome.MATCHED -> PosDelta
                com.pochamps.supporter.capture.SlotDiag.Outcome.EMPTY_TEXT -> NegDelta
                com.pochamps.supporter.capture.SlotDiag.Outcome.UNMATCHED_TEXT -> Color(0xFF_FFB74D)
            }
            Text(
                com.pochamps.supporter.capture.DiagState.formatSlot(d),
                color = color, fontSize = 11.sp.scaled(),
            )
        }
        Text(
            com.pochamps.supporter.capture.DiagState.formatLastSeen(state),
            color = SubTextColor, fontSize = 10.sp.scaled(),
        )
        // 캡처 건강 한 줄(K1 자동 진단, P17). 정상=회색, 이상=경고색.
        val healthColor = when (state.health) {
            com.pochamps.supporter.capture.CaptureHealth.Health.HEALTHY -> SubTextColor
            else -> NegDelta
        }
        Text(
            com.pochamps.supporter.capture.DiagState.formatHealth(state),
            color = healthColor, fontSize = 10.sp.scaled(),
        )
    }
}

/**
 * [P30] 확장 카드 가로 2컬럼 패널.
 *
 * 세로로 쌓이던 확장 내용(종족값 6줄 + 상성 + 기술)을 좌우로 나눠 카드 세로 높이를 대략 절반으로 줄인다.
 *  - 왼쪽 컬럼: 도감# + 특성 + 주요 기술(사용률%) (+메가/바꾸기 진입).
 *  - 오른쪽 컬럼: 종족값(H·A·B·C·D·S 가로 한 줄 + 합계) + 방어 상성(약점/반감/무효).
 *
 * 안전망: 그래도 화면을 넘치면 [maxHeight]([LocalOverlayScreenHeightDp] 의 ~85%) 로 제한하고 내부 세로
 * 스크롤을 붙인다. 넘치지 않으면 스크롤은 나타나지 않는다(wrap-content).
 */
@Composable
private fun ExpandedTwoColumnPanel(
    data: OverlayCardData,
    ex: OverlayCardData.ExpandedData,
    megaForms: List<OverlayCardData.MegaForm>,
    megaSelection: Int,
    hasMoreCandidates: Boolean,
    onInteract: () -> Unit,
    onSelectMega: (Int) -> Unit,
    onOpenCandidateSheet: () -> Unit,
) {
    // 안전망 최대 높이: 화면 높이의 ~85%. 화면 높이를 모르면(0) 제한 없음(wrap-content).
    val screenH = LocalOverlayScreenHeightDp.current
    val maxH: Dp = if (screenH > 0) (screenH * 0.85f).dp else Dp.Unspecified
    val scrollMod = if (maxH != Dp.Unspecified) {
        Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())
    } else Modifier

    Row(
        modifier = Modifier
            .padding(start = 12.dp.scaled(), end = 12.dp.scaled(), bottom = 12.dp.scaled())
            .then(scrollMod)
            .clickable(onClick = onInteract), // 스크롤/탭 = 조작 → 타이머 리셋
        horizontalArrangement = Arrangement.spacedBy(14.dp.scaled()),
    ) {
        // 왼쪽 컬럼: 특성 + 주요 기술 (+메가/바꾸기).
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp.scaled()),
        ) {
            Text(stringResource(R.string.overlay_dex_prefix) + "${ex.dexNumber}",
                color = SubTextColor, fontSize = 11.sp.scaled())
            AbilityMovesBlock(data)

            if (megaForms.isNotEmpty()) {
                MegaSegments(
                    forms = megaForms,
                    selection = megaSelection,
                    onSelect = { onInteract(); onSelectMega(it) },
                )
            }
            if (hasMoreCandidates) {
                Text(
                    stringResource(R.string.overlay_change_candidate),
                    color = AccentColor, fontSize = 11.sp.scaled(),
                    modifier = Modifier.clickable { onInteract(); onOpenCandidateSheet() },
                )
            }
        }

        // 오른쪽 컬럼: 종족값(가로 한 줄) + 방어 상성.
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp.scaled()),
        ) {
            SectionLabel(stringResource(R.string.overlay_section_stats))
            StatsRowHorizontal(ex)

            if (ex.matchups.isNotEmpty()) {
                SectionLabel(stringResource(R.string.overlay_section_matchup))
                ex.matchups.forEach { line -> MatchupRow(line) }
            }
        }
    }
}

/**
 * [P30] 종족값을 가로 한 줄로: H·A·B·C·D·S 6칸 + 합계.
 * 기존 6줄(HP 78 / 공격 81 …)을 가로로 펼쳐 세로 3~4줄을 절약한다.
 * 각 칸은 [라벨/값](메가면 증감 색으로). 합계는 우측에 별도 표시.
 */
@Composable
private fun StatsRowHorizontal(ex: OverlayCardData.ExpandedData) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp.scaled()),
        verticalAlignment = Alignment.Top,
    ) {
        ex.stats.forEach { s -> StatCell(s, Modifier.weight(1f)) }
    }
    // 합계 줄(가로 배치라 한 줄만 추가).
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.overlay_section_total), color = SubTextColor, fontSize = 11.sp.scaled())
        Spacer(Modifier.width(4.dp.scaled()))
        Text("${ex.statTotal}", color = ChipTextColor, fontSize = 12.sp.scaled(), fontWeight = FontWeight.Bold)
        DeltaText(ex.statTotalDelta)
    }
}

/** 종족값 한 칸(H/A/B/C/D/S 라벨 위, 값 아래). 메가 증감이 있으면 값 색을 증감색으로. */
@Composable
private fun StatCell(s: OverlayCardData.StatLine, modifier: Modifier = Modifier) {
    val valueColor = when {
        s.delta > 0 -> PosDelta
        s.delta < 0 -> NegDelta
        else -> ChipTextColor
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(s.shortLabel, color = SubTextColor, fontSize = 10.sp.scaled(), fontWeight = FontWeight.Bold)
        Text("${s.value}", color = valueColor, fontSize = 12.sp.scaled(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DeltaText(delta: Int) {
    if (delta == 0) return
    val sign = if (delta > 0) "+$delta" else "$delta"
    Text(
        sign,
        color = if (delta > 0) PosDelta else NegDelta,
        fontSize = 11.sp.scaled(), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 6.dp.scaled()),
    )
}

@Composable
private fun MatchupRow(line: OverlayCardData.MatchupLine) {
    val labelRes = when (line.bucket) {
        OverlayCardData.MatchupBucket.WEAK4 -> R.string.overlay_matchup_weak4
        OverlayCardData.MatchupBucket.WEAK2 -> R.string.overlay_matchup_weak2
        OverlayCardData.MatchupBucket.RESIST_QUARTER -> R.string.overlay_matchup_resist_quarter
        OverlayCardData.MatchupBucket.RESIST_HALF -> R.string.overlay_matchup_resist_half
        OverlayCardData.MatchupBucket.IMMUNE -> R.string.overlay_matchup_immune
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp.scaled())) {
        Text(stringResource(labelRes), color = SubTextColor, fontSize = 11.sp.scaled())
        FlowTypeChips(line.types)
    }
}

/** 타입 칩을 줄바꿈 없이(좁으면 잘림 방지 위해) 가로 나열. 상성 목록은 보통 짧다. */
@Composable
private fun FlowTypeChips(chips: List<OverlayCardData.TypeChip>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp.scaled())) {
        chips.take(9).forEach { TypeChipView(it) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = AccentColor, fontSize = 11.sp.scaled(), fontWeight = FontWeight.Bold)
}

@Composable
private fun MegaSegments(
    forms: List<OverlayCardData.MegaForm>,
    selection: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp.scaled())) {
        SegmentButton(label = stringResource(R.string.overlay_mega_base),
            selected = selection < 0, onClick = { onSelect(-1) })
        forms.forEachIndexed { idx, form ->
            SegmentButton(label = form.label, selected = selection == idx, onClick = { onSelect(idx) })
        }
    }
}

@Composable
private fun SegmentButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.White else SubTextColor,
        fontSize = 11.sp.scaled(),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(
                if (selected) MegaColor else Color(0x33_FFFFFF),
                RoundedCornerShape(6.dp.scaled()),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp.scaled(), vertical = 3.dp.scaled()),
    )
}

@Composable
private fun TypeChipView(chip: OverlayCardData.TypeChip) {
    val bg = parseColorOrNull(chip.colorHex) ?: Color(0xFF_666666)
    Text(
        text = chip.label,
        color = Color.White,
        fontSize = 12.sp.scaled(),
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp.scaled()))
            .padding(horizontal = 8.dp.scaled(), vertical = 2.dp.scaled()),
    )
}

@Composable
private fun MoveRow(move: OverlayCardData.MoveLine) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(move.label, color = ChipTextColor, fontSize = 12.sp.scaled(), modifier = Modifier.weight(1f))
        move.pct?.let {
            Spacer(Modifier.width(8.dp.scaled()))
            Text("${it.toInt()}%", color = SubTextColor, fontSize = 11.sp.scaled())
        }
    }
}

@Composable
private fun PinBadge(onClick: () -> Unit) {
    Text(
        stringResource(R.string.overlay_pin_release),
        color = Color.White, fontSize = 10.sp.scaled(), fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(Color(0xFF_546E7A), RoundedCornerShape(4.dp.scaled()))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp.scaled(), vertical = 1.dp.scaled()),
    )
}

// --- 시트(창 내부 확장) ---

/**
 * 후보 선택 시트: 각 후보를 타입칩+사용률과 함께. 최상위=추천 배지.
 * [sideFlyout](P16)=true 면 가로 측면 flyout 모드로, 세로 높이가 잘리지 않게 화면 높이에 맞춰 clamp.
 */
@Composable
fun CandidateSheet(
    choices: List<CandidateChoice>,
    onPick: (key: String) -> Unit,
    onDismiss: () -> Unit,
    maxSheetHeight: Dp = 280.dp,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp.scaled(), max = 320.dp.scaled())
            .background(CardBg, RoundedCornerShape(12.dp.scaled()))
            .padding(12.dp.scaled()),
        verticalArrangement = Arrangement.spacedBy(8.dp.scaled()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sheet_candidate_title), color = ChipTextColor, fontSize = 13.sp.scaled(),
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.sheet_close), color = AccentColor, fontSize = 12.sp.scaled(),
                modifier = Modifier.clickable(onClick = onDismiss))
        }
        Column(
            modifier = Modifier.heightIn(max = maxSheetHeight).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp.scaled()),
        ) {
            choices.forEach { c ->
                Column(
                    modifier = Modifier
                        .background(Color(0x22_FFFFFF), RoundedCornerShape(8.dp.scaled()))
                        .then(
                            if (c.recommended)
                                Modifier.border(1.dp.scaled(), AccentColor, RoundedCornerShape(8.dp.scaled()))
                            else Modifier,
                        )
                        .clickable { onPick(c.key) }
                        .padding(8.dp.scaled()),
                    verticalArrangement = Arrangement.spacedBy(4.dp.scaled()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, color = ChipTextColor, fontSize = 13.sp.scaled(),
                            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (c.recommended) {
                            Text(stringResource(R.string.sheet_recommended), color = Color.White,
                                fontSize = 9.sp.scaled(), fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(AccentColor, RoundedCornerShape(4.dp.scaled()))
                                    .padding(horizontal = 4.dp.scaled(), vertical = 1.dp.scaled()))
                            Spacer(Modifier.width(4.dp.scaled()))
                        }
                        c.usagePct?.let {
                            Text("${it.toInt()}%", color = SubTextColor, fontSize = 11.sp.scaled())
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp.scaled())) {
                        c.typeChips.forEach { TypeChipView(it) }
                    }
                }
            }
        }
    }
}

/** 수동 검색 시트: 이름 부분일치 입력 + 결과 목록. 선택 시 슬롯 핀. */
@Composable
fun SearchSheet(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SearchChoice>,
    onPick: (key: String) -> Unit,
    onDismiss: () -> Unit,
    maxSheetHeight: Dp = 240.dp,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp.scaled(), max = 320.dp.scaled())
            .background(CardBg, RoundedCornerShape(12.dp.scaled()))
            .padding(12.dp.scaled()),
        verticalArrangement = Arrangement.spacedBy(8.dp.scaled()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sheet_search_title), color = ChipTextColor, fontSize = 13.sp.scaled(),
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.sheet_close), color = AccentColor, fontSize = 12.sp.scaled(),
                modifier = Modifier.clickable(onClick = onDismiss))
        }
        Box(
            modifier = Modifier
                .background(Color(0x33_FFFFFF), RoundedCornerShape(8.dp.scaled()))
                .padding(horizontal = 10.dp.scaled(), vertical = 8.dp.scaled()),
        ) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.sheet_search_hint), color = SubTextColor, fontSize = 13.sp.scaled())
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = ChipTextColor, fontSize = 13.sp.scaled()),
                cursorBrush = SolidColor(AccentColor),
            )
        }
        Column(
            modifier = Modifier.heightIn(max = maxSheetHeight).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp.scaled()),
        ) {
            results.forEach { r ->
                Text(
                    r.name,
                    color = ChipTextColor, fontSize = 13.sp.scaled(),
                    modifier = Modifier
                        .background(Color(0x22_FFFFFF), RoundedCornerShape(6.dp.scaled()))
                        .clickable { onPick(r.key) }
                        .padding(horizontal = 10.dp.scaled(), vertical = 6.dp.scaled()),
                )
            }
        }
    }
}

/**
 * "#RRGGBB" / "#AARRGGBB" 헥스 → Compose Color. 파싱 실패 시 null.
 */
internal fun parseColorOrNull(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val cleaned = hex.trim().removePrefix("#")
    return try {
        when (cleaned.length) {
            6 -> Color(0xFF000000 or cleaned.toLong(16))
            8 -> Color(cleaned.toLong(16))
            else -> null
        }
    } catch (_: NumberFormatException) {
        null
    }
}

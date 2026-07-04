package com.pochamps.supporter.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 */

private val CardBg = Color(0xE6_1A1A1A)
private val ChipTextColor = Color(0xFF_FFFFFF)
private val SubTextColor = Color(0xFF_D0D0D0)
private val AccentColor = Color(0xFF_8AB4F8)
private val MegaColor = Color(0xFF_7E57C2)
private val PosDelta = Color(0xFF_66BB6A)
private val NegDelta = Color(0xFF_EF5350)

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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(min = 160.dp, max = 300.dp)
            .background(CardBg, RoundedCornerShape(12.dp)),
    ) {
        // --- 상단 그립 바(드래그 핸들 + 이름 + 메가배지 + chevron) ---
        Row(
            modifier = dragModifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = data.name,
                color = ChipTextColor,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            if (meta.pinned) {
                PinBadge(onClick = onUnpin)
                Spacer(Modifier.width(6.dp))
            }
            if (data.canMega) {
                Text(
                    stringResource(R.string.overlay_mega_badge),
                    color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(MegaColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
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
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                .clickable { onInteract(); onTapCycle() },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            data.typeChips.forEach { chip -> TypeChipView(chip) }
        }

        // --- 2단계: 특성 + 주요기술 + (메가/바꾸기) ---
        if (stage == CardStage.CARD || stage == CardStage.EXPANDED) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (data.abilities.isNotEmpty()) {
                    Text(
                        stringResource(R.string.overlay_ability_prefix) +
                            data.abilities.joinToString(" / "),
                        color = SubTextColor, fontSize = 12.sp,
                    )
                }
                data.topMoves.forEach { move -> MoveRow(move) }

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
                        color = AccentColor, fontSize = 11.sp,
                        modifier = Modifier.clickable { onInteract(); onOpenCandidateSheet() },
                    )
                }

                // 3단계 진입 힌트.
                if (stage == CardStage.CARD) {
                    Text(
                        stringResource(R.string.overlay_more_detail),
                        color = AccentColor, fontSize = 11.sp,
                        modifier = Modifier.clickable { onInteract(); onTapCycle() },
                    )
                }
            }
        }

        // --- 3단계: 확장 패널(스크롤) ---
        if (stage == CardStage.EXPANDED) {
            data.expanded?.let { ex -> ExpandedPanel(ex, onInteract) }
        }
    }
}

/** 인식 실패 상태 카드(수동 검색 진입). DESIGN.md 5장 상태별 UX. */
@Composable
fun FailureCard(
    dragModifier: Modifier,
    onOpenSearchSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(min = 160.dp, max = 300.dp)
            .background(CardBg, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenSearchSheet)
            .then(dragModifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.overlay_recognition_fail), color = ChipTextColor,
            fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.overlay_tap_to_search), color = AccentColor, fontSize = 12.sp)
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
            .widthIn(min = 180.dp, max = 300.dp)
            .background(CardBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.overlay_capture_stopped_title), color = ChipTextColor,
            fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.overlay_capture_stopped_body),
            color = SubTextColor, fontSize = 12.sp,
        )
        Text(
            stringResource(R.string.overlay_capture_restart),
            color = AccentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color(0x33_8AB4F8), RoundedCornerShape(8.dp))
                .clickable(onClick = onRestart)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
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
            .widthIn(min = 180.dp, max = 300.dp)
            .background(Color(0xE6_37474F), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.overlay_battlenames_hint),
            color = ChipTextColor, fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.sheet_close), color = AccentColor, fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable(onClick = onDismiss),
        )
    }
}

@Composable
private fun ExpandedPanel(ex: OverlayCardData.ExpandedData, onInteract: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .clickable(onClick = onInteract), // 스크롤/탭 = 조작 → 타이머 리셋
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.overlay_dex_prefix) + "${ex.dexNumber}",
            color = SubTextColor, fontSize = 11.sp)

        // 종족값 6스탯(메가면 증감).
        SectionLabel(stringResource(R.string.overlay_section_stats))
        ex.stats.forEach { StatRow(it) }
        Row {
            Text(stringResource(R.string.overlay_section_total), color = ChipTextColor,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${ex.statTotal}", color = ChipTextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            DeltaText(ex.statTotalDelta)
        }

        // 방어 상성.
        if (ex.matchups.isNotEmpty()) {
            SectionLabel(stringResource(R.string.overlay_section_matchup))
            ex.matchups.forEach { line -> MatchupRow(line) }
        }

        // 전체 기술(사용률순).
        if (ex.allMoves.isNotEmpty()) {
            SectionLabel(stringResource(R.string.overlay_section_moves))
            ex.allMoves.forEach { MoveRow(it) }
        }
    }
}

@Composable
private fun StatRow(s: OverlayCardData.StatLine) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.label, color = SubTextColor, fontSize = 12.sp, modifier = Modifier.width(56.dp))
        Text("${s.value}", color = ChipTextColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        DeltaText(s.delta)
    }
}

@Composable
private fun DeltaText(delta: Int) {
    if (delta == 0) return
    val sign = if (delta > 0) "+$delta" else "$delta"
    Text(
        sign,
        color = if (delta > 0) PosDelta else NegDelta,
        fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 6.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(labelRes), color = SubTextColor, fontSize = 11.sp)
        FlowTypeChips(line.types)
    }
}

/** 타입 칩을 줄바꿈 없이(좁으면 잘림 방지 위해) 가로 나열. 상성 목록은 보통 짧다. */
@Composable
private fun FlowTypeChips(chips: List<OverlayCardData.TypeChip>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.take(9).forEach { TypeChipView(it) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = AccentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun MegaSegments(
    forms: List<OverlayCardData.MegaForm>,
    selection: Int,
    onSelect: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .background(
                if (selected) MegaColor else Color(0x33_FFFFFF),
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun TypeChipView(chip: OverlayCardData.TypeChip) {
    val bg = parseColorOrNull(chip.colorHex) ?: Color(0xFF_666666)
    Text(
        text = chip.label,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun MoveRow(move: OverlayCardData.MoveLine) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(move.label, color = ChipTextColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        move.pct?.let {
            Spacer(Modifier.width(8.dp))
            Text("${it.toInt()}%", color = SubTextColor, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PinBadge(onClick: () -> Unit) {
    Text(
        stringResource(R.string.overlay_pin_release),
        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(Color(0xFF_546E7A), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

// --- 시트(창 내부 확장) ---

/** 후보 선택 시트: 각 후보를 타입칩+사용률과 함께. 최상위=추천 배지. */
@Composable
fun CandidateSheet(
    choices: List<CandidateChoice>,
    onPick: (key: String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 320.dp)
            .background(CardBg, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sheet_candidate_title), color = ChipTextColor, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.sheet_close), color = AccentColor, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onDismiss))
        }
        Column(
            modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            choices.forEach { c ->
                Column(
                    modifier = Modifier
                        .background(Color(0x22_FFFFFF), RoundedCornerShape(8.dp))
                        .then(
                            if (c.recommended)
                                Modifier.border(1.dp, AccentColor, RoundedCornerShape(8.dp))
                            else Modifier,
                        )
                        .clickable { onPick(c.key) }
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, color = ChipTextColor, fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (c.recommended) {
                            Text(stringResource(R.string.sheet_recommended), color = Color.White,
                                fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(AccentColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        c.usagePct?.let {
                            Text("${it.toInt()}%", color = SubTextColor, fontSize = 11.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
) {
    Column(
        modifier = Modifier
            .widthIn(min = 200.dp, max = 320.dp)
            .background(CardBg, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sheet_search_title), color = ChipTextColor, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.sheet_close), color = AccentColor, fontSize = 12.sp,
                modifier = Modifier.clickable(onClick = onDismiss))
        }
        Box(
            modifier = Modifier
                .background(Color(0x33_FFFFFF), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.sheet_search_hint), color = SubTextColor, fontSize = 13.sp)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = ChipTextColor, fontSize = 13.sp),
                cursorBrush = SolidColor(AccentColor),
            )
        }
        Column(
            modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            results.forEach { r ->
                Text(
                    r.name,
                    color = ChipTextColor, fontSize = 13.sp,
                    modifier = Modifier
                        .background(Color(0x22_FFFFFF), RoundedCornerShape(6.dp))
                        .clickable { onPick(r.key) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
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

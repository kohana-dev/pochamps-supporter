package com.pochamps.supporter.capture

import com.pochamps.supporter.data.Candidate
import com.pochamps.supporter.matching.MatchResult

/**
 * 파이프라인의 "순수 판정" 로직 — OCR/매칭 결과를 오버레이 갱신 액션으로 변환한다.
 *
 * ⚠️ Android 의존성 없음(순수 JVM). ML Kit/캡처를 mock 한 상태로 판정 규칙을 유닛 테스트할 수 있다.
 *
 * 규칙(DESIGN.md 3장 5단계 + 5장 후보선택 기억/핀):
 *  - NoMatch → 기존 카드 유지(미매칭이면 카드 안 지움). [KeepCurrent]
 *  - Matched 후보 1개 → 그 후보로 카드 갱신, 후보없음. [UpdateCard(hasMoreCandidates=false)]
 *  - Matched 후보 2+ →
 *      · 해당 슬롯에 **같은 root 로 이미 유저가 고른 선택**이 있으면 그 key 로 갱신(기억).
 *      · 없으면 최상위(usage_rank) 표시 + 후보 있음 플래그(선택 UI).
 *  - 같은 ROI 에서 직전과 동일 key → 불필요한 UI 갱신 스킵. [NoChange]
 *  - **핀(수동 고정)**: 유저가 슬롯에 수동 검색/후보 선택으로 key 를 고정하면, 파이프라인이 덮어쓰지 않음.
 *
 * ## P11 — 저신뢰 전환 히스테리시스(움직이는 배틀 영상 오인식 억제)
 * 실배틀에선 카메라 회전·기술 이펙트 프레임에서 이름표가 가려지거나 왜곡돼 OCR 이 **엉뚱한 다른 포켓몬**으로
 * fuzzy 매칭될 수 있다(editDistance 큰 약매칭). 이를 그대로 카드에 반영하면 카드가 깜빡이며 오인식 스팸이 된다.
 *  - **고신뢰 전환(editDistance ≤ [CONFIDENT_EDIT_DISTANCE])**: 즉시 카드 교체(정상 이름표 프레임).
 *  - **저신뢰 전환(editDistance 큰 약매칭)으로 *다른 root* 전환**: **연속 [SWITCH_CONFIRM_COUNT] 회**
 *    같은 root 로 인식돼야 카드를 교체(1~N-1 회는 유지). 단발 오인식은 무시된다.
 *  - **최초 카드 취득**(아직 표시 중인 카드가 없을 때)은 지연 없이 즉시 표시(약매칭이라도 첫 정보는 보여줌).
 */
class PipelineDecider {

    /** ROI 별 마지막으로 표시한 key(연속 동일 인식 스킵용). */
    private val lastKeyByRoi = HashMap<Int, String>()

    /** ROI 별 현재 표시 중인 root(저신뢰 전환 히스테리시스 기준). */
    private val lastRootByRoi = HashMap<Int, String>()

    /** ROI 별 저신뢰 전환 후보(연속 확인 카운트용): (root → 연속 관측 횟수). */
    private val pendingSwitchByRoi = HashMap<Int, PendingSwitch>()

    /**
     * ROI 별 후보 선택 기억: (root → 유저가 고른 key).
     * "해당 슬롯에서 같은 표시명(=root)이 유지되는 동안" 유효 — root 가 바뀌면 그 root 기억은 자동 무시.
     */
    private val choiceByRoi = HashMap<Int, RememberedChoice>()

    /** ROI 별 수동 핀(수동 검색으로 고정). 존재하면 파이프라인 인식이 이 슬롯을 덮어쓰지 않는다. */
    private val pinByRoi = HashMap<Int, String>()

    /**
     * ROI 하나의 매칭 결과를 액션으로 변환.
     * @param roiIndex ROI 식별자(카드 슬롯). 더블배틀이면 0/1.
     */
    fun decide(roiIndex: Int, result: MatchResult): PipelineAction {
        // 수동 핀이 있으면 인식 결과 무시(유저 고정 유지).
        if (pinByRoi.containsKey(roiIndex)) return PipelineAction.NoChange

        return when (result) {
            is MatchResult.NoMatch -> PipelineAction.KeepCurrent

            is MatchResult.Matched -> {
                val best: Candidate? = result.best
                if (best == null) {
                    PipelineAction.KeepCurrent
                } else {
                    // 후보 2+ 이고 이 슬롯에 같은 root 선택 기억이 있으면 그 key 를 우선.
                    val remembered = choiceByRoi[roiIndex]
                    val chosenKey =
                        if (result.candidates.size > 1 && remembered?.root == result.root) {
                            remembered.key
                        } else {
                            best.key
                        }

                    if (lastKeyByRoi[roiIndex] == chosenKey) {
                        // 직전과 동일 key → 갱신 스킵. 저신뢰 전환 대기중이던 것도 안정화됐으니 정리.
                        pendingSwitchByRoi.remove(roiIndex)
                        PipelineAction.NoChange
                    } else if (!confirmSwitch(roiIndex, result)) {
                        // 저신뢰(약매칭)로 *다른 root* 전환인데 아직 연속 확인 부족 → 카드 유지(깜빡임 방지).
                        PipelineAction.KeepCurrent
                    } else {
                        lastKeyByRoi[roiIndex] = chosenKey
                        lastRootByRoi[roiIndex] = result.root
                        pendingSwitchByRoi.remove(roiIndex)
                        PipelineAction.UpdateCard(
                            roiIndex = roiIndex,
                            key = chosenKey,
                            root = result.root,
                            // 기억된 선택으로 확정됐어도, 후보가 여럿이면 "바꾸기" 진입점은 계속 노출.
                            hasMoreCandidates = result.candidates.size > 1,
                            candidateCount = result.candidates.size,
                        )
                    }
                }
            }
        }
    }

    /**
     * 유저가 후보 선택 시트에서 특정 후보를 골랐을 때 호출.
     * 해당 슬롯에서 같은 root 가 유지되는 동안 이 선택을 기억한다.
     * 다음 인식 사이클에서 카드가 즉시 반영되도록 lastKey 도 무효화한다.
     */
    fun rememberChoice(roiIndex: Int, root: String, key: String) {
        choiceByRoi[roiIndex] = RememberedChoice(root, key)
        // 다음 decide 에서 이 key 로 갱신되도록 lastKey 를 지운다(같은 root 재인식 시 즉시 반영).
        lastKeyByRoi.remove(roiIndex)
    }

    /**
     * 유저가 수동 검색으로 슬롯을 고정(핀). 파이프라인이 덮어쓰지 않게 한다.
     * key 는 표시할 포켓몬. 핀 상태에선 인식 결과가 이 슬롯을 갱신하지 않는다.
     */
    fun pin(roiIndex: Int, key: String) {
        pinByRoi[roiIndex] = key
        lastKeyByRoi[roiIndex] = key
    }

    /** 슬롯 핀 해제. 이후 인식이 다시 이 슬롯을 갱신할 수 있다. */
    fun unpin(roiIndex: Int) {
        pinByRoi.remove(roiIndex)
        // 핀 해제 후 다음 인식이 즉시 반영되도록 lastKey 무효화.
        lastKeyByRoi.remove(roiIndex)
    }

    /** 슬롯이 현재 핀 상태인가. */
    fun isPinned(roiIndex: Int): Boolean = pinByRoi.containsKey(roiIndex)

    /**
     * 저신뢰 전환 히스테리시스 판정: 이 매칭으로 카드를 **교체해도 되는지**.
     *
     *  - 아직 이 슬롯에 표시 중인 카드가 없으면(최초 취득) → 무조건 즉시 허용.
     *  - 표시 중인 root 와 **같은 root** 로의 전환(같은 포켓몬, key 만 다름)은 즉시 허용.
     *  - **다른 root** 로의 전환:
     *      · 고신뢰(editDistance ≤ [CONFIDENT_EDIT_DISTANCE]) → 즉시 허용(정상 이름표 프레임).
     *      · 저신뢰(약매칭) → **연속 [SWITCH_CONFIRM_COUNT] 회** 같은 root 관측 시에만 허용.
     *        단발/2연속 미만 오인식(이펙트·카메라 회전 프레임)은 여기서 걸러진다.
     */
    private fun confirmSwitch(roiIndex: Int, result: MatchResult.Matched): Boolean {
        val currentRoot = lastRootByRoi[roiIndex]
        // 최초 취득 또는 같은 root 유지(폼/기억 key 변경)면 지연 없이 허용.
        if (currentRoot == null || currentRoot == result.root) {
            pendingSwitchByRoi.remove(roiIndex)
            return true
        }
        // 고신뢰 전환은 즉시 허용.
        if (result.editDistance <= CONFIDENT_EDIT_DISTANCE) {
            pendingSwitchByRoi.remove(roiIndex)
            return true
        }
        // 저신뢰 전환 — 연속 관측 카운트 누적.
        val pending = pendingSwitchByRoi[roiIndex]
        val count = if (pending?.root == result.root) pending.count + 1 else 1
        pendingSwitchByRoi[roiIndex] = PendingSwitch(result.root, count)
        return count >= SWITCH_CONFIRM_COUNT
    }

    /** 새 배틀 세션/캡처 재시작 시 상태 초기화. */
    fun reset() {
        lastKeyByRoi.clear()
        lastRootByRoi.clear()
        pendingSwitchByRoi.clear()
        choiceByRoi.clear()
        pinByRoi.clear()
    }

    private data class RememberedChoice(val root: String, val key: String)

    private data class PendingSwitch(val root: String, val count: Int)

    companion object {
        /**
         * 고신뢰 매칭 편집거리 상한. editDistance 가 이 값 이하면 정상 이름표로 보고 즉시 전환.
         * (한글 1자 오인식 `갸→가` 등 실측 저편집거리 = 정상 인식 → 즉시 반영.)
         */
        const val CONFIDENT_EDIT_DISTANCE = 1

        /**
         * 저신뢰(약매칭) *다른 root* 전환을 확정하는 데 필요한 연속 동일 관측 횟수.
         * 이펙트/카메라 연출 프레임의 단발 오인식이 카드를 뒤집는 것을 막는다.
         */
        const val SWITCH_CONFIRM_COUNT = 2
    }
}

/** 파이프라인 판정 결과. */
sealed interface PipelineAction {
    /** 이 ROI 카드를 갱신한다. */
    data class UpdateCard(
        val roiIndex: Int,
        val key: String,
        /** 매칭된 species root(후보 선택 시트가 후보 리스트를 조회할 때 사용). */
        val root: String,
        /** 후보 2+ (후보 선택 UI 대상). 최상위/기억된 선택을 표시하되 "바꾸기" 진입점 노출용. */
        val hasMoreCandidates: Boolean,
        val candidateCount: Int,
    ) : PipelineAction

    /** 매칭 실패 — 기존 카드 유지(지우지 않음). */
    data object KeepCurrent : PipelineAction

    /** 직전과 동일 인식 — UI 갱신 스킵. */
    data object NoChange : PipelineAction
}

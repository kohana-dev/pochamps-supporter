package com.pochamps.supporter.matching

import com.pochamps.supporter.data.Candidate

/**
 * NameMatcher 결과.
 * - Matched: 후보 1개면 바로 표시, 2+면 후보 선택 UI(candidates 는 usage_rank 순 정렬됨).
 * - NoMatch: 인식 실패 → 오버레이 "인식 실패 🔍" + 수동 검색 fallback.
 */
sealed interface MatchResult {

    data class Matched(
        val root: String,
        val candidates: List<Candidate>,
        /** 실제로 매칭된 정규화 키(디버깅/로깅용). */
        val matchedKey: String,
        /** 0 = 완전일치, 1+ = fuzzy 매칭 시 편집거리. */
        val editDistance: Int,
    ) : MatchResult {
        val isUnique: Boolean get() = candidates.size == 1
        val best: Candidate? get() = candidates.firstOrNull()
    }

    data object NoMatch : MatchResult
}

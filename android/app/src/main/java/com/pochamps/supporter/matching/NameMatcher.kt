package com.pochamps.supporter.matching

import com.pochamps.supporter.data.CandidateIndex
import com.pochamps.supporter.data.SpeciesGroup

/**
 * [5] NameMatcher — DESIGN.md 4-4 로직.
 *
 * OCR 결과 문자열 → 정규화 → candidate_index.lookup 으로 species root 해석 →
 * 해당 species 그룹의 후보 리스트를 usage_rank 내림차순으로 반환.
 *
 * 매칭 단계:
 *   1) 정규화(공백/기호 제거 + 소문자) 후 lookup 완전일치 시도(모든 언어).
 *   2) 실패 시 Levenshtein 편집거리로 fuzzy 매칭(최대 허용거리 이내 최소거리 키 선택).
 *   3) root → species 그룹 → 후보를 usage_rank 순 정렬해 반환.
 *
 * ⚠️ Android 의존성 없음(순수 JVM) → Robolectric 없이 유닛 테스트 가능.
 */
class NameMatcher(index: CandidateIndex) {

    /** root -> SpeciesGroup 빠른 조회 맵. */
    private val speciesByRoot: Map<String, SpeciesGroup> =
        index.species.associateBy { it.root }

    /**
     * 정규화된 표시명 -> root 통합 lookup.
     * 원본 lookup 은 언어별로 나뉘어 있지만, 정규화 문자열은 언어 무관하게 유일하다고 보고 하나로 합친다.
     * (충돌 시 먼저 등장한 매핑 유지 — 언어 간 동일 문자열은 같은 root 를 가리키므로 안전)
     */
    private val normalizedToRoot: Map<String, String> = buildMap {
        for ((_, table) in index.lookup) {
            for ((normName, root) in table) {
                putIfAbsent(normalize(normName), root)
            }
        }
    }

    /** fuzzy 매칭 후보 키 배열(미리 정렬해두면 결정적 결과). */
    private val normalizedKeys: List<String> = normalizedToRoot.keys.sorted()

    /**
     * OCR 원문을 후보 결과로 변환.
     * @param ocrText OCR 원본 문자열(노이즈 포함 가능)
     * @param maxEditDistance fuzzy 허용 최대 편집거리(기본 2). 짧은 이름 오검출 방지를 위해
     *        정규화 길이에 비례해 상한을 한 번 더 죈다.
     */
    fun match(ocrText: String, maxEditDistance: Int = 2): MatchResult {
        val norm = normalize(ocrText)
        if (norm.isEmpty()) return MatchResult.NoMatch

        // 1) 완전 일치
        normalizedToRoot[norm]?.let { root ->
            return buildResult(root, norm, 0)
        }

        // 2) fuzzy — 최소 편집거리 후보 탐색
        // 짧은 문자열은 오검출 위험이 크므로 길이 기반으로 허용거리를 죈다.
        val allowed = minOf(maxEditDistance, maxOf(1, norm.length / 3))
        var bestKey: String? = null
        var bestDist = Int.MAX_VALUE
        for (key in normalizedKeys) {
            // 길이 차가 허용거리보다 크면 계산 스킵(가지치기)
            if (kotlin.math.abs(key.length - norm.length) > allowed) continue
            val d = levenshtein(norm, key, allowed)
            if (d < bestDist) {
                bestDist = d
                bestKey = key
                if (d == 1) break // 1글자 오차면 충분히 좋음, 조기 종료
            }
        }
        if (bestKey != null && bestDist <= allowed) {
            val root = normalizedToRoot.getValue(bestKey)
            return buildResult(root, bestKey, bestDist)
        }

        return MatchResult.NoMatch
    }

    private fun buildResult(root: String, matchedKey: String, distance: Int): MatchResult {
        val group = speciesByRoot[root] ?: return MatchResult.NoMatch
        // usage_rank 내림차순(null 은 맨 뒤). 스마트 기본값 = 첫 번째.
        val sorted = group.candidates.sortedByDescending { it.usage_rank ?: -1.0 }
        return MatchResult.Matched(
            root = root,
            candidates = sorted,
            matchedKey = matchedKey,
            editDistance = distance,
        )
    }

    companion object {
        /** 정규화: 공백/구두점/기호 제거 + 소문자. 한글/일본어/중국어/라틴 문자는 보존. */
        fun normalize(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (ch in raw) {
                if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
                // 그 외(공백/·/()/기호 등)는 버린다
            }
            return sb.toString()
        }

        /**
         * Levenshtein 편집거리. maxDistance 상한을 넘으면 조기 중단(maxDistance+1 반환).
         * 두 행 롤링 버퍼로 O(min(m,n)) 공간.
         */
        fun levenshtein(a: String, b: String, maxDistance: Int = Int.MAX_VALUE): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length

            val m = a.length
            val n = b.length
            var prev = IntArray(n + 1) { it }
            var curr = IntArray(n + 1)

            for (i in 1..m) {
                curr[0] = i
                var rowMin = curr[0]
                val ai = a[i - 1]
                for (j in 1..n) {
                    val cost = if (ai == b[j - 1]) 0 else 1
                    curr[j] = minOf(
                        prev[j] + 1,        // 삭제
                        curr[j - 1] + 1,    // 삽입
                        prev[j - 1] + cost, // 치환
                    )
                    if (curr[j] < rowMin) rowMin = curr[j]
                }
                // 행 전체 최소값이 상한을 넘으면 더 볼 필요 없음
                if (rowMin > maxDistance) return maxDistance + 1
                val tmp = prev; prev = curr; curr = tmp
            }
            return prev[n]
        }
    }
}

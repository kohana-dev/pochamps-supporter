package com.pochamps.supporter.data

import com.pochamps.supporter.matching.MatchResult
import com.pochamps.supporter.matching.NameMatcher
import kotlinx.serialization.json.Json

/** 수동 검색 결과 한 건(key + 표시명). */
data class SearchHit(val key: String, val name: String)

/**
 * [6] LocalRepository — 내장 JSON DB 조회 계층.
 *
 * pokedex_db / usage_db / candidate_index 세 파일을 파싱해 보유하고,
 * key 로 포켓몬 조회, slug→다국어 이름 해석, 메가 링크 접근, 사용률 상위 N 기술 조회를 제공한다.
 * NameMatcher 도 여기서 생성해 노출한다.
 *
 * ⚠️ 이 클래스 자체는 Android 의존성이 없다(문자열 파싱만). assets 로드는 [fromJson] 팩토리에
 *    JSON 문자열을 넘겨주는 얇은 Android 어댑터(AssetsPokedexLoader)가 담당한다.
 *    → Repository 로직도 순수 JVM 유닛 테스트 가능.
 */
class PokedexRepository private constructor(
    val pokedex: PokedexDb,
    val usage: UsageDb,
    val candidateIndex: CandidateIndex,
) {
    /** key -> PokemonEntry 조회 맵. */
    private val pokemonByKey: Map<String, PokemonEntry> =
        pokedex.pokemon.associateBy { it.key }

    /** OCR 문자열 매칭기(candidate_index 기반). */
    val nameMatcher: NameMatcher = NameMatcher(candidateIndex)

    // --- 기본 조회 ---

    /** key(예: "garchomp", "mega-garchomp")로 포켓몬 조회. */
    fun pokemonByKey(key: String): PokemonEntry? = pokemonByKey[key]

    /** OCR 원문 → 후보 매칭 결과. */
    fun match(ocrText: String): MatchResult = nameMatcher.match(ocrText)

    /**
     * 여러 OCR 라인 중 "가장 잘 매칭되는" 결과를 고른다(P12 ROI 강건화).
     * 각 라인을 매칭기에 통과시켜, Matched 중 editDistance 가 가장 작은 것을 채택.
     * (동률이면 먼저 등장한 라인 우선.) 전부 NoMatch 면 NoMatch.
     *
     * 이유: ROI 크롭에 이름표 + 인접 UI("MOVE TIME 45" 등)가 함께 들어와도, 사전 매칭이
     * UI 라인을 걸러내고 실제 종족명 라인만 채택하게 한다 → ROI 밴드를 넓혀도 안전.
     */
    fun matchBest(lines: List<String>): MatchResult {
        var best: MatchResult.Matched? = null
        for (line in lines) {
            val m = nameMatcher.match(line)
            if (m is MatchResult.Matched && (best == null || m.editDistance < best!!.editDistance)) {
                best = m
                if (m.editDistance == 0) break // 완전일치면 더 볼 필요 없음.
            }
        }
        return best ?: MatchResult.NoMatch
    }

    // --- slug → 다국어 이름 해석 ---

    /** 타입 slug → 언어별 이름(예: "dragon","ko" -> "드래곤"). */
    fun typeName(slug: String, lang: String): String? =
        pokedex.dict.types[slug]?.names?.get(lang)

    /** 타입 slug → 색상 헥스(오버레이 타입 칩 색). */
    fun typeColor(slug: String): String? = pokedex.dict.types[slug]?.color

    /** 특성 slug → 언어별 이름. */
    fun abilityName(slug: String, lang: String): String? =
        pokedex.dict.abilities[slug]?.names?.get(lang)

    /** 기술 slug → 언어별 이름. */
    fun moveName(slug: String, lang: String): String? =
        pokedex.dict.moves[slug]?.names?.get(lang)

    /** 기술 slug → 사전 상세(타입/위력/PP 등). */
    fun moveInfo(slug: String): MoveInfo? = pokedex.dict.moves[slug]

    // --- 메가 링크 접근 ---

    /** base 포켓몬이 메가 가능한가. */
    fun canMega(key: String): Boolean = pokemonByKey[key]?.can_mega == true

    /**
     * base key → 연결된 메가 레코드 리스트.
     * X/Y 2메가(리자몽/라이츄)면 2개 반환. 메가 불가면 빈 리스트.
     */
    fun megaFormsOf(baseKey: String): List<PokemonEntry> {
        val base = pokemonByKey[baseKey] ?: return emptyList()
        return base.mega_keys.orEmpty().mapNotNull { pokemonByKey[it] }
    }

    /** 메가 레코드 → base 레코드. */
    fun baseFormOf(megaKey: String): PokemonEntry? {
        val mega = pokemonByKey[megaKey] ?: return null
        val baseKey = mega.base_key ?: return null
        return pokemonByKey[baseKey]
    }

    // --- 사용률 조회 ---

    /** key(slug) → 해당 포맷 사용률. */
    fun usageOf(key: String, format: BattleFormat): FormatUsage? =
        usage.usage[key]?.forFormat(format)

    /**
     * 사용률 상위 N개 기술(포맷별).
     * 메가는 movepool 이 base 와 동일하므로, 메가 key 로 물으면 base 사용률을 재사용한다(DESIGN.md 4-5).
     */
    fun topMoves(key: String, format: BattleFormat, limit: Int = 4): List<UsageStat> {
        val lookupKey = pokemonByKey[key]?.let { entry ->
            if (entry.is_mega == true) entry.base_key ?: key else key
        } ?: key
        val fmt = usage.usage[lookupKey]?.forFormat(format) ?: return emptyList()
        // 원본이 이미 pct 내림차순이지만, 방어적으로 다시 정렬 후 상위 N.
        return fmt.moves.sortedByDescending { it.pct ?: -1.0 }.take(limit)
    }

    /**
     * [P32] 아이템 사용률 상위 N(포맷별). 원본이 이미 pct 내림차순이지만 방어적으로 재정렬.
     * 메가 key 로 물으면 base 사용률을 재사용한다(topMoves 와 동일 규칙).
     * ⚠️ 아이템 이름은 9언어 사전이 없어(영문뿐) name 을 그대로 반환한다(UI 에서 영문 표기).
     */
    fun topItems(key: String, format: BattleFormat, limit: Int = 4): List<UsageStat> {
        val lookupKey = pokemonByKey[key]?.let { entry ->
            if (entry.is_mega == true) entry.base_key ?: key else key
        } ?: key
        val fmt = usage.usage[lookupKey]?.forFormat(format) ?: return emptyList()
        return fmt.items.sortedByDescending { it.pct ?: -1.0 }.take(limit)
    }

    /**
     * [P32] 예상 팀원(파트너 사용률 상위 N). 원본 순서(사용률순)를 보존하되 [limit] 만큼 자른다.
     * 메가 key 로 물으면 base 사용률을 재사용한다(topMoves 와 동일 규칙 — 메가는 별도 통계가 없음).
     *
     * 각 팀원의 표시명은 [teammateName] 으로 표시 언어를 해석한다(key 있으면 pokedex 9언어, 없으면 영문 원문).
     */
    fun topTeammates(key: String, format: BattleFormat, limit: Int = 4): List<Teammate> {
        val lookupKey = pokemonByKey[key]?.let { entry ->
            if (entry.is_mega == true) entry.base_key ?: key else key
        } ?: key
        val fmt = usage.usage[lookupKey]?.forFormat(format) ?: return emptyList()
        return fmt.teammates.take(limit)
    }

    /**
     * [P32] 팀원 칩 표시명 해석. key(pokedex join)가 있으면 표시 언어 이름을, 없으면 원문(영문) 그대로.
     * 팀원 탭 시 검색-핀에 쓸 pokedex key 는 [Teammate.key] 를 그대로 사용(없으면 핀 불가).
     */
    fun teammateName(t: Teammate, lang: String): String =
        t.key?.let { pokemonByKey[it]?.names?.get(lang) } ?: t.name

    // --- 수동 검색 fallback (DESIGN.md 5장) ---

    /**
     * 이름 부분일치 검색(현재 언어 기준). OCR 미매칭 시 유저가 직접 찾는 fallback.
     * 메가 폼(is_mega)은 결과에서 제외(base 카드의 메가 토글로 접근하는 게 자연스러움).
     * @param query 검색어(공백/대소문자 무시, 부분일치).
     * @param lang 표시 언어.
     * @param limit 최대 결과 수.
     * @return (key, 표시명) 쌍 리스트. 접두 일치를 먼저, 그다음 부분 일치.
     */
    fun searchByName(query: String, lang: String, limit: Int = 20): List<SearchHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<SearchHit, Int>>() // (hit, rank) rank 0=접두, 1=부분
        for (entry in pokedex.pokemon) {
            if (entry.is_mega == true) continue
            val display = entry.names.get(lang) ?: entry.key
            val norm = display.lowercase()
            val rank = when {
                norm.startsWith(q) -> 0
                norm.contains(q) -> 1
                else -> continue
            }
            hits.add(SearchHit(entry.key, display) to rank)
        }
        return hits.sortedWith(compareBy({ it.second }, { it.first.name.length }))
            .map { it.first }
            .take(limit)
    }

    /** 종족 루트(candidate_index)로 후보 리스트 조회(후보 선택 UI 용). usage_rank 내림차순. */
    fun candidatesOfRoot(root: String): List<Candidate> =
        candidateIndex.species.firstOrNull { it.root == root }
            ?.candidates
            ?.sortedByDescending { it.usage_rank ?: -1.0 }
            ?: emptyList()

    companion object {
        // 여분/미지 필드가 있어도 실패하지 않게 관대하게 파싱.
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /** JSON 원문 3종으로 Repository 생성(순수 JVM — 테스트/Android 공용). */
        fun fromJson(
            pokedexJson: String,
            usageJson: String,
            candidateIndexJson: String,
        ): PokedexRepository = PokedexRepository(
            pokedex = json.decodeFromString(PokedexDb.serializer(), pokedexJson),
            usage = json.decodeFromString(UsageDb.serializer(), usageJson),
            candidateIndex = json.decodeFromString(CandidateIndex.serializer(), candidateIndexJson),
        )
    }
}

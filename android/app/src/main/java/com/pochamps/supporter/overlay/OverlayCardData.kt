package com.pochamps.supporter.overlay

import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.data.DefensiveMatchup
import com.pochamps.supporter.data.PokedexRepository
import com.pochamps.supporter.data.SpeedCalc
import com.pochamps.supporter.data.TypeChart

/**
 * 오버레이 카드가 그릴 표시 데이터(뷰 모델).
 *
 * ⚠️ 순수 JVM — Compose/Android 의존성 없음. Repository 조회 결과를 UI 가 바로 쓸 형태로 평탄화한다.
 *    [fromRepository] 팩토리로 key + 언어 + 포맷을 넣으면 완성된 카드 데이터가 나온다.
 *
 * ## P4: 3단계 점진 공개 + 메가 토글
 *  - 1단계(칩)/2단계(기본 카드): name/typeChips/abilities/topMoves.
 *  - 3단계(확장 패널): [expanded] — 종족값 6스탯 + 전체 기술 + 방어 상성 + 도감번호.
 *  - 메가 토글: base 카드에 [megaForms] 를 담아, UI 가 선택 시 해당 폼 데이터로 스왑해 표시.
 *    (기술/사용률은 base 재사용 — DESIGN.md 4-5.)
 */
data class OverlayCardData(
    /** 표시용 포켓몬 이름(선택 언어). */
    val name: String,
    /** 타입 칩: (표시명, 색상 헥스) 순서쌍. 색상 없으면 null. */
    val typeChips: List<TypeChip>,
    /** 특성 표시명 리스트(선택 언어). */
    val abilities: List<String>,
    /** 주요 기술 4개(사용률 상위) — (표시명, 사용률%). */
    val topMoves: List<MoveLine>,
    /**
     * [P32] 아이템 사용률(사용률 내림차순, 최대 4개). CARD 단계는 상위 1~2개, EXPANDED 는 4개까지 표시.
     * 아이템 이름은 9언어 사전이 없어 **영문 원문 그대로**(usage_db items.name). truncate 는 UI 에서만.
     */
    val topItems: List<MoveLine> = emptyList(),
    /**
     * [P32] ×4 약점 타입(배지 승격용). CARD 단계에 타입칩 옆 작은 경고 배지로 노출한다. 없으면 빈 리스트.
     * EXPANDED 의 전체 방어 상성([ExpandedData.matchups])은 그대로 유지된다(중복이지만 목적이 다름).
     */
    val weak4Badge: List<TypeChip> = emptyList(),
    /** 메가진화 가능 여부(배지/토글 노출용). */
    val canMega: Boolean,
    /** pokedex key(콜백/디버깅용). */
    val key: String,
    /** 3단계 확장 패널 데이터(종족값/전체기술/방어상성/도감번호). null 이면 조립 실패. */
    val expanded: ExpandedData? = null,
    /**
     * 메가 폼 선택지(base 카드에서 메가로 스왑할 때 UI 가 사용).
     * X/Y 2메가면 2개. 각 항목은 이미 조립된 "메가 상태 카드 데이터".
     * 메가 데이터 자체의 [megaForms] 는 비어 있다(재귀 방지).
     */
    val megaForms: List<MegaForm> = emptyList(),
) {
    data class TypeChip(val label: String, val colorHex: String?)
    data class MoveLine(val label: String, val pct: Double?)

    /**
     * 종족값 한 스탯(표시명 + 값 + base 대비 증감 — 메가 시각화용).
     * [shortLabel] 은 가로 한 줄 배치(P30)용 1글자 라벨(H·A·B·C·D·S — 커뮤니티 표준, 언어 중립).
     */
    data class StatLine(
        val label: String,
        val value: Int,
        val delta: Int = 0,
        val shortLabel: String = "",
    )

    /**
     * 방어 상성 한 줄. [bucket] 은 UI 가 언어 리소스로 라벨을 그릴 때 사용(strings.xml).
     * [label] 은 한국어 기본 라벨(로케일 리소스 없는 곳/테스트용 폴백).
     */
    data class MatchupLine(
        val label: String,
        val types: List<TypeChip>,
        val bucket: MatchupBucket = MatchupBucket.WEAK4,
    )

    /** 방어 상성 버킷(UI 라벨 리소스 키). */
    enum class MatchupBucket { WEAK4, WEAK2, RESIST_QUARTER, RESIST_HALF, IMMUNE }

    /**
     * [P32] 스피드 실능치(실속) 범위(Lv50). EXPANDED 종족값 근처에 "최소~최대(+스카프)"로 표시.
     * 값은 [SpeedCalc] 순수 함수 결과(무투자중립 / 풀투자상향 / +스카프).
     */
    data class SpeedRangeLine(val min: Int, val max: Int, val scarf: Int)

    /**
     * [P32] 예상 팀원 칩. [label] 표시명(표시 언어, key 없으면 영문 원문), [key] 는 탭 시 검색-핀 대상 pokedex key.
     * 각 팀원의 첫 타입 색([colorHex])으로 작은 타입색 칩을 그린다(없으면 회색). key 가 null 이면 탭 비활성.
     */
    data class TeammateChip(val label: String, val key: String?, val colorHex: String?)

    /** 3단계 확장 패널 데이터. */
    data class ExpandedData(
        val dexNumber: Int,
        val stats: List<StatLine>,
        val statTotal: Int,
        val statTotalDelta: Int,
        /** 전체 기술(사용률 내림차순) — (표시명, %). */
        val allMoves: List<MoveLine>,
        /** 방어 상성 줄들(약점/반감/무효, 있는 것만). */
        val matchups: List<MatchupLine>,
        /** [P32] 스피드 실속 범위(Lv50). null 이면 표시 안 함. */
        val speedRange: SpeedRangeLine? = null,
        /** [P32] 예상 팀원 칩(상위 3~4). 빈 리스트면 행 미표시. */
        val teammates: List<TeammateChip> = emptyList(),
    )

    /** 메가 폼 하나(세그먼트 토글 라벨 + 스왑될 카드 데이터). */
    data class MegaForm(val label: String, val card: OverlayCardData)

    companion object {
        /**
         * Repository 에서 카드 데이터를 조립한다.
         * @param withMegaForms base 카드면 연결된 메가 폼 데이터도 함께 조립(메가 토글용). 메가 자신엔 false.
         * @return 해당 key 의 포켓몬이 없으면 null.
         */
        fun fromRepository(
            repo: PokedexRepository,
            key: String,
            lang: String,
            format: BattleFormat,
            withMegaForms: Boolean = true,
        ): OverlayCardData? {
            val entry = repo.pokemonByKey(key) ?: return null

            val name = entry.names.get(lang) ?: entry.key

            val typeChips = entry.types.map { typeSlug ->
                TypeChip(
                    label = repo.typeName(typeSlug, lang) ?: typeSlug,
                    colorHex = repo.typeColor(typeSlug),
                )
            }

            val abilities = entry.abilities.map { abilitySlug ->
                repo.abilityName(abilitySlug, lang) ?: abilitySlug
            }

            val topMoves = repo.topMoves(key, format, limit = 4).map { stat ->
                val label = stat.slug?.let { repo.moveName(it, lang) } ?: stat.name
                MoveLine(label = label, pct = stat.pct)
            }

            // [P32] 아이템 사용률 상위 4(CARD=1~2, EXPANDED=4). 이름은 영문 원문(9언어 사전 없음).
            val topItems = repo.topItems(key, format, limit = 4).map { stat ->
                MoveLine(label = stat.name, pct = stat.pct)
            }

            // [P32] ×4 약점 배지(CARD 승격). 현재 폼(메가면 메가 타입)의 방어 상성에서 ×4 만.
            val weak4Badge = TypeChart.defensiveMatchup(entry.types).weak4.map { slug ->
                TypeChip(label = repo.typeName(slug, lang) ?: slug, colorHex = repo.typeColor(slug))
            }

            // 확장 패널: base 종족값과 비교(메가면 base 대비 증감 표시).
            val baseStats = repo.baseFormOf(key)?.base_stats
            val expanded = buildExpanded(repo, entry, lang, format, baseStats)

            // 메가 폼 데이터(base 카드에만). 메가 자신은 재귀 방지로 megaForms 비움.
            val megaForms = if (withMegaForms && entry.can_mega == true) {
                repo.megaFormsOf(key).mapNotNull { mega ->
                    val megaCard = fromRepository(repo, mega.key, lang, format, withMegaForms = false)
                        ?: return@mapNotNull null
                    MegaForm(label = megaFormLabel(mega.key), card = megaCard)
                }
            } else {
                emptyList()
            }

            return OverlayCardData(
                name = name,
                typeChips = typeChips,
                abilities = abilities,
                topMoves = topMoves,
                topItems = topItems,
                weak4Badge = weak4Badge,
                canMega = entry.can_mega == true,
                key = entry.key,
                expanded = expanded,
                megaForms = megaForms,
            )
        }

        /** 확장 패널 조립(종족값 delta / 전체 기술 / 방어 상성 / 도감번호). */
        private fun buildExpanded(
            repo: PokedexRepository,
            entry: com.pochamps.supporter.data.PokemonEntry,
            lang: String,
            format: BattleFormat,
            baseStatsForDelta: com.pochamps.supporter.data.BaseStats?,
        ): ExpandedData {
            val s = entry.base_stats
            val b = baseStatsForDelta
            // shortLabel(H·A·B·C·D·S): 가로 한 줄 배치(P30)용 커뮤니티 표준 1글자 라벨(언어 중립).
            val stats = listOf(
                StatLine("HP", s.hp, (b?.let { s.hp - it.hp }) ?: 0, "H"),
                StatLine("공격", s.atk, (b?.let { s.atk - it.atk }) ?: 0, "A"),
                StatLine("방어", s.def, (b?.let { s.def - it.def }) ?: 0, "B"),
                StatLine("특공", s.spa, (b?.let { s.spa - it.spa }) ?: 0, "C"),
                StatLine("특방", s.spd, (b?.let { s.spd - it.spd }) ?: 0, "D"),
                StatLine("스피드", s.spe, (b?.let { s.spe - it.spe }) ?: 0, "S"),
            )
            val statTotalDelta = b?.let { s.total - it.total } ?: 0

            // 전체 기술(사용률 내림차순). 메가는 base movepool 재사용(topMoves 와 동일 규칙).
            val allMoves = repo.topMoves(entry.key, format, limit = Int.MAX_VALUE).map { stat ->
                val label = stat.slug?.let { repo.moveName(it, lang) } ?: stat.name
                MoveLine(label = label, pct = stat.pct)
            }

            // 방어 상성(고정 타입 상성표) — 현재 폼(메가면 메가 타입)의 방어 상성.
            val matchup = TypeChart.defensiveMatchup(entry.types)
            val matchups = buildMatchupLines(repo, matchup, lang)

            // [P32] 스피드 실속 범위(Lv50) — 현재 폼 종족 스피드 기준(메가면 메가 스피드).
            val sr = SpeedCalc.rangeLv50(s.spe)
            val speedRange = SpeedRangeLine(min = sr.min, max = sr.max, scarf = sr.scarf)

            // [P32] 예상 팀원 상위 4(사용률순). 표시명=표시 언어(key 없으면 영문), 첫 타입 색으로 칩.
            val teammates = repo.topTeammates(entry.key, format, limit = 4).map { tm ->
                val color = tm.key
                    ?.let { repo.pokemonByKey(it) }
                    ?.types?.firstOrNull()
                    ?.let { repo.typeColor(it) }
                TeammateChip(
                    label = repo.teammateName(tm, lang),
                    key = tm.key,
                    colorHex = color,
                )
            }

            return ExpandedData(
                dexNumber = entry.dex,
                stats = stats,
                statTotal = s.total,
                statTotalDelta = statTotalDelta,
                allMoves = allMoves,
                matchups = matchups,
                speedRange = speedRange,
                teammates = teammates,
            )
        }

        /** DefensiveMatchup → 표시 줄(약점/반감/무효, 있는 것만) + 타입 칩 색상 해석. */
        internal fun buildMatchupLines(
            repo: PokedexRepository,
            m: DefensiveMatchup,
            lang: String,
        ): List<MatchupLine> {
            fun chips(slugs: List<String>): List<TypeChip> = slugs.map { slug ->
                TypeChip(repo.typeName(slug, lang) ?: slug, repo.typeColor(slug))
            }
            val lines = mutableListOf<MatchupLine>()
            if (m.weak4.isNotEmpty())
                lines.add(MatchupLine("×4 약점", chips(m.weak4), MatchupBucket.WEAK4))
            if (m.weak2.isNotEmpty())
                lines.add(MatchupLine("×2 약점", chips(m.weak2), MatchupBucket.WEAK2))
            if (m.resistQuarter.isNotEmpty())
                lines.add(MatchupLine("×¼ 반감", chips(m.resistQuarter), MatchupBucket.RESIST_QUARTER))
            if (m.resistHalf.isNotEmpty())
                lines.add(MatchupLine("×½ 반감", chips(m.resistHalf), MatchupBucket.RESIST_HALF))
            if (m.immune.isNotEmpty())
                lines.add(MatchupLine("무효", chips(m.immune), MatchupBucket.IMMUNE))
            return lines
        }

        /** 메가 폼 세그먼트 라벨: X/Y 2메가면 "메가 X"/"메가 Y", 단일이면 "메가". */
        private fun megaFormLabel(megaKey: String): String = when {
            megaKey.endsWith("-x") -> "메가 X"
            megaKey.endsWith("-y") -> "메가 Y"
            else -> "메가"
        }
    }
}

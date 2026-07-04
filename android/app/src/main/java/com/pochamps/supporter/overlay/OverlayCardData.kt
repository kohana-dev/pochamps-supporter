package com.pochamps.supporter.overlay

import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.data.DefensiveMatchup
import com.pochamps.supporter.data.PokedexRepository
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

    /** 종족값 한 스탯(표시명 + 값 + base 대비 증감 — 메가 시각화용). */
    data class StatLine(val label: String, val value: Int, val delta: Int = 0)

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
            val stats = listOf(
                StatLine("HP", s.hp, (b?.let { s.hp - it.hp }) ?: 0),
                StatLine("공격", s.atk, (b?.let { s.atk - it.atk }) ?: 0),
                StatLine("방어", s.def, (b?.let { s.def - it.def }) ?: 0),
                StatLine("특공", s.spa, (b?.let { s.spa - it.spa }) ?: 0),
                StatLine("특방", s.spd, (b?.let { s.spd - it.spd }) ?: 0),
                StatLine("스피드", s.spe, (b?.let { s.spe - it.spe }) ?: 0),
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

            return ExpandedData(
                dexNumber = entry.dex,
                stats = stats,
                statTotal = s.total,
                statTotalDelta = statTotalDelta,
                allMoves = allMoves,
                matchups = matchups,
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

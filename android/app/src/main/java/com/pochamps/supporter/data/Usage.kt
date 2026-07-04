package com.pochamps.supporter.data

import kotlinx.serialization.Serializable

/**
 * usage_db.json 최상위.
 * 구조: { schema_version, source_format, season, formats:[doubles,singles], count, usage:{ slug -> {doubles,singles} } }
 * usage 는 pokedex slug 를 키로 하는 맵이다.
 */
@Serializable
data class UsageDb(
    val schema_version: Int,
    val source_format: String? = null,
    val season: String? = null,
    val formats: List<String> = emptyList(),
    val count: Int = 0,
    val usage: Map<String, UsageEntry> = emptyMap(),
)

/** 한 종의 싱글/더블 사용률. 두 포맷 모두 항상 존재(데이터 확인). */
@Serializable
data class UsageEntry(
    val doubles: FormatUsage? = null,
    val singles: FormatUsage? = null,
) {
    /** 포맷 문자열("doubles"/"singles")로 조회. */
    fun forFormat(format: BattleFormat): FormatUsage? = when (format) {
        BattleFormat.DOUBLES -> doubles
        BattleFormat.SINGLES -> singles
    }
}

/** 배틀 포맷. 포챔스는 더블 중심이지만 싱글 사용률도 데이터에 있다. */
enum class BattleFormat(val slug: String) {
    DOUBLES("doubles"),
    SINGLES("singles");
}

/** 한 포맷의 사용률 집합. 각 항목은 %(pct) 내림차순으로 정렬돼 있다(원본 기준). */
@Serializable
data class FormatUsage(
    val moves: List<UsageStat> = emptyList(),
    val items: List<UsageStat> = emptyList(),
    val abilities: List<UsageStat> = emptyList(),
    val natures: List<UsageStat> = emptyList(),
    val spreads: List<UsageStat> = emptyList(),
    val teammates: List<Teammate> = emptyList(),
)

/**
 * 사용률 통계 한 줄.
 * moves/abilities 는 slug 필드가 있고(pokedex 조인용), items/natures/spreads 는 slug 없음.
 */
@Serializable
data class UsageStat(
    val name: String,
    val pct: Double? = null,
    val slug: String? = null,
)

/** 파트너 포켓몬. key 는 pokedex 조인용(일부 항목엔 없음), pct 는 원본에서 null 인 경우가 많음. */
@Serializable
data class Teammate(
    val name: String,
    val pct: Double? = null,
    val key: String? = null,
)

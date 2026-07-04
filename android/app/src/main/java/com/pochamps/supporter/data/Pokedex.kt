package com.pochamps.supporter.data

import kotlinx.serialization.Serializable

/**
 * pokedex_db.json 최상위.
 * 구조: { schema_version, languages, count, pokemon:[...], dict:{types,abilities,moves} }
 */
@Serializable
data class PokedexDb(
    val schema_version: Int,
    val languages: List<String> = emptyList(),
    val count: Int = 0,
    val pokemon: List<PokemonEntry> = emptyList(),
    val dict: PokedexDict = PokedexDict(),
)

/**
 * 포켓몬 한 종(base 또는 메가/폼) 레코드.
 * 메가 레코드는 dex 가 10000+ 이며 is_mega=true, base_key 로 base 를 가리킨다.
 * base 레코드는 can_mega=true 이면 mega_keys 로 메가 레코드를 가리킨다.
 */
@Serializable
data class PokemonEntry(
    val dex: Int,
    val key: String,
    val generation: Int? = null,
    val names: LocalizedNames = LocalizedNames(),
    val types: List<String> = emptyList(),
    val abilities: List<String> = emptyList(),
    val base_stats: BaseStats = BaseStats(),
    val moves: List<String> = emptyList(),
    // --- 메가 상호 링크 (일부 레코드에만 존재) ---
    val can_mega: Boolean? = null,
    val mega_keys: List<String>? = null,
    val is_mega: Boolean? = null,
    val base_key: String? = null,
)

/** 종족값 6스탯. */
@Serializable
data class BaseStats(
    val hp: Int = 0,
    val atk: Int = 0,
    val def: Int = 0,
    val spa: Int = 0,
    val spd: Int = 0,
    val spe: Int = 0,
) {
    /** 종족값 합. */
    val total: Int get() = hp + atk + def + spa + spd + spe
}

/** 타입/특성/기술 다국어 사전. slug → 항목. */
@Serializable
data class PokedexDict(
    val types: Map<String, TypeInfo> = emptyMap(),
    val abilities: Map<String, DictEntry> = emptyMap(),
    val moves: Map<String, MoveInfo> = emptyMap(),
)

/** 타입 사전 항목: 다국어 이름 + 색상 헥스. */
@Serializable
data class TypeInfo(
    val names: LocalizedNames = LocalizedNames(),
    val color: String? = null,
)

/** 특성 사전 항목: 다국어 이름만. */
@Serializable
data class DictEntry(
    val names: LocalizedNames = LocalizedNames(),
)

/** 기술 사전 항목: 다국어 이름 + 타입/분류/위력/PP/명중 등(선택 필드). */
@Serializable
data class MoveInfo(
    val names: LocalizedNames = LocalizedNames(),
    val type: String? = null,
    val category: String? = null,
    val power: Int? = null,
    val pp: Int? = null,
    val accuracy: Int? = null,
)

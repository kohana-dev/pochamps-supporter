package com.pochamps.supporter.data

import kotlinx.serialization.Serializable

/**
 * candidate_index.json 최상위.
 * 구조: { schema_version, note, species_count, collision_count, species:[...], lookup:{ lang -> {정규화이름 -> root} } }
 * - species: 종족 루트별 후보 그룹(메가 제외).
 * - lookup: 언어별로 "정규화된 표시명 문자열 → species root" 매핑.
 */
@Serializable
data class CandidateIndex(
    val schema_version: Int,
    val note: String? = null,
    val species_count: Int = 0,
    val collision_count: Int = 0,
    val species: List<SpeciesGroup> = emptyList(),
    val lookup: Map<String, Map<String, String>> = emptyMap(),
)

/** 표시명이 충돌할 수 있는 종족 그룹. root 아래 후보가 1개면 바로 확정, 2+면 후보 선택 UI. */
@Serializable
data class SpeciesGroup(
    val root: String,
    val candidates: List<Candidate> = emptyList(),
)

/**
 * 후보 한 종. usage_rank(사용률) 내림차순 정렬에 사용.
 * key 는 pokedex 조인용, types/names 는 후보 선택 UI 즉시 표시용(pokedex 재조회 없이 칩 렌더).
 */
@Serializable
data class Candidate(
    val key: String,
    val usage_rank: Double? = null,
    val types: List<String> = emptyList(),
    val names: LocalizedNames = LocalizedNames(),
)

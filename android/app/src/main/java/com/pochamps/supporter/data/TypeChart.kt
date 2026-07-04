package com.pochamps.supporter.data

/**
 * 표준 18타입 상성표 + 방어 상성 계산(순수 JVM).
 *
 * 포켓몬 타입 상성은 세대가 바뀌어도 고정된 지식이므로 코드 상수로 내장한다(데이터 파일 불필요).
 * 여기서 계산하는 건 **방어측** 상성이다: 방어 포켓몬의 타입 조합(1~2개)에 대해
 * 각 공격 타입이 몇 배로 들어가는지(약점 ×2/×4, 반감 ×0.5/×0.25, 무효 ×0)를 낸다.
 *
 * slug 는 pokedex_db 의 타입 slug 와 동일(모두 소문자 영문 18종).
 *
 * 예) dragon/ground(한카리아스) → ice ×4, fairy/dragon ×2, electric ×0(무효), poison ×0(무효), ...
 */
object TypeChart {

    /** 표준 18타입(pokedex_db dict.types 키와 일치). */
    val TYPES: List<String> = listOf(
        "normal", "fire", "water", "electric", "grass", "ice",
        "fighting", "poison", "ground", "flying", "psychic", "bug",
        "rock", "ghost", "dragon", "dark", "steel", "fairy",
    )

    /**
     * 공격타입 → (방어타입 → 배수). 명시되지 않은 조합은 ×1.0(등배).
     * 아래 표는 세대 9 기준 공식 상성(공격 관점: attacker 가 defender 를 칠 때의 배수).
     */
    private val CHART: Map<String, Map<String, Double>> = mapOf(
        "normal" to mapOf("rock" to 0.5, "ghost" to 0.0, "steel" to 0.5),
        "fire" to mapOf(
            "fire" to 0.5, "water" to 0.5, "grass" to 2.0, "ice" to 2.0,
            "bug" to 2.0, "rock" to 0.5, "dragon" to 0.5, "steel" to 2.0,
        ),
        "water" to mapOf(
            "fire" to 2.0, "water" to 0.5, "grass" to 0.5, "ground" to 2.0,
            "rock" to 2.0, "dragon" to 0.5,
        ),
        "electric" to mapOf(
            "water" to 2.0, "electric" to 0.5, "grass" to 0.5, "ground" to 0.0,
            "flying" to 2.0, "dragon" to 0.5,
        ),
        "grass" to mapOf(
            "fire" to 0.5, "water" to 2.0, "grass" to 0.5, "poison" to 0.5,
            "ground" to 2.0, "flying" to 0.5, "bug" to 0.5, "rock" to 2.0,
            "dragon" to 0.5, "steel" to 0.5,
        ),
        "ice" to mapOf(
            "fire" to 0.5, "water" to 0.5, "grass" to 2.0, "ice" to 0.5,
            "ground" to 2.0, "flying" to 2.0, "dragon" to 2.0, "steel" to 0.5,
        ),
        "fighting" to mapOf(
            "normal" to 2.0, "ice" to 2.0, "poison" to 0.5, "flying" to 0.5,
            "psychic" to 0.5, "bug" to 0.5, "rock" to 2.0, "ghost" to 0.0,
            "dark" to 2.0, "steel" to 2.0, "fairy" to 0.5,
        ),
        "poison" to mapOf(
            "grass" to 2.0, "poison" to 0.5, "ground" to 0.5, "rock" to 0.5,
            "ghost" to 0.5, "steel" to 0.0, "fairy" to 2.0,
        ),
        "ground" to mapOf(
            "fire" to 2.0, "electric" to 2.0, "grass" to 0.5, "poison" to 2.0,
            "flying" to 0.0, "bug" to 0.5, "rock" to 2.0, "steel" to 2.0,
        ),
        "flying" to mapOf(
            "electric" to 0.5, "grass" to 2.0, "fighting" to 2.0, "bug" to 2.0,
            "rock" to 0.5, "steel" to 0.5,
        ),
        "psychic" to mapOf(
            "fighting" to 2.0, "poison" to 2.0, "psychic" to 0.5, "dark" to 0.0,
            "steel" to 0.5,
        ),
        "bug" to mapOf(
            "fire" to 0.5, "grass" to 2.0, "fighting" to 0.5, "poison" to 0.5,
            "flying" to 0.5, "psychic" to 2.0, "ghost" to 0.5, "dark" to 2.0,
            "steel" to 0.5, "fairy" to 0.5,
        ),
        "rock" to mapOf(
            "fire" to 2.0, "ice" to 2.0, "fighting" to 0.5, "ground" to 0.5,
            "flying" to 2.0, "bug" to 2.0, "steel" to 0.5,
        ),
        "ghost" to mapOf(
            "normal" to 0.0, "psychic" to 2.0, "ghost" to 2.0, "dark" to 0.5,
        ),
        "dragon" to mapOf("dragon" to 2.0, "steel" to 0.5, "fairy" to 0.0),
        "dark" to mapOf(
            "fighting" to 0.5, "psychic" to 2.0, "ghost" to 2.0, "dark" to 0.5,
            "fairy" to 0.5,
        ),
        "steel" to mapOf(
            "fire" to 0.5, "water" to 0.5, "electric" to 0.5, "ice" to 2.0,
            "rock" to 2.0, "steel" to 0.5, "fairy" to 2.0,
        ),
        "fairy" to mapOf(
            "fire" to 0.5, "fighting" to 2.0, "poison" to 0.5, "dragon" to 2.0,
            "dark" to 2.0, "steel" to 0.5,
        ),
    )

    /** 공격타입이 단일 방어타입을 칠 때 배수(미지정 = 1.0). */
    fun effectiveness(attackType: String, defendType: String): Double =
        CHART[attackType]?.get(defendType) ?: 1.0

    /**
     * 방어측 타입 조합(1~2개)에 대해, 각 공격 타입의 총 배수를 계산한다.
     * 2타입이면 각 타입 배수를 곱한다(예: dragon×ground 에 ice → 2.0 × 2.0 = 4.0).
     * @return 공격타입 slug → 총 배수(0.0/0.25/0.5/1.0/2.0/4.0).
     */
    fun defensiveMultipliers(defenderTypes: List<String>): Map<String, Double> {
        // 유효(=차트에 있는) 방어 타입만 사용. 알 수 없는 슬러그는 무시(등배 취급).
        val defTypes = defenderTypes.filter { it in CHART.keys }
        return TYPES.associateWith { atk ->
            defTypes.fold(1.0) { acc, def -> acc * effectiveness(atk, def) }
        }
    }

    /**
     * 방어 상성을 오버레이 표시용 버킷으로 분류한다.
     * - weak4/weak2: 약점(×4, ×2)
     * - resist/resistHalf: 반감(×0.25, ×0.5)
     * - immune: 무효(×0)
     * 등배(×1)는 제외(표시 안 함 — 화면 절약).
     */
    fun defensiveMatchup(defenderTypes: List<String>): DefensiveMatchup {
        val mult = defensiveMultipliers(defenderTypes)
        val weak4 = mutableListOf<String>()
        val weak2 = mutableListOf<String>()
        val resistHalf = mutableListOf<String>()
        val resistQuarter = mutableListOf<String>()
        val immune = mutableListOf<String>()
        // 안정적 표시 순서(TYPES 순).
        for (t in TYPES) {
            when (mult[t]) {
                4.0 -> weak4.add(t)
                2.0 -> weak2.add(t)
                0.5 -> resistHalf.add(t)
                0.25 -> resistQuarter.add(t)
                0.0 -> immune.add(t)
                else -> Unit // 1.0 등배는 생략.
            }
        }
        return DefensiveMatchup(
            weak4 = weak4,
            weak2 = weak2,
            resistHalf = resistHalf,
            resistQuarter = resistQuarter,
            immune = immune,
        )
    }
}

/**
 * 방어 상성 분류 결과(오버레이 확장 패널 표시용).
 * 각 리스트는 공격 타입 slug(TYPES 순 정렬).
 */
data class DefensiveMatchup(
    /** ×4 약점. */
    val weak4: List<String>,
    /** ×2 약점. */
    val weak2: List<String>,
    /** ×0.5 반감. */
    val resistHalf: List<String>,
    /** ×0.25 반감. */
    val resistQuarter: List<String>,
    /** ×0 무효. */
    val immune: List<String>,
) {
    /** 표시할 상성이 하나라도 있는가(전부 등배면 false). */
    val isEmpty: Boolean
        get() = weak4.isEmpty() && weak2.isEmpty() &&
            resistHalf.isEmpty() && resistQuarter.isEmpty() && immune.isEmpty()
}

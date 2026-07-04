package com.pochamps.supporter

import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.data.PokedexRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PokedexRepository — 실데이터 로드/조회/메가링크/사용률 검증. */
class PokedexRepositoryTest {

    private val repo: PokedexRepository by lazy { TestData.repository() }

    @Test
    fun load_parsesAllThreeFiles() {
        assertTrue("pokedex 비어있지 않음", repo.pokedex.pokemon.isNotEmpty())
        assertTrue("usage 비어있지 않음", repo.usage.usage.isNotEmpty())
        assertTrue("candidate species 비어있지 않음", repo.candidateIndex.species.isNotEmpty())
        // 스키마 확인
        assertEquals(317, repo.pokedex.count)
        assertEquals(234, repo.usage.count)
    }

    @Test
    fun lookup_garchomp_hasCorrectData() {
        val g = repo.pokemonByKey("garchomp")
        assertNotNull("garchomp 존재", g)
        g!!
        assertEquals(445, g.dex)
        assertEquals("한카리아스", g.names.ko)
        assertEquals(listOf("dragon", "ground"), g.types)
        assertTrue("특성 rough-skin 보유", g.abilities.contains("rough-skin"))
        // 종족값 합 검증(600)
        assertEquals(600, g.base_stats.total)
    }

    @Test
    fun megaLink_garchomp_base_to_mega() {
        assertTrue("garchomp 는 메가 가능", repo.canMega("garchomp"))
        val megas = repo.megaFormsOf("garchomp")
        assertEquals("메가 폼 1개", 1, megas.size)
        assertEquals("mega-garchomp", megas[0].key)
        assertEquals(true, megas[0].is_mega)
    }

    @Test
    fun megaLink_mega_to_base_roundtrip() {
        val base = repo.baseFormOf("mega-garchomp")
        assertNotNull(base)
        assertEquals("garchomp", base!!.key)
        // 왕복: base -> mega -> base
        val megaKey = repo.megaFormsOf("garchomp").first().key
        assertEquals("garchomp", repo.baseFormOf(megaKey)!!.key)
    }

    @Test
    fun megaLink_charizard_hasTwoMegas() {
        // X/Y 2메가 종(DESIGN.md 4-5) — 리자몽
        val megas = repo.megaFormsOf("charizard")
        assertEquals("리자몽은 메가 X/Y 2개", 2, megas.size)
    }

    @Test
    fun typeAndAbility_slugResolution() {
        assertEquals("드래곤", repo.typeName("dragon", "ko"))
        assertNotNull("타입 색상 존재", repo.typeColor("dragon"))
        assertNotNull("특성 이름 해석", repo.abilityName("rough-skin", "ko"))
        assertNotNull("기술 이름 해석", repo.moveName("earthquake", "ko"))
    }

    @Test
    fun topMoves_doubles_returnsSortedByUsage() {
        val moves = repo.topMoves("garchomp", BattleFormat.DOUBLES, limit = 4)
        assertEquals(4, moves.size)
        // DESIGN.md 예시: 더블 상위 = Dragon Claw 89%, Rock Slide 84%...
        assertEquals("dragon-claw", moves[0].slug)
        // 내림차순 검증
        val pcts = moves.map { it.pct ?: -1.0 }
        assertEquals(pcts.sortedDescending(), pcts)
    }

    @Test
    fun topMoves_mega_reusesBaseUsage() {
        // 메가는 movepool 이 base 와 동일 → base 사용률 재사용(DESIGN.md 4-5)
        val baseTop = repo.topMoves("garchomp", BattleFormat.DOUBLES, limit = 4)
        val megaTop = repo.topMoves("mega-garchomp", BattleFormat.DOUBLES, limit = 4)
        assertEquals(baseTop.map { it.slug }, megaTop.map { it.slug })
    }

    @Test
    fun topMoves_singlesDiffersFromDoubles() {
        // 포맷 분리가 실제로 의미 있는지(DESIGN.md 4-3)
        val d = repo.topMoves("garchomp", BattleFormat.DOUBLES, limit = 6).map { it.slug }
        val s = repo.topMoves("garchomp", BattleFormat.SINGLES, limit = 6).map { it.slug }
        assertFalse("싱글/더블 상위 기술 집합이 동일하면 안 됨", d == s)
    }

    /**
     * P6 데모 순환 대상 검증(CaptureService.DEMO_CYCLE 가 의존하는 실데이터 불변식).
     *  - garchomp: 메가 토글 데모가 성립하려면 can_mega 여야 함.
     *  - arcanine: 후보 시트 데모가 성립하려면 root "arcanine" 에 후보가 2개 이상이어야 함
     *    (윈디 / 히스이 윈디). 데이터 갱신으로 이 조건이 깨지면 데모가 무의미해지므로 회귀 방어.
     */
    @Test
    fun demoCycle_targets_areValidAgainstAssets() {
        // garchomp 데모 → 메가 가능해야 메가 세그먼트가 뜬다.
        assertTrue("garchomp 데모는 메가 가능해야 함", repo.canMega("garchomp"))
        assertNotNull("arcanine 표시 대상 존재", repo.pokemonByKey("arcanine"))

        // arcanine 데모 → root "arcanine" 후보 2+ 여야 "바꾸기"(후보 시트)가 뜬다.
        val cands = repo.candidatesOfRoot("arcanine")
        assertTrue(
            "arcanine root 후보는 2개 이상이어야 함(윈디/히스이윈디) — 현재 ${cands.size}개",
            cands.size >= 2,
        )
        // 표시 대상 key 가 실제 후보 목록에 포함되어야 함.
        assertTrue(
            "arcanine 표시 key 가 root 후보에 포함",
            cands.any { it.key == "arcanine" },
        )
    }
}

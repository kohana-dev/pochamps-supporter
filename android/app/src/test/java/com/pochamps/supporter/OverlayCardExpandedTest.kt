package com.pochamps.supporter

import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.overlay.OverlayCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4: 확장 패널 데이터 + 메가 폼 스왑 조립 테스트(실 assets JSON).
 */
class OverlayCardExpandedTest {

    private val repo = TestData.repository()

    @Test
    fun garchomp_확장패널_종족값_상성_기술() {
        val card = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        val ex = card.expanded
        assertNotNull("확장 데이터", ex)
        ex!!

        // 도감번호.
        assertEquals(445, ex.dexNumber)

        // 종족값 6스탯 + 합계 600.
        assertEquals(6, ex.stats.size)
        assertEquals(600, ex.statTotal)
        // base 자신은 증감 0.
        assertTrue(ex.stats.all { it.delta == 0 })
        assertEquals(0, ex.statTotalDelta)

        // 방어 상성: dragon/ground → ice ×4 줄이 있어야 함.
        val weak4Line = ex.matchups.firstOrNull { it.label == "×4 약점" }
        assertNotNull("×4 약점 줄", weak4Line)
        assertTrue("ice ×4", weak4Line!!.types.any { it.label.contains("얼음") || it.label == "ice" })

        // 전체 기술이 topMoves(4개)보다 많거나 같음.
        assertTrue(ex.allMoves.size >= card.topMoves.size)
    }

    @Test
    fun 종족값_가로배치_shortLabel_HABCDS() {
        // P30: 가로 한 줄(H·A·B·C·D·S) 배치용 1글자 라벨이 순서대로 채워지고 값과 대응해야 함.
        val card = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        val ex = card.expanded!!
        assertEquals(listOf("H", "A", "B", "C", "D", "S"), ex.stats.map { it.shortLabel })
        // 긴 라벨(HP/공격/…)도 유지(호환) — shortLabel 은 추가 필드.
        assertEquals("HP", ex.stats[0].label)
        // shortLabel 과 value 의 대응이 유지(H=hp 값). garchomp HP=108.
        assertEquals(108, ex.stats.first { it.shortLabel == "H" }.value)
        // 6칸 합이 합계와 일치.
        assertEquals(ex.statTotal, ex.stats.sumOf { it.value })
    }

    @Test
    fun garchomp_메가폼_타입특성종족값_스왑() {
        val card = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        assertTrue(card.canMega)
        assertEquals("메가 폼 1개", 1, card.megaForms.size)

        val mega = card.megaForms.first().card
        // 메가 종족값 합 700(mega-garchomp), base 대비 +100.
        assertEquals(700, mega.expanded!!.statTotal)
        assertEquals(100, mega.expanded!!.statTotalDelta)
        // 공격 증감 +40 (130→170).
        val atk = mega.expanded!!.stats.first { it.label == "공격" }
        assertEquals(170, atk.value)
        assertEquals(40, atk.delta)

        // 메가 기술은 base 재사용(topMoves 동일 4개).
        assertEquals(card.topMoves.map { it.label }, mega.topMoves.map { it.label })

        // 메가 카드 자신은 재귀 방지로 megaForms 비어 있음.
        assertTrue(mega.megaForms.isEmpty())
    }

    @Test
    fun 리자몽_XY_2메가_세그먼트라벨() {
        val card = OverlayCardData.fromRepository(repo, "charizard", "ko", BattleFormat.DOUBLES)!!
        assertEquals("메가 2폼(X/Y)", 2, card.megaForms.size)
        val labels = card.megaForms.map { it.label }.toSet()
        assertTrue(labels.contains("메가 X"))
        assertTrue(labels.contains("메가 Y"))

        // 메가 X 는 불꽃/드래곤 타입(DESIGN.md 예시).
        val megaX = card.megaForms.first { it.label == "메가 X" }.card
        val typeLabels = megaX.typeChips.map { it.label }
        // 드래곤 타입 칩 존재.
        assertTrue("메가X 드래곤", typeLabels.any { it.contains("드래곤") })
    }

    @Test
    fun 방어상성_버킷_라벨_매핑() {
        // P5: MatchupLine.bucket 이 라벨과 일관되게 채워져야 함(UI 가 언어 리소스로 라벨을 그림).
        val card = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        val ex = card.expanded!!

        // ×4 약점 줄은 bucket=WEAK4.
        val weak4 = ex.matchups.first { it.label == "×4 약점" }
        assertEquals(OverlayCardData.MatchupBucket.WEAK4, weak4.bucket)

        // dragon/ground 는 전기 무효(×0) → IMMUNE 버킷 줄이 있어야 함.
        val immune = ex.matchups.firstOrNull { it.bucket == OverlayCardData.MatchupBucket.IMMUNE }
        assertNotNull("무효 줄(전기)", immune)
        assertEquals("무효", immune!!.label)

        // 모든 줄의 bucket 이 label 과 서로 대응(불일치 없음).
        ex.matchups.forEach { line ->
            val expectedLabel = when (line.bucket) {
                OverlayCardData.MatchupBucket.WEAK4 -> "×4 약점"
                OverlayCardData.MatchupBucket.WEAK2 -> "×2 약점"
                OverlayCardData.MatchupBucket.RESIST_QUARTER -> "×¼ 반감"
                OverlayCardData.MatchupBucket.RESIST_HALF -> "×½ 반감"
                OverlayCardData.MatchupBucket.IMMUNE -> "무효"
            }
            assertEquals(expectedLabel, line.label)
        }
    }

    @Test
    fun 후보리스트_root조회_사용률순() {
        // candidate_index 의 arcanine root 후보(윈디/히스이윈디) usage_rank 내림차순.
        val cands = repo.candidatesOfRoot("arcanine")
        if (cands.size >= 2) {
            val ranks = cands.mapNotNull { it.usage_rank }
            assertTrue("사용률 내림차순", ranks.zipWithNext().all { (a, b) -> a >= b })
        }
    }
}

package com.pochamps.supporter

import com.pochamps.supporter.data.BattleFormat
import com.pochamps.supporter.overlay.OverlayCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 오버레이 카드 뷰모델 조립 로직 테스트(실 assets JSON 사용, 순수 JVM).
 * 데모 카드(garchomp)가 실데이터로 올바르게 채워지는지 검증.
 */
class OverlayCardDataTest {

    private val repo = TestData.repository()

    @Test
    fun garchomp_한국어_더블_카드조립() {
        val card = OverlayCardData.fromRepository(
            repo = repo,
            key = "garchomp",
            lang = "ko",
            format = BattleFormat.DOUBLES,
        )!!

        // 이름(한국어).
        assertEquals("한카리아스", card.name)

        // 타입 칩 2개(드래곤/땅), 각각 색상 헥스 존재.
        assertEquals(2, card.typeChips.size)
        val typeLabels = card.typeChips.map { it.label }.toSet()
        assertTrue("드래곤 타입 칩", typeLabels.contains("드래곤"))
        card.typeChips.forEach { chip ->
            assertTrue("타입 색상 헥스 존재: ${chip.label}", chip.colorHex?.startsWith("#") == true)
        }

        // 특성 최소 1개. slug/이름/설명(P36)이 채워진다.
        assertTrue("특성 존재", card.abilities.isNotEmpty())
        val roughSkin = card.abilities.firstOrNull { it.slug == "rough-skin" }
        assertTrue("까칠한피부 특성 존재", roughSkin != null)
        assertEquals("까칠한피부", roughSkin!!.name)
        assertTrue("특성 효과 설명 존재(탭 가능)", roughSkin.hasDescription)
        assertTrue("설명에 접촉 언급", roughSkin.description!!.contains("접촉"))

        // 주요기술 4개(더블 상위), 사용률 내림차순.
        assertEquals(4, card.topMoves.size)
        val pcts = card.topMoves.mapNotNull { it.pct }
        assertEquals("모든 기술에 사용률", 4, pcts.size)
        assertTrue("사용률 내림차순", pcts.zipWithNext().all { (a, b) -> a >= b })

        // garchomp 는 메가 가능.
        assertTrue("메가 가능 배지", card.canMega)
        assertEquals("garchomp", card.key)
    }

    @Test
    fun 없는키는_null() {
        val card = OverlayCardData.fromRepository(repo, "존재하지않는키", "ko", BattleFormat.DOUBLES)
        assertNull(card)
    }

    // ===== P32: 아이템/스피드/팀원/×4 배지 =====

    @Test
    fun garchomp_아이템사용률_상위조립() {
        val card = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        // 최대 4개, 사용률 내림차순.
        assertTrue("아이템 존재", card.topItems.isNotEmpty())
        assertTrue("아이템 최대 4개", card.topItems.size <= 4)
        // 최상위는 생명의구슬(Life Orb) 57% 대(영문 원문 — 9언어 사전 없음).
        assertEquals("Life Orb", card.topItems.first().label)
        assertEquals(57, card.topItems.first().pct!!.toInt())
        val pcts = card.topItems.mapNotNull { it.pct }
        assertTrue("아이템 사용률 내림차순", pcts.zipWithNext().all { (a, b) -> a >= b })
    }

    @Test
    fun garchomp_스피드실속범위_Lv50() {
        val card = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        val sr = card.expanded!!.speedRange!!
        // 한카리아스 스피드 종족값 102 → 107~169(+스카프 253).
        assertEquals(107, sr.min)
        assertEquals(169, sr.max)
        assertEquals(253, sr.scarf)
    }

    @Test
    fun garchomp_예상팀원_칩조립() {
        val card = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        val mates = card.expanded!!.teammates
        assertTrue("팀원 존재", mates.isNotEmpty())
        assertTrue("팀원 최대 4개", mates.size <= 4)
        // key 있는 팀원은 표시 언어(한국어) 이름으로 해석되고, 탭 시 검색-핀에 쓸 key 를 갖는다.
        val whimsicott = mates.firstOrNull { it.key == "whimsicott" }
        assertTrue("key 있는 팀원 존재(whimsicott)", whimsicott != null)
        // 한국어 이름으로 해석(영문 'Whimsicott' 이 아님).
        assertTrue("팀원 이름 한글화", whimsicott!!.label != "Whimsicott")
    }

    @Test
    fun garchomp_x4약점배지_얼음() {
        val card = OverlayCardData.fromRepository(repo, "garchomp", "ko", BattleFormat.DOUBLES)!!
        // 드래곤/땅 → 얼음 ×4. CARD 승격 배지에 얼음 타입이 있어야 한다.
        assertTrue("×4 배지 존재", card.weak4Badge.isNotEmpty())
        assertTrue("×4 배지에 얼음", card.weak4Badge.any { it.label == "얼음" })
    }

    @Test
    fun x4약점없으면_배지없음() {
        // 이상해꽃(풀/독)은 ×4 약점이 없다(가장 큰 배수가 ×2) → 배지 빈 리스트.
        val card = OverlayCardData.fromRepository(repo, "venusaur", "ko", BattleFormat.DOUBLES)!!
        assertTrue("×4 약점 없으면 배지 비어야", card.weak4Badge.isEmpty())
    }
}

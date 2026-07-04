package com.pochamps.supporter

import com.pochamps.supporter.data.TypeChart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 표준 18타입 방어 상성 계산 테스트(순수 JVM, 고정 지식).
 * DESIGN.md 5장: dragon/ground → ice ×4, electric 무효 등.
 */
class TypeChartTest {

    @Test
    fun 단일타입_배수_기본() {
        // fire → grass 2배, water → fire 2배, normal → ghost 무효.
        assertEquals(2.0, TypeChart.effectiveness("fire", "grass"), 0.0)
        assertEquals(2.0, TypeChart.effectiveness("water", "fire"), 0.0)
        assertEquals(0.0, TypeChart.effectiveness("normal", "ghost"), 0.0)
        // 미지정 조합은 등배.
        assertEquals(1.0, TypeChart.effectiveness("normal", "water"), 0.0)
    }

    @Test
    fun 한카리아스_dragon_ground_방어상성() {
        val m = TypeChart.defensiveMultipliers(listOf("dragon", "ground"))
        // ice: dragon 2 × ground 2 = 4 (×4 약점).
        assertEquals(4.0, m["ice"]!!, 0.0)
        // electric: ground 무효 → 0.
        assertEquals(0.0, m["electric"]!!, 0.0)
        // dragon: dragon 2 × ground 1 = 2.
        assertEquals(2.0, m["dragon"]!!, 0.0)
        // fairy: dragon 2 × ground 1 = 2.
        assertEquals(2.0, m["fairy"]!!, 0.0)
    }

    @Test
    fun 한카리아스_상성분류_버킷() {
        val d = TypeChart.defensiveMatchup(listOf("dragon", "ground"))
        assertTrue("ice ×4", d.weak4.contains("ice"))
        assertTrue("dragon ×2", d.weak2.contains("dragon"))
        assertTrue("fairy ×2", d.weak2.contains("fairy"))
        assertTrue("electric 무효", d.immune.contains("electric"))
        // ×4 는 ×2 버킷에 중복되지 않음.
        assertFalse(d.weak2.contains("ice"))
        assertFalse(d.isEmpty)
    }

    @Test
    fun 무효_이중타입_곱() {
        // 강철/땅(예): poison → steel 무효(0) × ground 0.5 = 0.
        val m = TypeChart.defensiveMultipliers(listOf("steel", "ground"))
        assertEquals(0.0, m["poison"]!!, 0.0)
    }

    @Test
    fun 사반감_025() {
        // 불꽃/땅 방어 조합에 grass: fire 0.5 × ground 2 = 1 (등배 검증).
        // 사반감(0.25) 검증: 물 방어에 fire 0.5, 이건 단일. 이중 grass/water 에 water: grass0.5×water0.5=0.25.
        val m = TypeChart.defensiveMultipliers(listOf("grass", "water"))
        assertEquals(0.25, m["water"]!!, 0.0)
        val d = TypeChart.defensiveMatchup(listOf("grass", "water"))
        assertTrue("water ×0.25", d.resistQuarter.contains("water"))
    }

    @Test
    fun 알수없는타입은_등배취급() {
        // 방어 타입에 잘못된 슬러그가 껴도 등배(1.0)로 무시.
        val m = TypeChart.defensiveMultipliers(listOf("dragon", "unknown-type"))
        assertEquals(2.0, m["ice"]!!, 0.0) // dragon 만 반영(ice 2배), unknown 무시.
    }

    @Test
    fun 전체등배는_isEmpty() {
        // 존재하지 않는 타입만 있으면 전부 등배 → 표시할 상성 없음.
        val d = TypeChart.defensiveMatchup(listOf("nonexistent"))
        assertTrue(d.isEmpty)
    }
}

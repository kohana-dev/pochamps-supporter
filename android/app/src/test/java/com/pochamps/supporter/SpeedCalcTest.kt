package com.pochamps.supporter

import com.pochamps.supporter.data.SpeedCalc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P32] 스피드 실속 범위 계산(순수 JVM) 경계값 테스트.
 *
 * 실능치 공식(Lv50, 비-HP):
 *   min   = (2*base) * 50 / 100 + 5                          (무투자 중립)
 *   maxN  = (2*base + 31 + 63) * 50 / 100 + 5                (풀투자, 성격 전)
 *   max   = floor(maxN * 1.1)                                (상향성격)
 *   scarf = floor(max * 1.5)
 */
class SpeedCalcTest {

    @Test
    fun garchomp_base102_경계값() {
        // 한카리아스 스피드 종족값 102 → 실측 기대값 min 107 / max 169 / scarf 253.
        //  min   = 204*50/100 + 5 = 102 + 5 = 107
        //  maxN  = (204+31+63)*50/100 + 5 = 298*50/100 + 5 = 149 + 5 = 154
        //  max   = floor(154*1.1) = floor(169.4) = 169  ← 커뮤니티 공지값과 일치
        //  scarf = floor(169*1.5) = floor(253.5) = 253
        val r = SpeedCalc.rangeLv50(102)
        assertEquals(107, r.min)
        assertEquals(169, r.max)
        assertEquals(253, r.scarf)
    }

    @Test
    fun base0_방어적계산() {
        // base 0 → min=5, maxN=(0+94)*50/100+5=47+5=52, max=floor(52*1.1)=57, scarf=floor(57*1.5)=85
        val r = SpeedCalc.rangeLv50(0)
        assertEquals(5, r.min)
        assertEquals(57, r.max)
        assertEquals(85, r.scarf)
    }

    @Test
    fun base130_고속포켓몬() {
        // 130(예: 매우 빠른 종). min=(260)*50/100+5=130+5=135.
        //  maxN=(260+94)*50/100+5=354*50/100+5=177+5=182, max=floor(182*1.1)=200, scarf=floor(200*1.5)=300
        val r = SpeedCalc.rangeLv50(130)
        assertEquals(135, r.min)
        assertEquals(200, r.max)
        assertEquals(300, r.scarf)
    }

    @Test
    fun 범위_단조성_min작거나같음max_max작거나같음scarf() {
        for (base in intArrayOf(1, 50, 60, 80, 100, 120, 150)) {
            val r = SpeedCalc.rangeLv50(base)
            assertTrue("min<=max at base=$base", r.min <= r.max)
            assertTrue("max<=scarf at base=$base", r.max <= r.scarf)
        }
    }
}

package com.pochamps.supporter

import com.pochamps.supporter.capture.FrameGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FrameGate 해시/변화감지/스로틀 유닛 테스트(순수 JVM).
 */
class FrameGateTest {

    /** width*height 크기의 단색 ARGB 픽셀 배열. */
    private fun solid(width: Int, height: Int, gray: Int): IntArray {
        val p = 0xFF shl 24 or (gray shl 16) or (gray shl 8) or gray
        return IntArray(width * height) { p }
    }

    @Test
    fun downsample_단색은_같은값() {
        val px = solid(24, 8, 128)
        val sig = FrameGate.downsampleGray(px, 24, 8, targetW = 12, targetH = 4)
        assertEquals(12 * 4, sig.size)
        // 단색이면 모든 셀이 대략 128.
        sig.forEach { assertTrue("cell=$it", kotlin.math.abs(it - 128) <= 1) }
    }

    @Test
    fun diffRatio_동일서명은_0() {
        val a = intArrayOf(10, 20, 30, 40)
        assertEquals(0.0, FrameGate.diffRatio(a, a.copyOf()), 1e-9)
    }

    @Test
    fun diffRatio_전부다르면_1() {
        val a = intArrayOf(0, 0, 0, 0)
        val b = intArrayOf(255, 255, 255, 255)
        assertEquals(1.0, FrameGate.diffRatio(a, b), 1e-9)
    }

    @Test
    fun diffRatio_길이다르면_1() {
        assertEquals(1.0, FrameGate.diffRatio(intArrayOf(1, 2), intArrayOf(1, 2, 3)), 1e-9)
    }

    @Test
    fun diffRatio_톨러런스내_변화는_무시() {
        val a = intArrayOf(100, 100, 100, 100)
        // 20 차이(톨러런스 24 이하) → diff 아님.
        val b = intArrayOf(120, 120, 120, 120)
        assertEquals(0.0, FrameGate.diffRatio(a, b), 1e-9)
    }

    @Test
    fun 최초프레임은_항상통과() {
        val gate = FrameGate(minIntervalMs = 0)
        assertTrue(gate.shouldProcess(0, intArrayOf(1, 2, 3, 4), nowMs = 0))
    }

    @Test
    fun 변화없으면_통과안함() {
        val gate = FrameGate(minIntervalMs = 0)
        val sig = intArrayOf(50, 50, 50, 50)
        assertTrue(gate.shouldProcess(0, sig, nowMs = 0))
        // 같은 서명 → 통과 안 함.
        assertFalse(gate.shouldProcess(0, sig.copyOf(), nowMs = 1000))
    }

    @Test
    fun 변화있고_인터벌지나면_통과() {
        val gate = FrameGate(minIntervalMs = 700, diffThreshold = 0.1)
        val a = IntArray(48) { 0 }
        val b = IntArray(48) { 255 }
        assertTrue(gate.shouldProcess(0, a, nowMs = 0))
        // 완전 변화 + 인터벌(800>700) 경과 → 통과.
        assertTrue(gate.shouldProcess(0, b, nowMs = 800))
    }

    @Test
    fun 변화있어도_인터벌내면_스로틀() {
        val gate = FrameGate(minIntervalMs = 700, diffThreshold = 0.1)
        val a = IntArray(48) { 0 }
        val b = IntArray(48) { 255 }
        assertTrue(gate.shouldProcess(0, a, nowMs = 0))
        // 변화는 있지만 인터벌(300<700) 안 → 억제.
        assertFalse(gate.shouldProcess(0, b, nowMs = 300))
    }

    @Test
    fun ROI별로_독립_상태() {
        val gate = FrameGate(minIntervalMs = 0)
        val sig0 = intArrayOf(1, 1, 1, 1)
        val sig1 = intArrayOf(2, 2, 2, 2)
        // 서로 다른 ROI 는 독립적으로 "최초 프레임" 통과.
        assertTrue(gate.shouldProcess(0, sig0, nowMs = 0))
        assertTrue(gate.shouldProcess(1, sig1, nowMs = 0))
    }

    @Test
    fun reset_후_다시_최초프레임() {
        val gate = FrameGate(minIntervalMs = 0)
        val sig = intArrayOf(9, 9, 9, 9)
        assertTrue(gate.shouldProcess(0, sig, nowMs = 0))
        assertFalse(gate.shouldProcess(0, sig.copyOf(), nowMs = 100))
        gate.reset()
        // reset 후엔 다시 최초 프레임 취급.
        assertTrue(gate.shouldProcess(0, sig.copyOf(), nowMs = 200))
    }
}

package com.pochamps.supporter

import com.pochamps.supporter.ui.OnboardingLogic
import com.pochamps.supporter.ui.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P33: 온보딩 단계 판정(권한상태 → 단계) 순수 로직 검증.
 *
 * 실제 권한 조회(Settings.canDrawOverlays / POST_NOTIFICATIONS)와 UI 는 에뮬 실측이 담당.
 * 여기서는 상태 조합 → 단계 done/active/allReady 매핑만 순수하게 검증한다.
 */
class OnboardingStateTest {

    // ── 완전 미완료(막 설치) ───────────────────────────────────────────────
    @Test
    fun 최초상태_오버레이단계만_활성() {
        val s = OnboardingLogic.compute(
            overlayGranted = false,
            notificationRequired = true,
            notificationGranted = false,
            battleNamesAcknowledged = false,
        )
        assertFalse(s.allReady)
        // 첫 활성 단계는 오버레이.
        assertEquals(OnboardingStep.OVERLAY, s.activeStep)
        // 오버레이만 active, 나머지는 비활성·미완료.
        assertTrue(s.stepState(OnboardingStep.OVERLAY).active)
        assertFalse(s.stepState(OnboardingStep.OVERLAY).done)
        assertFalse(s.stepState(OnboardingStep.NOTIFICATION).active)
        assertFalse(s.stepState(OnboardingStep.BATTLE_NAMES).active)
        assertFalse(s.stepState(OnboardingStep.START).active)
    }

    // ── 순차 진행: 오버레이만 완료 → 알림 단계 활성 ────────────────────────
    @Test
    fun 오버레이완료후_알림단계_활성() {
        val s = OnboardingLogic.compute(
            overlayGranted = true,
            notificationRequired = true,
            notificationGranted = false,
            battleNamesAcknowledged = false,
        )
        assertTrue(s.stepState(OnboardingStep.OVERLAY).done)
        assertFalse(s.stepState(OnboardingStep.OVERLAY).active)
        assertEquals(OnboardingStep.NOTIFICATION, s.activeStep)
        assertTrue(s.stepState(OnboardingStep.NOTIFICATION).active)
        assertFalse(s.allReady)
    }

    // ── 오버레이+알림 완료 → 배틀명 안내 단계 활성 ─────────────────────────
    @Test
    fun 오버레이알림완료후_배틀명단계_활성() {
        val s = OnboardingLogic.compute(
            overlayGranted = true,
            notificationRequired = true,
            notificationGranted = true,
            battleNamesAcknowledged = false,
        )
        assertTrue(s.stepState(OnboardingStep.NOTIFICATION).done)
        assertEquals(OnboardingStep.BATTLE_NAMES, s.activeStep)
        assertTrue(s.stepState(OnboardingStep.BATTLE_NAMES).active)
        assertFalse(s.stepState(OnboardingStep.START).active)
        assertFalse(s.allReady)
    }

    // ── 전부 완료 → START 활성 + allReady ──────────────────────────────────
    @Test
    fun 선행모두완료후_시작단계_활성_및_allReady() {
        val s = OnboardingLogic.compute(
            overlayGranted = true,
            notificationRequired = true,
            notificationGranted = true,
            battleNamesAcknowledged = true,
        )
        assertTrue(s.allReady)
        assertEquals(OnboardingStep.START, s.activeStep)
        assertTrue(s.stepState(OnboardingStep.START).active)
        // START 는 done 개념이 없다(항상 false).
        assertFalse(s.stepState(OnboardingStep.START).done)
    }

    // ── Android 12 이하: 알림 단계 자동 완료(런타임 권한 불필요) ────────────
    @Test
    fun 알림불필요_OS는_알림단계_자동완료() {
        val s = OnboardingLogic.compute(
            overlayGranted = true,
            notificationRequired = false, // Android 12-
            notificationGranted = false,  // 무시돼야 함
            battleNamesAcknowledged = false,
        )
        // 알림 단계는 자동 done → 다음 활성은 배틀명.
        assertTrue(s.stepState(OnboardingStep.NOTIFICATION).done)
        assertEquals(OnboardingStep.BATTLE_NAMES, s.activeStep)
    }

    @Test
    fun 알림불필요_OS_선행완료시_allReady() {
        val s = OnboardingLogic.compute(
            overlayGranted = true,
            notificationRequired = false,
            notificationGranted = false,
            battleNamesAcknowledged = true,
        )
        assertTrue(s.allReady)
        assertEquals(OnboardingStep.START, s.activeStep)
    }

    // ── 활성 단계는 항상 최대 1개(순차성 불변식) ───────────────────────────
    @Test
    fun 활성단계는_항상_최대하나() {
        // 여러 조합을 돌려 active 가 2개 이상 나오지 않음을 보장.
        val combos = listOf(
            listOf(false, false, false),
            listOf(true, false, false),
            listOf(true, true, false),
            listOf(true, true, true),
            listOf(false, true, true), // 오버레이 미완료지만 뒤가 완료 — 여전히 오버레이만 active
        )
        for (c in combos) {
            val s = OnboardingLogic.compute(
                overlayGranted = c[0],
                notificationRequired = true,
                notificationGranted = c[1],
                battleNamesAcknowledged = c[2],
            )
            val activeCount = s.steps.count { it.active }
            assertTrue("active 단계는 최대 1개여야 함: $c → $activeCount", activeCount <= 1)
        }
    }

    @Test
    fun 오버레이미완료면_뒤단계완료여도_오버레이가_활성() {
        // 사용자가 배틀명을 먼저 체크했어도 오버레이가 없으면 오버레이가 우선 활성.
        val s = OnboardingLogic.compute(
            overlayGranted = false,
            notificationRequired = true,
            notificationGranted = true,
            battleNamesAcknowledged = true,
        )
        assertEquals(OnboardingStep.OVERLAY, s.activeStep)
        assertFalse(s.allReady)
    }

    @Test
    fun allReady여도_activeStep은_START() {
        val s = OnboardingLogic.compute(true, true, true, true)
        assertEquals(OnboardingStep.START, s.activeStep)
    }

    @Test
    fun 단계순서는_고정() {
        val s = OnboardingLogic.compute(false, true, false, false)
        assertEquals(
            listOf(
                OnboardingStep.OVERLAY,
                OnboardingStep.NOTIFICATION,
                OnboardingStep.BATTLE_NAMES,
                OnboardingStep.START,
            ),
            s.steps.map { it.step },
        )
        // START active 는 절대 선행 미완료 상태에서 나오지 않는다.
        assertNull(if (s.stepState(OnboardingStep.START).active) OnboardingStep.START else null)
    }
}

package com.pochamps.supporter

import com.pochamps.supporter.capture.SingleAppShareDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P35 리포트2] 단일 앱 공유(부분 캡처) 감지 순수 로직 테스트.
 * onCapturedContentResize 로 통지된 콘텐츠 크기 vs 디스플레이 크기 비교로 부분 캡처를 추정한다.
 */
class SingleAppShareDetectorTest {

    @Test
    fun 전체화면_일치하면_단일앱아님() {
        // 콘텐츠 == 디스플레이 → 전체 화면 공유.
        assertFalse(
            SingleAppShareDetector.looksLikeSingleApp(
                contentWidth = 1280, contentHeight = 720,
                displayWidth = 1280, displayHeight = 720,
            ),
        )
    }

    @Test
    fun 콘텐츠가_확연히_작으면_단일앱추정() {
        // 앱 창(레터박스)로 콘텐츠가 한정 → 부분 캡처 추정.
        assertTrue(
            SingleAppShareDetector.looksLikeSingleApp(
                contentWidth = 960, contentHeight = 540,
                displayWidth = 1280, displayHeight = 720,
            ),
        )
    }

    @Test
    fun 세로만_달라도_단일앱추정() {
        // 가로는 같지만 세로가 크게 다르면(상단 앱바 제외 등) 부분 캡처로 본다.
        assertTrue(
            SingleAppShareDetector.looksLikeSingleApp(
                contentWidth = 1280, contentHeight = 600,
                displayWidth = 1280, displayHeight = 720,
            ),
        )
    }

    @Test
    fun 허용오차_이내_소소한차이는_무시() {
        // 5% 이내 차이(상태바/제스처 영역 등)는 전체 화면으로 본다(오탐 방지).
        assertFalse(
            SingleAppShareDetector.looksLikeSingleApp(
                contentWidth = 1280, contentHeight = 700, // 720 대비 ~2.8% 차이.
                displayWidth = 1280, displayHeight = 720,
            ),
        )
    }

    @Test
    fun 허용오차_경계_초과하면_추정() {
        // 정확히 경계보다 큰 차이(6% > 5%)면 부분 캡처.
        val display = 1000
        val content = 940 // 6% 차이.
        assertTrue(
            SingleAppShareDetector.looksLikeSingleApp(
                contentWidth = 1000, contentHeight = content,
                displayWidth = 1000, displayHeight = display,
                toleranceRatio = 0.05,
            ),
        )
    }

    @Test
    fun 잘못된_크기는_판정불가_false() {
        // 0/음수 크기는 판정 불가 → false(오탐 방지).
        assertFalse(
            SingleAppShareDetector.looksLikeSingleApp(
                contentWidth = 0, contentHeight = 0,
                displayWidth = 1280, displayHeight = 720,
            ),
        )
        assertFalse(
            SingleAppShareDetector.looksLikeSingleApp(
                contentWidth = 1280, contentHeight = 720,
                displayWidth = 0, displayHeight = 0,
            ),
        )
    }

    @Test
    fun 큰허용오차면_웬만한차이_무시() {
        // toleranceRatio 를 크게 주면 큰 차이도 전체 화면으로 취급(파라미터 동작 검증).
        assertFalse(
            SingleAppShareDetector.looksLikeSingleApp(
                contentWidth = 960, contentHeight = 540,
                displayWidth = 1280, displayHeight = 720,
                toleranceRatio = 0.5,
            ),
        )
    }
}

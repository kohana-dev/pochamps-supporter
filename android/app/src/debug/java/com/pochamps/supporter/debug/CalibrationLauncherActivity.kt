package com.pochamps.supporter.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.pochamps.supporter.capture.CaptureService
import com.pochamps.supporter.data.AppSettings

/**
 * P14 E2E 검증 전용(디버그 빌드에만 존재). 릴리즈/메인 흐름 비침투.
 *
 * 설정 화면을 스크롤해 버튼을 탭하는 대신(에뮬 부하 시 SystemUI ANR 로 불안정),
 * ROI 보정 오버레이 진입 / 진단 모드 토글을 **`am start` 로 바로** 트리거한다.
 * 실제 앱 흐름과 **동일한 경로**(CaptureService.calibrateIntent / AppSettings.diagnosticsEnabled)를 사용한다.
 *
 * 실행(디버그 APK, adb):
 *   # ROI 보정 오버레이 열기(설정 "이름 영역 보정" 버튼과 동일 경로):
 *   adb shell am start -n com.pochamps.supporter/.debug.CalibrationLauncherActivity --es cmd calibrate
 *   # 진단 모드 on/off(설정 토글과 동일):
 *   adb shell am start -n com.pochamps.supporter/.debug.CalibrationLauncherActivity --es cmd diag_on
 *   adb shell am start -n com.pochamps.supporter/.debug.CalibrationLauncherActivity --es cmd diag_off
 */
class CalibrationLauncherActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.getStringExtra("cmd")) {
            "calibrate" -> {
                // 설정 "이름 영역 보정" 버튼과 동일: CaptureService 로 보정 오버레이 표시.
                ContextCompat.startForegroundService(this, CaptureService.calibrateIntent(this))
            }
            "diag_on" -> AppSettings(this).diagnosticsEnabled = true
            "diag_off" -> AppSettings(this).diagnosticsEnabled = false
        }
        finish()
    }
}

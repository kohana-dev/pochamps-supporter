package com.pochamps.supporter

import android.app.Application
import com.pochamps.supporter.crash.CrashReporter

/**
 * Application 진입점 — P34에서 전역 로컬 크래시 핸들러를 설치한다.
 *
 * 여기서 하는 일은 [CrashReporter.install] 한 줄뿐이다(SDK 0, 자동 전송 0 원칙 유지).
 * 크래시 저장/공유 로직은 crash/ 패키지(CrashLog 순수 로직 + CrashReporter glue)에 있다.
 */
class SupporterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

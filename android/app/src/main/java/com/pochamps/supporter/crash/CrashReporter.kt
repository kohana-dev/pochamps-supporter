package com.pochamps.supporter.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import com.pochamps.supporter.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 로컬 크래시 리포트 Android glue (P34) — 순수 로직은 [CrashLog].
 *
 * - [install]: Application.onCreate 에서 1회. 기존 default handler 를 감싸(chain) 우리 저장 후
 *   원래 핸들러(시스템 종료 다이얼로그)로 넘긴다 — 표준 UncaughtExceptionHandler 만 사용, SDK 0.
 * - [buildShareIntent]: 설정 "버그 리포트 공유" 가 부르는 ACTION_SEND(text) 인텐트(자동 전송 0).
 *
 * 저장 위치는 filesDir/crash/ (앱 내부, 앱 삭제 시 함께 제거). 전송은 유저가 공유를 고를 때만.
 */
object CrashReporter {

    /**
     * 전역 크래시 핸들러 설치. 예외 발생 시 스택트레이스+버전+기기모델을 로컬 저장 후
     * 이전 핸들러로 위임(설치 안 돼 있으면 프로세스 종료).
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { saveCrash(appContext, throwable) }
            // 원래 핸들러가 있으면 그쪽에 위임(시스템 종료 UX 보존). 없으면 프로세스 강제 종료.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** 예외 한 건을 crash/ 에 저장. 핸들러 내부에서 호출되므로 예외를 다시 던지지 않는다. */
    private fun saveCrash(context: Context, throwable: Throwable): File? {
        val now = System.currentTimeMillis()
        val meta = CrashLog.Meta(
            appVersionName = BuildConfig.APP_VERSION_NAME,
            appVersionCode = BuildConfig.APP_VERSION_CODE,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidRelease = Build.VERSION.RELEASE ?: "?",
            androidSdk = Build.VERSION.SDK_INT,
            timestampMs = now,
            timestampLabel = labelFormat().format(Date(now)),
        )
        return CrashLog.writeReport(context.filesDir, meta, CrashLog.stackTraceString(throwable))
    }

    /** 저장된 크래시 리포트가 하나라도 있는가(재실행 시 안내 게이트). */
    fun hasReports(context: Context): Boolean = CrashLog.hasReports(context.filesDir)

    /** 모든 크래시 리포트 삭제. */
    fun clearReports(context: Context): Boolean = CrashLog.clearReports(context.filesDir)

    /**
     * 최근 크래시 리포트를 공유하는 ACTION_SEND(text/plain) 인텐트.
     * 리포트가 없으면 null(호출부가 "공유할 로그 없음" 안내). chooser 로 감싸 사용 권장.
     */
    fun buildShareIntent(context: Context, subject: String): Intent? {
        val body = CrashLog.buildShareText(context.filesDir) ?: return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
    }

    private fun labelFormat(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
}

package com.pochamps.supporter.crash

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 로컬 크래시 리포트의 순수 로직 (Android 비의존, JVM 테스트 가능) — P34.
 *
 * 원칙(research/crash_reporting.md): **SDK 0, 자동 전송 0**. UncaughtExceptionHandler 가
 * 스택트레이스 + 앱 버전 + 기기 모델을 filesDir/crash/ 에 텍스트로 저장하고, 유저가 설정에서
 * "버그 리포트 공유"(ACTION_SEND)를 누를 때만 밖으로 나간다.
 *
 * 이 오브젝트는 File 만 다루므로(Context 불필요) JVM 단위 테스트로 포맷·로테이션을 검증한다.
 * Android glue(Thread 핸들러 설치·Build.MODEL·ACTION_SEND)는 [CrashReporter] 가 담당한다.
 */
object CrashLog {

    const val CRASH_DIR = "crash"
    /** 파일명 접두사 + 확장자(`crash-<timestamp>.txt`). 정렬·필터에 쓴다. */
    const val FILE_PREFIX = "crash-"
    const val FILE_SUFFIX = ".txt"
    /** 최근 몇 개까지 보관할지(오래된 것부터 삭제). */
    const val MAX_FILES = 5

    /** 리포트 한 건의 메타(포맷/파일명 구성 입력). 순수 값 — 테스트가 그대로 주입. */
    data class Meta(
        val appVersionName: String,
        val appVersionCode: Int,
        val deviceModel: String,
        val androidRelease: String,
        val androidSdk: Int,
        /** 발생 시각(에폭 ms). 파일명·본문에 사용. */
        val timestampMs: Long,
        /** 사람이 읽는 시각 문자열(예: "2026-07-05 23:40:12"). glue 가 로케일 포맷해 넘긴다. */
        val timestampLabel: String,
    )

    /** 저장 파일명(정렬 가능한 timestamp 기반 — 사전순=시간순). */
    fun fileName(timestampMs: Long): String = "$FILE_PREFIX$timestampMs$FILE_SUFFIX"

    /** Throwable 의 전체 스택트레이스를 문자열로. (Android 비의존 — Throwable 표준 API) */
    fun stackTraceString(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }

    /**
     * 사람이·개발자가 읽기 쉬운 리포트 본문. 개인식별정보 없음(모델명은 일반 정보).
     * 헤더(메타) → 스택트레이스 순. 공유 시 이 텍스트가 그대로 나간다.
     */
    fun formatReport(meta: Meta, stackTrace: String): String = buildString {
        appendLine("=== 포챔스 서포터 크래시 리포트 / PokeChamps Supporter crash report ===")
        appendLine("time:        ${meta.timestampLabel}")
        appendLine("app version: ${meta.appVersionName} (build ${meta.appVersionCode})")
        appendLine("device:      ${meta.deviceModel}")
        appendLine("android:     ${meta.androidRelease} (SDK ${meta.androidSdk})")
        appendLine("-----------------------------------------------------------------")
        append(stackTrace.trimEnd())
        appendLine()
    }

    /**
     * 리포트를 crash/ 에 저장하고 최근 [MAX_FILES] 개만 남긴다(오래된 것부터 삭제).
     * 반환: 저장된 파일. 실패 시 예외를 던지지 않고 null(핸들러 안에서 호출되므로 방어적).
     */
    fun writeReport(filesDir: File, meta: Meta, stackTrace: String): File? = runCatching {
        val dir = File(filesDir, CRASH_DIR).apply { mkdirs() }
        val file = File(dir, fileName(meta.timestampMs))
        file.writeText(formatReport(meta, stackTrace))
        rotate(dir, MAX_FILES)
        file
    }.getOrNull()

    /** crash/ 의 리포트 목록(최신순). 없으면 빈 리스트. */
    fun listReports(filesDir: File): List<File> {
        val dir = File(filesDir, CRASH_DIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        }?.sortedByDescending { sortKey(it.name) } ?: emptyList()
    }

    /** 가장 최근 리포트(없으면 null). */
    fun latestReport(filesDir: File): File? = listReports(filesDir).firstOrNull()

    /** 저장된 크래시 리포트가 하나라도 있는가(재실행 시 "지난 세션 오류" 안내 게이트). */
    fun hasReports(filesDir: File): Boolean = listReports(filesDir).isNotEmpty()

    /** 모든 리포트 삭제(유저가 "지웠음" 확인 시). 반환: 삭제 성공 여부. */
    fun clearReports(filesDir: File): Boolean {
        val dir = File(filesDir, CRASH_DIR)
        if (!dir.isDirectory) return true
        return dir.listFiles()?.all { it.delete() } ?: true
    }

    /**
     * 최근 [n] 개를 하나의 공유용 텍스트로 합친다(최신순, 구분선). 공유(ACTION_SEND) 본문.
     * 리포트가 없으면 null.
     */
    fun buildShareText(filesDir: File, n: Int = MAX_FILES): String? {
        val reports = listReports(filesDir).take(n)
        if (reports.isEmpty()) return null
        return reports.joinToString("\n\n\n") { runCatching { it.readText().trimEnd() }.getOrDefault("") }
            .trimEnd() + "\n"
    }

    // ── 내부 ────────────────────────────────────────────────────────────────

    /** 파일명에서 timestamp 정렬키 추출(`crash-<ms>.txt` → ms). 파싱 실패 시 0(맨 뒤). */
    private fun sortKey(name: String): Long =
        name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull() ?: 0L

    /** 디렉터리를 최신 [keep] 개만 남기고 오래된 것부터 삭제. */
    fun rotate(dir: File, keep: Int) {
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
        }?.sortedByDescending { sortKey(it.name) } ?: return
        if (files.size <= keep) return
        files.drop(keep).forEach { it.delete() }
    }
}

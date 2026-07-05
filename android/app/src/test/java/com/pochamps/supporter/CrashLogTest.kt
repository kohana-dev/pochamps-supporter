package com.pochamps.supporter

import com.pochamps.supporter.crash.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 로컬 크래시 리포트의 순수 로직(포맷·로테이션·공유텍스트, P34) — 순수 JVM File 기반. */
class CrashLogTest {

    private fun tempFilesDir(): File =
        File.createTempFile("crashlog", "").let { it.delete(); it.mkdirs(); it }

    private fun meta(ts: Long, label: String = "2026-07-05 23:40:12") = CrashLog.Meta(
        appVersionName = "0.2.0",
        appVersionCode = 21,
        deviceModel = "Samsung Galaxy S23",
        androidRelease = "15",
        androidSdk = 35,
        timestampMs = ts,
        timestampLabel = label,
    )

    @Test fun 리포트_본문에_메타와_스택트레이스가_포함() {
        val body = CrashLog.formatReport(meta(1000), "java.lang.RuntimeException: boom\n\tat Foo.bar(Foo.kt:1)")
        assertTrue(body.contains("0.2.0 (build 21)"))
        assertTrue(body.contains("Samsung Galaxy S23"))
        assertTrue(body.contains("15 (SDK 35)"))
        assertTrue(body.contains("2026-07-05 23:40:12"))
        assertTrue(body.contains("java.lang.RuntimeException: boom"))
        assertTrue(body.contains("Foo.bar(Foo.kt:1)"))
    }

    @Test fun 스택트레이스_문자열화() {
        val trace = CrashLog.stackTraceString(IllegalStateException("nope"))
        assertTrue(trace.contains("IllegalStateException"))
        assertTrue(trace.contains("nope"))
    }

    @Test fun 파일명은_타임스탬프_기반_정렬가능() {
        assertEquals("crash-1000.txt", CrashLog.fileName(1000))
        // 사전순 정렬이 시간순과 일치하도록 순수 숫자 timestamp 사용.
        assertTrue(CrashLog.fileName(2000) > CrashLog.fileName(1000))
    }

    @Test fun 저장_후_목록에_최신순으로_보임() {
        val dir = tempFilesDir()
        assertFalse(CrashLog.hasReports(dir))
        CrashLog.writeReport(dir, meta(1000), "trace-a")
        CrashLog.writeReport(dir, meta(3000), "trace-c")
        CrashLog.writeReport(dir, meta(2000), "trace-b")
        val reports = CrashLog.listReports(dir)
        assertEquals(3, reports.size)
        assertEquals("crash-3000.txt", reports[0].name) // 최신순
        assertEquals("crash-2000.txt", reports[1].name)
        assertEquals("crash-1000.txt", reports[2].name)
        assertTrue(CrashLog.hasReports(dir))
        assertEquals("crash-3000.txt", CrashLog.latestReport(dir)!!.name)
    }

    @Test fun 로테이션은_최근_5개만_유지() {
        val dir = tempFilesDir()
        // 7개 저장 → 최신 5개(3000~7000)만 남고 오래된 2개(1000,2000) 삭제.
        for (i in 1..7) CrashLog.writeReport(dir, meta(i * 1000L), "trace-$i")
        val reports = CrashLog.listReports(dir)
        assertEquals(CrashLog.MAX_FILES, reports.size)
        assertEquals("crash-7000.txt", reports.first().name)
        assertEquals("crash-3000.txt", reports.last().name)
        assertFalse(File(File(dir, CrashLog.CRASH_DIR), "crash-1000.txt").exists())
        assertFalse(File(File(dir, CrashLog.CRASH_DIR), "crash-2000.txt").exists())
    }

    @Test fun 공유텍스트는_최신순으로_합쳐짐() {
        val dir = tempFilesDir()
        CrashLog.writeReport(dir, meta(1000), "OLDEST-TRACE")
        CrashLog.writeReport(dir, meta(2000), "NEWEST-TRACE")
        val share = CrashLog.buildShareText(dir)!!
        val idxNew = share.indexOf("NEWEST-TRACE")
        val idxOld = share.indexOf("OLDEST-TRACE")
        assertTrue(idxNew >= 0 && idxOld >= 0)
        assertTrue("최신 리포트가 먼저 와야 한다", idxNew < idxOld)
    }

    @Test fun 리포트_없으면_공유텍스트_null_이고_hasReports_false() {
        val dir = tempFilesDir()
        assertNull(CrashLog.buildShareText(dir))
        assertNull(CrashLog.latestReport(dir))
        assertFalse(CrashLog.hasReports(dir))
    }

    @Test fun 리포트_전체_삭제() {
        val dir = tempFilesDir()
        CrashLog.writeReport(dir, meta(1000), "x")
        CrashLog.writeReport(dir, meta(2000), "y")
        assertTrue(CrashLog.hasReports(dir))
        assertTrue(CrashLog.clearReports(dir))
        assertFalse(CrashLog.hasReports(dir))
        assertNull(CrashLog.buildShareText(dir))
    }

    @Test fun 빈_디렉터리에서_삭제는_안전() {
        val dir = tempFilesDir()
        assertTrue(CrashLog.clearReports(dir)) // crash/ 없어도 true
    }
}

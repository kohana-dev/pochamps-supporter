package com.pochamps.supporter

import com.pochamps.supporter.data.DbFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream

/** sha256 / gzip / 원자교체 / 손상 폴백(순수 JVM File 기반, P13). */
class DbFilesTest {

    private fun tempFilesDir(): File =
        File.createTempFile("dbfiles", "").let {
            it.delete(); it.mkdirs(); it
        }

    private fun gzip(bytes: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(bytes) }
        return bos.toByteArray()
    }

    @Test fun sha256_알려진값() {
        // echo -n "abc" | sha256sum
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DbFiles.sha256Hex("abc".toByteArray()),
        )
    }

    @Test fun gzip_왕복() {
        val original = """{"hello":"세계"}""".toByteArray()
        val gz = gzip(original)
        assertEquals(original.toList(), DbFiles.gunzip(gz).toList())
    }

    @Test fun 빈_디렉터리는_유효본_없음() {
        val dir = tempFilesDir()
        assertFalse(DbFiles.hasValidDownloadedDb(dir))
        assertNull(DbFiles.downloadedVersion(dir))
        assertNull(DbFiles.readDownloadedJson(dir))
    }

    @Test fun 원자교체_후_유효본_로드() {
        val filesDir = tempFilesDir()
        val tmp = DbFiles.freshTmpDir(filesDir)
        File(tmp, "pokedex_db.json").writeText("""{"p":1}""")
        File(tmp, "usage_db.json").writeText("""{"u":2}""")
        File(tmp, "candidate_index.json").writeText("""{"c":3}""")

        assertTrue(DbFiles.promoteAtomically(filesDir, tmp, "20260705"))
        assertTrue(DbFiles.hasValidDownloadedDb(filesDir))
        assertEquals("20260705", DbFiles.downloadedVersion(filesDir))
        val json = DbFiles.readDownloadedJson(filesDir)
        assertNotNull(json)
        assertEquals("""{"p":1}""", json!!.first)
        // 임시 디렉터리는 정리됨.
        assertFalse(File(filesDir, DbFiles.TMP_DIR).exists())
    }

    @Test fun 원자교체_부분파일이면_실패_폴백유지() {
        val filesDir = tempFilesDir()
        val tmp = DbFiles.freshTmpDir(filesDir)
        // 2종만 있음(candidate_index 누락) → 승격 거부.
        File(tmp, "pokedex_db.json").writeText("x")
        File(tmp, "usage_db.json").writeText("y")
        assertFalse(DbFiles.promoteAtomically(filesDir, tmp, "1"))
        assertFalse(DbFiles.hasValidDownloadedDb(filesDir))
    }

    @Test fun 버전파일_손상시_유효본_아님() {
        val filesDir = tempFilesDir()
        val dir = File(filesDir, DbFiles.DB_DIR).apply { mkdirs() }
        // 3종 JSON 은 있으나 version.txt(커밋 표식) 없음 → 미완료로 간주.
        DbFiles.REQUIRED.forEach { File(dir, it).writeText("{}") }
        assertFalse(DbFiles.hasValidDownloadedDb(filesDir))
    }

    @Test fun 재갱신_새버전_덮어쓰기() {
        val filesDir = tempFilesDir()
        // 1차
        DbFiles.freshTmpDir(filesDir).also { tmp ->
            DbFiles.REQUIRED.forEach { File(tmp, it).writeText("v1") }
            DbFiles.promoteAtomically(filesDir, tmp, "1")
        }
        assertEquals("1", DbFiles.downloadedVersion(filesDir))
        // 2차
        DbFiles.freshTmpDir(filesDir).also { tmp ->
            DbFiles.REQUIRED.forEach { File(tmp, it).writeText("v2") }
            DbFiles.promoteAtomically(filesDir, tmp, "2")
        }
        assertEquals("2", DbFiles.downloadedVersion(filesDir))
        assertEquals("v2", DbFiles.readDownloadedJson(filesDir)!!.first)
    }
}

package com.pochamps.supporter.data

import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * 다운로드본 로컬 저장소 레이아웃 + 검증/원자교체 유틸 (순수 JVM, File 기반).
 *
 * filesDir/db/ 에 다운로드본 3종 + version.txt(현재 dataVersion) 를 둔다.
 * Android Context 없이 File 만 받으므로 JVM 임시디렉터리로 테스트 가능.
 *
 * - sha256Hex: 바이트 검증(manifest 대조).
 * - gunzip: gzip 해제.
 * - version.txt: 현재 유효 다운로드본 버전(없으면 내장본 사용 중).
 * - hasValidDownloadedDb: 3종 전부 존재해야 유효(부분 파일 폴백 안전).
 * - promoteAtomically: 임시 디렉터리 → 실 디렉터리 원자 교체(임시파일 rename).
 */
object DbFiles {

    const val DB_DIR = "db"
    const val TMP_DIR = "db_tmp"
    const val VERSION_FILE = "version.txt"

    val REQUIRED = DbManifest.REQUIRED_FILES

    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    fun gunzip(gz: ByteArray): ByteArray =
        GZIPInputStream(gz.inputStream()).use { it.readBytes() }

    /** 다운로드본이 유효한가: db/ 에 3종 전부 존재 + version.txt 존재. */
    fun hasValidDownloadedDb(filesDir: File): Boolean {
        val dir = File(filesDir, DB_DIR)
        if (!dir.isDirectory) return false
        if (!File(dir, VERSION_FILE).isFile) return false
        return REQUIRED.all { File(dir, it).isFile && File(dir, it).length() > 0 }
    }

    /** 현재 유효 다운로드본 버전. 없으면 null(내장본 사용). */
    fun downloadedVersion(filesDir: File): String? {
        if (!hasValidDownloadedDb(filesDir)) return null
        return runCatching { File(File(filesDir, DB_DIR), VERSION_FILE).readText().trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** 다운로드본 3종의 JSON 문자열(존재·유효 시). 없으면 null → 호출부가 assets 폴백. */
    fun readDownloadedJson(filesDir: File): Triple<String, String, String>? {
        if (!hasValidDownloadedDb(filesDir)) return null
        val dir = File(filesDir, DB_DIR)
        return runCatching {
            Triple(
                File(dir, "pokedex_db.json").readText(),
                File(dir, "usage_db.json").readText(),
                File(dir, "candidate_index.json").readText(),
            )
        }.getOrNull()
    }

    /** 다음 다운로드를 위한 깨끗한 임시 디렉터리 반환(기존 임시 삭제 후 재생성). */
    fun freshTmpDir(filesDir: File): File {
        val tmp = File(filesDir, TMP_DIR)
        tmp.deleteRecursively()
        tmp.mkdirs()
        return tmp
    }

    /**
     * 임시 디렉터리에 다운로드·검증이 끝난 3종을 실 db/ 로 원자 교체.
     * 각 파일을 db/ 로 rename(원자적). version.txt 를 마지막에 써서 "완료 표식"으로 삼는다
     * (버전 파일이 있어야 hasValidDownloadedDb=true → 중간 실패 시 폴백 유지).
     */
    fun promoteAtomically(filesDir: File, tmpDir: File, version: String): Boolean {
        val dir = File(filesDir, DB_DIR)
        // 3종이 임시에 전부 있어야 승격.
        if (!REQUIRED.all { File(tmpDir, it).isFile }) return false
        dir.mkdirs()
        // 기존 버전표식 먼저 제거 → 교체 도중 크래시 나도 "유효" 로 안 보이게.
        File(dir, VERSION_FILE).delete()
        for (name in REQUIRED) {
            val from = File(tmpDir, name)
            val to = File(dir, name)
            to.delete()
            if (!from.renameTo(to)) {
                // rename 실패(교차 파일시스템 등) 시 복사 폴백.
                from.copyTo(to, overwrite = true)
            }
        }
        // 마지막에 버전표식 기록 = 커밋.
        File(dir, VERSION_FILE).writeText(version)
        tmpDir.deleteRecursively()
        return true
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

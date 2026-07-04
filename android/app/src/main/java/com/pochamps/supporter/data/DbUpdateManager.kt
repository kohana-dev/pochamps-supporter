package com.pochamps.supporter.data

import android.content.Context
import android.util.Log
import com.pochamps.supporter.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 원격 데이터 갱신 관리자 (DESIGN.md 4-6, P13).
 *
 * 흐름(수동 버튼 — v0.1 은 자동 백그라운드 체크 없음):
 *   1. base URL(BuildConfig, 미설정 시 비활성) 의 manifest.json 조회
 *   2. dataVersion 비교 → 신규면 3종 .gz 다운로드 (HttpURLConnection, 신규 의존성 없음)
 *   3. gzip 해제 + sha256 검증
 *   4. filesDir/db/ 로 원자 교체(임시디렉터리→rename, version.txt 커밋)
 *   실패 시 조용히 내장/기존본 유지 → 오프라인·장애 안전.
 *
 * 순수 로직(manifest 파싱/버전비교/sha256/원자교체)은 DbManifest·DbFiles(JVM 테스트).
 * 여기서는 네트워크 I/O + 그 조립만 담당.
 */
class DbUpdateManager(
    private val filesDir: File,
    private val baseUrl: String = BuildConfig.DATA_UPDATE_BASE_URL,
) {
    constructor(context: Context) : this(context.filesDir)

    /** 갱신 기능이 켜져 있는가(base URL 설정됨). */
    val isEnabled: Boolean get() = baseUrl.isNotBlank()

    /** 현재 사용 중인 데이터 버전. 다운로드본 있으면 그 버전, 없으면 null(=내장본). */
    fun currentVersion(): String? = DbFiles.downloadedVersion(filesDir)

    sealed interface Result {
        /** 갱신 비활성(base URL 미설정) — 내장본만 사용. */
        object Disabled : Result
        /** 이미 최신(원격 == 로컬). */
        data class UpToDate(val version: String) : Result
        /** 갱신 완료. */
        data class Updated(val from: String?, val to: String) : Result
        /** 실패(네트워크/검증/파싱). 기존본 유지. message 는 UI 문구용. */
        data class Failed(val reason: String) : Result
    }

    /**
     * manifest 조회 → 신규면 다운로드·검증·교체. IO 디스패처에서 실행.
     * 예외를 밖으로 던지지 않는다(항상 Result 로 귀결 → UI 토스트).
     */
    suspend fun checkAndUpdate(): Result = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext Result.Disabled

        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val local = DbFiles.downloadedVersion(filesDir)

        // 1) manifest 조회
        val manifestText = runCatching { httpGetText(base + "manifest.json") }
            .getOrElse {
                Log.i(TAG, "manifest 조회 실패: ${it.message}")
                return@withContext Result.Failed(REASON_NETWORK)
            }
        val manifest = DbManifest.parse(manifestText)
            ?: return@withContext Result.Failed(REASON_MANIFEST)

        if (!manifest.isSchemaSupported) return@withContext Result.Failed(REASON_SCHEMA)
        if (!manifest.hasAllRequiredFiles) return@withContext Result.Failed(REASON_MANIFEST)

        // 2) 버전 비교
        if (!DbManifest.isNewer(manifest.dataVersion, local)) {
            return@withContext Result.UpToDate(manifest.dataVersion)
        }

        // 3) 다운로드 + 해제 + sha256 검증 → 임시 디렉터리
        val tmp = DbFiles.freshTmpDir(filesDir)
        for (name in DbManifest.REQUIRED_FILES) {
            val entry = manifest.fileByName(name)
                ?: return@withContext failCleanup(tmp, REASON_MANIFEST)
            val gz = runCatching { httpGetBytes(base + entry.url) }
                .getOrElse {
                    Log.i(TAG, "다운로드 실패 $name: ${it.message}")
                    return@withContext failCleanup(tmp, REASON_NETWORK)
                }
            val raw = runCatching { DbFiles.gunzip(gz) }
                .getOrElse { return@withContext failCleanup(tmp, REASON_CORRUPT) }
            val actual = DbFiles.sha256Hex(raw)
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                Log.w(TAG, "sha256 불일치 $name: expected=${entry.sha256} actual=$actual")
                return@withContext failCleanup(tmp, REASON_CORRUPT)
            }
            if (raw.size.toLong() != entry.size) {
                return@withContext failCleanup(tmp, REASON_CORRUPT)
            }
            File(tmp, name).writeBytes(raw)
        }

        // 4) 원자 교체
        val promoted = DbFiles.promoteAtomically(filesDir, tmp, manifest.dataVersion)
        if (!promoted) return@withContext Result.Failed(REASON_CORRUPT)
        Log.i(TAG, "데이터 갱신 완료: $local -> ${manifest.dataVersion}")
        Result.Updated(from = local, to = manifest.dataVersion)
    }

    private fun failCleanup(tmp: File, reason: String): Result {
        tmp.deleteRecursively()
        return Result.Failed(reason)
    }

    private fun httpGetText(url: String): String =
        openConnection(url).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun httpGetBytes(url: String): ByteArray =
        openConnection(url).use { it.readBytes() }

    /** HttpURLConnection GET → 스트림. 호출부가 use{} 로 닫는다. */
    private fun openConnection(url: String): java.io.InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept-Encoding", "identity") // .gz 는 우리가 직접 해제
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw java.io.IOException("HTTP $code for $url")
        }
        val stream = conn.inputStream
        // 연결 정리를 스트림 close 에 위임하도록 래핑.
        return object : java.io.FilterInputStream(stream) {
            override fun close() {
                try { super.close() } finally { conn.disconnect() }
            }
        }
    }

    companion object {
        private const val TAG = "DbUpdateManager"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000

        // UI 토스트 매핑용 사유 상수(문자열 리소스 키가 아니라 식별자 — MainActivity 에서 문구 매핑).
        const val REASON_NETWORK = "network"
        const val REASON_MANIFEST = "manifest"
        const val REASON_SCHEMA = "schema"
        const val REASON_CORRUPT = "corrupt"
    }
}

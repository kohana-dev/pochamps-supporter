package com.pochamps.supporter.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 원격 갱신 manifest 모델 + 순수 JVM 로직 (DESIGN.md 4-6).
 *
 * 정적 호스팅(GitHub/Cloudflare Pages)의 manifest.json 을 파싱하고,
 * 내장/다운로드본 버전과 비교해 "갱신 필요 여부"를 판정한다.
 * Android 의존성 없음 → JVM 유닛 테스트 가능. 네트워크/파일 I/O 는 DbUpdateManager 담당.
 *
 * manifest.json 은 data/build_release.py 가 생성한다(스키마 동기화 계약).
 */
@Serializable
data class DbManifest(
    @SerialName("manifestSchema") val manifestSchema: Int = 1,
    // dataVersion 은 정수(42) 또는 날짜스탬프 문자열("20260705") 둘 다 허용 → 문자열로 받는다.
    @SerialName("dataVersion") val dataVersion: String,
    @SerialName("generatedAt") val generatedAt: String? = null,
    @SerialName("files") val files: List<DbFile> = emptyList(),
) {
    /** 이 manifest 가 앱이 아는 스키마와 호환되는가. */
    val isSchemaSupported: Boolean get() = manifestSchema == SUPPORTED_SCHEMA

    /** manifest 가 3종 논리 파일을 모두 담고 있는가(부분 배포 방지). */
    val hasAllRequiredFiles: Boolean
        get() = REQUIRED_FILES.all { req -> files.any { it.name == req } }

    fun fileByName(name: String): DbFile? = files.firstOrNull { it.name == name }

    companion object {
        const val SUPPORTED_SCHEMA = 1

        /** 반드시 존재해야 하는 논리 파일명(앱 assets/db 3종). */
        val REQUIRED_FILES = listOf(
            "pokedex_db.json",
            "usage_db.json",
            "candidate_index.json",
        )

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** manifest.json 문자열 파싱. 실패 시 null(원격 장애·손상 안전). */
        fun parse(text: String): DbManifest? = runCatching {
            json.decodeFromString(serializer(), text)
        }.getOrNull()

        /**
         * 원격 dataVersion 이 로컬(현재 사용 중)보다 새로운가.
         *
         * - 둘 다 순수 정수로 파싱되면 정수 비교(42 > 9 올바르게).
         * - 아니면 문자열 사전순 비교(날짜스탬프 "20260705" 도 사전순=시간순이라 안전).
         * - local 이 null(내장본만, 버전 미상)이면 원격이 있으면 갱신 대상.
         */
        fun isNewer(remote: String, local: String?): Boolean {
            if (local == null) return true
            if (remote == local) return false
            val r = remote.toLongOrNull()
            val l = local.toLongOrNull()
            return if (r != null && l != null) r > l else remote > local
        }
    }
}

@Serializable
data class DbFile(
    @SerialName("name") val name: String,   // 논리 파일명(앱 db/ 에 이 이름으로 저장)
    @SerialName("url") val url: String,     // manifest 기준 상대 경로(gzip)
    @SerialName("sha256") val sha256: String, // 압축 해제 후 원본 sha256
    @SerialName("size") val size: Long,       // 원본(해제 후) 바이트
    @SerialName("gzipSize") val gzipSize: Long = 0,
)

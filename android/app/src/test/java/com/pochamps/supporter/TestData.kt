package com.pochamps.supporter

import com.pochamps.supporter.data.PokedexRepository
import java.io.File

/**
 * 유닛 테스트용 실데이터 로더.
 * assets 의 실제 JSON 3종을 파일시스템에서 직접 읽어 Repository 를 만든다(Android 프레임워크 불필요).
 * 경로는 모듈 루트(app/) 기준의 src/main/assets.
 */
object TestData {

    private val assetsDir: File by lazy {
        // 테스트 워킹디렉토리는 보통 app 모듈 루트. 몇 후보를 시도해 존재하는 곳을 쓴다.
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("assets 디렉토리를 찾지 못함. cwd=${File(".").absolutePath}")
    }

    private fun read(name: String): String =
        File(assetsDir, name).readText(Charsets.UTF_8)

    fun repository(): PokedexRepository = PokedexRepository.fromJson(
        pokedexJson = read("pokedex_db.json"),
        usageJson = read("usage_db.json"),
        candidateIndexJson = read("candidate_index.json"),
    )
}

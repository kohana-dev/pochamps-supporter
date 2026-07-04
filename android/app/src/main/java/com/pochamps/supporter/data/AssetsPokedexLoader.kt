package com.pochamps.supporter.data

import android.content.Context
import android.util.Log

/**
 * JSON 3종을 읽어 [PokedexRepository] 를 만드는 얇은 Android 어댑터.
 * 파싱/로직은 전부 PokedexRepository(순수 JVM) 에 있고, 여기선 파일 읽기만 한다.
 *
 * P13 원격 갱신(DESIGN.md 4-6): filesDir/db/ 에 유효한 다운로드본이 있으면 **우선 로드**,
 * 없거나 손상이면 **assets 내장본으로 폴백**(오프라인·실패 안전 보장).
 */
object AssetsPokedexLoader {

    private const val TAG = "AssetsPokedexLoader"
    private const val POKEDEX = "pokedex_db.json"
    private const val USAGE = "usage_db.json"
    private const val CANDIDATE_INDEX = "candidate_index.json"

    /**
     * 다운로드본(있으면) → 실패 시 assets 로 Repository 생성.
     * I/O 이므로 백그라운드 스레드에서 호출할 것.
     */
    fun load(context: Context): PokedexRepository {
        // 1) 다운로드본 우선(파싱까지 성공해야 채택).
        DbFiles.readDownloadedJson(context.filesDir)?.let { (pokedex, usage, cand) ->
            val repo = runCatching { PokedexRepository.fromJson(pokedex, usage, cand) }
                .onFailure { Log.w(TAG, "다운로드본 파싱 실패 → assets 폴백", it) }
                .getOrNull()
            if (repo != null) {
                Log.i(TAG, "다운로드본 로드(version=${DbFiles.downloadedVersion(context.filesDir)})")
                return repo
            }
        }
        // 2) assets 내장본 폴백.
        return loadFromAssets(context)
    }

    /** assets 내장본만 로드(폴백/명시 호출용). */
    fun loadFromAssets(context: Context): PokedexRepository {
        val assets = context.assets
        val pokedexJson = assets.open(POKEDEX).bufferedReader().use { it.readText() }
        val usageJson = assets.open(USAGE).bufferedReader().use { it.readText() }
        val candidateJson = assets.open(CANDIDATE_INDEX).bufferedReader().use { it.readText() }
        return PokedexRepository.fromJson(pokedexJson, usageJson, candidateJson)
    }
}

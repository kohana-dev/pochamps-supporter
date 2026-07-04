package com.pochamps.supporter

import com.pochamps.supporter.data.DbFiles
import com.pochamps.supporter.data.PokedexRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 다운로드본 우선 로드 + 손상 시 폴백 의미 검증(순수 JVM, P13).
 *
 * AssetsPokedexLoader 는 Android Context 를 요구하므로, 그 폴백 결정 로직의 핵심
 * (다운로드본 JSON 이 유효 파싱되면 채택 / 손상이면 null → 호출부가 assets 폴백)을
 * DbFiles + PokedexRepository 조합으로 재현해 검증한다.
 */
class DbUpdateFallbackTest {

    private fun tempFilesDir(): File =
        File.createTempFile("dbfallback", "").let { it.delete(); it.mkdirs(); it }

    private val assetsDir: File by lazy {
        listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { it.isDirectory }
    }

    @Test fun 유효한_다운로드본은_실제_Repository로_로드된다() {
        val filesDir = tempFilesDir()
        val tmp = DbFiles.freshTmpDir(filesDir)
        // 실 assets JSON 을 다운로드본으로 승격.
        DbFiles.REQUIRED.forEach { name ->
            File(tmp, name).writeText(File(assetsDir, name).readText())
        }
        assertTrue(DbFiles.promoteAtomically(filesDir, tmp, "20260705"))

        val json = DbFiles.readDownloadedJson(filesDir)!!
        val repo = PokedexRepository.fromJson(json.first, json.second, json.third)
        // 로드 성공 = 다운로드본이 실제로 유효한 데이터로 파싱됨(P13 우선 로드 경로).
        assertEquals(445, repo.pokemonByKey("garchomp")!!.dex)
    }

    @Test fun 손상된_다운로드본은_파싱실패_null_이므로_폴백대상() {
        val filesDir = tempFilesDir()
        val tmp = DbFiles.freshTmpDir(filesDir)
        // 3종 파일은 존재하지만 내용이 깨짐 → hasValidDownloadedDb 는 true 지만 파싱은 실패.
        File(tmp, "pokedex_db.json").writeText("{ broken")
        File(tmp, "usage_db.json").writeText("also broken")
        File(tmp, "candidate_index.json").writeText("nope")
        DbFiles.promoteAtomically(filesDir, tmp, "bad")

        val json = DbFiles.readDownloadedJson(filesDir)!!
        val repo = runCatching {
            PokedexRepository.fromJson(json.first, json.second, json.third)
        }.getOrNull()
        // 파싱 실패 → null → AssetsPokedexLoader 가 assets 로 폴백한다(오프라인·손상 안전).
        assertNull(repo)
    }

    @Test fun 다운로드본_없으면_null_로_assets_폴백() {
        val filesDir = tempFilesDir()
        assertNull(DbFiles.readDownloadedJson(filesDir))
    }
}

package com.pochamps.supporter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pochamps.supporter.capture.RoiConfig
import com.pochamps.supporter.capture.RoiCropper
import com.pochamps.supporter.capture.RoiRect
import com.pochamps.supporter.data.AssetsPokedexLoader
import com.pochamps.supporter.data.PokedexRepository
import com.pochamps.supporter.matching.MatchResult
import com.pochamps.supporter.ocr.OcrEngine
import com.pochamps.supporter.ocr.Preprocess
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [P8 · K3] 실배틀 화면 기반 OCR 실측 하네스 (계측 테스트).
 *
 * androidTest/assets/field_samples/ 의 실게임 배틀 스크린샷을 앱 파이프라인
 * (RoiCropper → OcrEngine → NameMatcher/Repository)에 통과시켜:
 *   - 상대 이름표 ROI 크롭이 실좌표에 맞는지(K2 교정 검증),
 *   - ML Kit 온디바이스 OCR 이 종족명을 정확히 읽는지(K3 인식률),
 *   - OCR 호출당 지연(ms)이 실용 범위(100~400ms)인지
 * 를 측정하고 logcat(TAG="OcrFieldTest")으로 표를 출력한다.
 *
 * 실행:
 *   ./gradlew :app:connectedDebugAndroidTest        (에뮬레이터/실기기 필요)
 *   adb logcat -s OcrFieldTest                       (결과 표 확인)
 *
 * ⚠️ ML Kit 온디바이스 모델은 최초 실행 시 Play 서비스에서 다운로드가 필요할 수 있다(온라인).
 *    다운로드 전이면 인식이 실패하므로, 각 recognizer 를 워밍업(재시도)한 뒤 측정한다.
 *
 * ⚠️ 샘플 화면은 웹 녹화에서 추출한 로컬 검증용(재배포 아님). 출처는 field_samples/SOURCES.md.
 *    영어 이름표는 이탤릭/기울임 폰트 + 마젠타 배경이라 OCR 난이도가 있다(실측 대상).
 */
@RunWith(AndroidJUnit4::class)
class OcrFieldTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    // 샘플/에셋은 테스트 APK 쪽 context 에 있다.
    private val testCtx get() = InstrumentationRegistry.getInstrumentation().context

    private val TAG = "OcrFieldTest"

    /**
     * 한 케이스 = 이미지 + 언어 + ROI설정 + 기대되는 종족명들(정답).
     */
    private data class Case(
        val asset: String,
        val lang: String,
        val roiConfig: RoiConfig,
        val expected: List<String>, // 소문자/정규화 비교
    )

    private val cases = listOf(
        Case(
            "field_samples/en_single_hippowdon.jpg", "en",
            RoiConfig.DEFAULT_LANDSCAPE_SINGLE, listOf("hippowdon"),
        ),
        Case(
            "field_samples/en_single_hippowdon2.jpg", "en",
            RoiConfig.DEFAULT_LANDSCAPE_SINGLE, listOf("hippowdon"),
        ),
        Case(
            "field_samples/en_doubles_typhlosion_charizard.jpg", "en",
            RoiConfig.DEFAULT_LANDSCAPE_DOUBLES, listOf("typhlosion", "charizard"),
        ),
        Case(
            "field_samples/en_doubles_typhlosion_torkoal.jpg", "en",
            RoiConfig.DEFAULT_LANDSCAPE_DOUBLES, listOf("typhlosion", "torkoal"),
        ),
        Case(
            "field_samples/ko_single_gyarados.jpg", "ko",
            RoiConfig.DEFAULT_LANDSCAPE_SINGLE, listOf("갸라도스"),
        ),
        // P12: 실배틀 영상(DM Gaming) 프레임 2장 추가. Sylveon 은 P11 에서 실캡처 경로 미인식이던 케이스.
        Case(
            "field_samples/real_sylveon_battle.jpg", "en",
            RoiConfig.DEFAULT_LANDSCAPE_SINGLE, listOf("sylveon"),
        ),
        Case(
            "field_samples/real_hippowdon_moveselect.jpg", "en",
            RoiConfig.DEFAULT_LANDSCAPE_SINGLE, listOf("hippowdon"),
        ),
    )

    @Test
    fun k3_ocr_실측_field_samples() = runBlocking {
        runPass(Preprocess.NONE, "기본(전처리 없음)")
    }

    /**
     * [P9] 전처리 비교 — GRAYSCALE_CONTRAST 로 미인식 프레임(예: hippowdon2 'vopmoddy')이
     * 복구되는지 실측. NONE 대비 성공 ROI 가 줄지 않아야(회귀 방지) 한다.
     */
    @Test
    fun k3_ocr_전처리_grayscale_contrast() = runBlocking {
        runPass(Preprocess.GRAYSCALE_CONTRAST, "전처리=GRAYSCALE_CONTRAST")
    }

    /**
     * [P9 진단] 미인식 프레임(hippowdon2)의 원인 규명 — ROI 위치/전처리 변주를 격자로 실측.
     * 'vopmoddy' 오인식이 (a)ROI 하단 클리핑 때문인지 (b)저대비 때문인지 데이터로 판정.
     */
    @Test
    fun k3_diag_hippowdon2_roi_variants() = runBlocking {
        val bmp = loadAsset("field_samples/en_single_hippowdon2.jpg")!!
        // (label, ROI). 기본 SINGLE 대비 하단 확장/아래 이동 변주.
        val variants = listOf(
            "default(0.70,0.02,0.92,0.17)" to RoiRect(0.70, 0.02, 0.92, 0.17),
            "lower(0.70,0.04,0.92,0.20)" to RoiRect(0.70, 0.04, 0.92, 0.20),
            "taller(0.70,0.02,0.94,0.22)" to RoiRect(0.70, 0.02, 0.94, 0.22),
            "downshift(0.70,0.06,0.94,0.22)" to RoiRect(0.70, 0.06, 0.94, 0.22),
        )
        Log.i(TAG, "===== P9 진단: hippowdon2 ROI×전처리 격자 =====")
        for (pp in listOf(Preprocess.NONE, Preprocess.GRAYSCALE_CONTRAST)) {
            val ocr = OcrEngine("en", pp)
            warmup(ocr, bmp)
            for ((vlabel, roi) in variants) {
                val cropper = RoiCropper()
                val crop = cropper.crop(bmp, roi)
                val line = crop?.let { runCatching { ocr.recognizeBestLine(it) }.getOrNull() }
                crop?.recycle()
                Log.i(TAG, "  pp=$pp | $vlabel | '${line ?: "-"}'")
            }
            ocr.close()
        }
        bmp.recycle()
    }

    /**
     * [P12 진단] 실배틀 프레임(Sylveon/Hippowdon)의 SINGLE ROI 재현 + 세로밴드 확장 변주 격자.
     * P11 에서 같은 영상 Hippowdon 은 editDist 0 인식, Sylveon 프레임은 미인식(레터박스 y 편차 추정).
     * 현재 SINGLE ROI(0.70,0.02,0.94,0.22) 와 세로밴드 확장안을 실측 비교해 대책을 데이터로 정한다.
     */
    @Test
    fun p12_diag_real_battle_letterbox_variants() = runBlocking {
        val samples = listOf(
            "field_samples/real_sylveon_battle.jpg" to "sylveon",
            "field_samples/real_hippowdon_moveselect.jpg" to "hippowdon",
        )
        // (label, ROI). 현재 SINGLE + 세로밴드 확장(위/아래) 변주.
        val variants = listOf(
            "current(0.70,0.02,0.94,0.22)" to RoiRect(0.70, 0.02, 0.94, 0.22),
            "tallerBottom(0.70,0.02,0.94,0.30)" to RoiRect(0.70, 0.02, 0.94, 0.30),
            "wideBand(0.68,0.02,0.98,0.30)" to RoiRect(0.68, 0.02, 0.98, 0.30),
            "wideBandTall(0.68,0.02,1.00,0.34)" to RoiRect(0.68, 0.02, 1.00, 0.34),
        )
        val repo: PokedexRepository = AssetsPokedexLoader.load(ctx)
        Log.i(TAG, "===== P12 진단: 실배틀 레터박스 ROI 격자 =====")
        for ((asset, expect) in samples) {
            val bmp = loadAsset(asset) ?: run {
                Log.w(TAG, "$asset 로드 실패"); return@runBlocking
            }
            for (pp in listOf(Preprocess.NONE, Preprocess.GRAYSCALE_CONTRAST)) {
                val ocr = OcrEngine("en", pp)
                warmup(ocr, bmp)
                for ((vlabel, roi) in variants) {
                    val crop = RoiCropper().crop(bmp, roi)
                    // P12: 다중 라인 매칭(matchBest) — ROI 를 넓혀 인접 UI 텍스트가 들어와도 종족명만 채택되는지 검증.
                    val lines = crop?.let { runCatching { ocr.recognizeAllLines(it) }.getOrNull() } ?: emptyList()
                    crop?.recycle()
                    val match = repo.matchBest(lines)
                    val root = (match as? MatchResult.Matched)?.root
                    val dist = (match as? MatchResult.Matched)?.editDistance ?: -1
                    val ok = root != null && normalizeEq(root, expect)
                    Log.i(TAG, "  [$expect] pp=$pp | $vlabel | lines=$lines | root=${root ?: "-"} d=$dist | ${if (ok) "OK" else "MISS"}")
                }
                ocr.close()
            }
            bmp.recycle()
        }
    }

    /**
     * [P20] 싱글/더블 형식 전환 실측 — 형식별 ROI(밴드 수)와 사용률(싱글 vs 더블 메타)이 함께 바뀌는지.
     *
     * (a) 싱글 샘플(hippowdon/gyarados)이 **싱글 활성 ROI(1밴드, activeDefault(SINGLES))** 로 인식되는지.
     * (b) 더블 샘플이 **더블 활성 ROI(2밴드)** 로 2종 인식되는지.
     * (c) 같은 포켓몬(garchomp)의 주요 기술 목록이 형식에 따라 다른지(싱글 메타 vs 더블 메타) —
     *     OverlayCardData.fromRepository(format=…) 가 형식 사용률을 반영하는지 실측.
     * logcat(TAG="OcrFieldTest")으로 표 출력.
     */
    @Test
    fun p20_singles_doubles_형식전환_실측(): Unit = runBlocking {
        val repo: PokedexRepository = AssetsPokedexLoader.load(ctx)
        val cropper = RoiCropper()
        Log.i(TAG, "===== P20: 싱글/더블 형식 전환 실측 =====")

        // (a) 싱글 활성 ROI 로 싱글 샘플 인식.
        val singleCfg = RoiConfig.activeDefault(com.pochamps.supporter.data.BattleFormat.SINGLES)
        assertTrue("싱글 활성 ROI 는 1밴드여야", singleCfg.rois.size == 1)
        val singleCases = listOf(
            Triple("field_samples/en_single_hippowdon.jpg", "en", "hippowdon"),
            Triple("field_samples/ko_single_gyarados.jpg", "ko", "갸라도스"),
        )
        var singleOk = 0
        for ((asset, lang, expect) in singleCases) {
            val bmp = loadAsset(asset)
            if (bmp == null) { Log.w(TAG, "$asset 로드 실패"); continue }
            val ocr = OcrEngine(lang, Preprocess.NONE)
            warmup(ocr, bmp)
            val crops = cropper.cropAll(bmp, singleCfg)
            val lines = crops.flatMap {
                val l = runCatching { ocr.recognizeAllLines(it.bitmap) }.getOrNull() ?: emptyList()
                it.bitmap.recycle(); l
            }
            val match = repo.matchBest(lines)
            val root = (match as? MatchResult.Matched)?.root
            val names = (match as? MatchResult.Matched)?.candidates?.mapNotNull { it.names.get(lang) } ?: emptyList()
            val ok = names.any { normalizeEq(it, expect) }
            if (ok) singleOk++
            Log.i(TAG, "  (a) single ${short(asset)} bands=${singleCfg.rois.size} lines=$lines root=${root ?: "-"} ${if (ok) "OK" else "MISS"}")
            ocr.close(); bmp.recycle()
        }

        // (b) 더블 활성 ROI(2밴드)로 더블 샘플 2종 인식.
        val doublesCfg = RoiConfig.activeDefault(com.pochamps.supporter.data.BattleFormat.DOUBLES)
        assertTrue("더블 활성 ROI 는 2밴드여야", doublesCfg.rois.size == 2)
        val dblBmp = loadAsset("field_samples/en_doubles_typhlosion_charizard.jpg")
        var doublesMatched = emptySet<String>()
        if (dblBmp != null) {
            val ocr = OcrEngine("en", Preprocess.NONE)
            warmup(ocr, dblBmp)
            val matched = mutableSetOf<String>()
            for (cr in cropper.cropAll(dblBmp, doublesCfg)) {
                val lines = runCatching { ocr.recognizeAllLines(cr.bitmap) }.getOrNull() ?: emptyList()
                cr.bitmap.recycle()
                (repo.matchBest(lines) as? MatchResult.Matched)?.root?.let { matched += it }
            }
            doublesMatched = matched
            Log.i(TAG, "  (b) doubles bands=${doublesCfg.rois.size} matched=$matched")
            ocr.close(); dblBmp.recycle()
        }

        // (c) 형식별 사용률 차이(garchomp): 싱글 vs 더블 주요기술 목록이 달라야.
        val dbl = com.pochamps.supporter.overlay.OverlayCardData
            .fromRepository(repo, "garchomp", "en", com.pochamps.supporter.data.BattleFormat.DOUBLES)
        val sgl = com.pochamps.supporter.overlay.OverlayCardData
            .fromRepository(repo, "garchomp", "en", com.pochamps.supporter.data.BattleFormat.SINGLES)
        val dblMoves = dbl?.topMoves?.map { it.label } ?: emptyList()
        val sglMoves = sgl?.topMoves?.map { it.label } ?: emptyList()
        Log.i(TAG, "  (c) garchomp doubles moves=$dblMoves")
        Log.i(TAG, "  (c) garchomp singles moves=$sglMoves")

        // 판정.
        assertTrue("싱글 샘플 최소 1종 인식(싱글 ROI)", singleOk >= 1)
        assertTrue("더블 샘플 2밴드로 2종 인식", doublesMatched.size >= 2)
        assertTrue("garchomp 주요기술이 형식별로 달라야(싱글 vs 더블 메타)", dblMoves != sglMoves && dblMoves.isNotEmpty() && sglMoves.isNotEmpty())
        Log.i(TAG, "===== P20 실측 완료: single=$singleOk doubles=${doublesMatched.size} 사용률차이=${dblMoves != sglMoves} =====")
    }

    private suspend fun runPass(preprocess: Preprocess, label: String) {
        val repo: PokedexRepository = AssetsPokedexLoader.load(ctx)
        val cropper = RoiCropper() // 기본 2x 업스케일

        Log.i(TAG, "==================== P9 K3 OCR 실측 시작 [$label] ====================")
        Log.i(TAG, "image | roi# | lang | OCR라인 | 매칭root | editDist | 지연ms | 판정")

        var totalRoi = 0
        var success = 0
        val latencies = mutableListOf<Long>()

        for (case in cases) {
            val bmp = loadAsset(case.asset) ?: run {
                Log.w(TAG, "${case.asset}: 로드 실패(에셋 없음?)")
                assertTrue("에셋 로드 실패: ${case.asset}", false)
                return
            }
            val ocr = OcrEngine(case.lang, preprocess)
            // 모델 다운로드 대비 워밍업(전체 프레임 1회 인식 시도, 실패 무시).
            warmup(ocr, bmp)

            val crops = cropper.cropAll(bmp, case.roiConfig)
            // 크롭이 하나도 없으면 fallback(상단 절반)도 시도.
            val effectiveCrops = crops.ifEmpty { listOfNotNull(cropper.cropFullTopHalf(bmp)) }

            val matchedThisImage = mutableSetOf<String>()
            for (cr in effectiveCrops) {
                totalRoi++
                val t0 = System.nanoTime()
                // 프로덕션 파이프라인(RecognitionPipeline.processCrop)과 동일 경로: 모든 라인 → matchBest.
                val lines = runCatching { ocr.recognizeAllLines(cr.bitmap) }.getOrNull() ?: emptyList()
                val ms = (System.nanoTime() - t0) / 1_000_000
                latencies += ms

                val match = repo.matchBest(lines)
                val root = (match as? MatchResult.Matched)?.root
                val dist = (match as? MatchResult.Matched)?.editDistance ?: -1

                // 정답 판정: 매칭된 root 후보의 이름(해당 언어) 중 기대 목록에 있으면 성공.
                val names = (match as? MatchResult.Matched)
                    ?.candidates
                    ?.mapNotNull { it.names.get(case.lang) }
                    ?.map { it.lowercase() }
                    ?: emptyList()
                val ok = names.any { n -> case.expected.any { e -> normalizeEq(n, e) } }
                if (ok) {
                    success++
                    root?.let { matchedThisImage += it }
                }

                Log.i(
                    TAG,
                    "${short(case.asset)} | ${cr.roiIndex} | ${case.lang} | " +
                        "'${if (lines.isEmpty()) "-" else lines.joinToString("|")}' | ${root ?: "-"} | $dist | $ms | ${if (ok) "OK" else "MISS"}",
                )
                cr.bitmap.recycle()
            }
            ocr.close()
            bmp.recycle()
            Log.i(TAG, "  → ${short(case.asset)} 기대=${case.expected} 매칭=${matchedThisImage}")
        }

        val avg = if (latencies.isNotEmpty()) latencies.average() else 0.0
        val p50 = latencies.sorted().getOrNull(latencies.size / 2) ?: 0
        Log.i(TAG, "==================== 요약 ====================")
        Log.i(TAG, "ROI 총 $totalRoi / 성공 $success (${pct(success, totalRoi)}%)")
        Log.i(TAG, "지연 ms: avg=${"%.0f".format(avg)} p50=$p50 min=${latencies.minOrNull()} max=${latencies.maxOrNull()}")
        Log.i(TAG, "==============================================")

        // 회귀 방어: 최소한 절반 이상은 인식되어야 한다(모델 다운로드 완료 가정).
        // 인식률이 낮으면 로그로 원인(라인/거리) 확인 후 업스케일/ROI 여백 튜닝.
        assertTrue(
            "OCR 인식 성공 ROI 가 너무 적음: $success/$totalRoi (logcat OcrFieldTest 확인)",
            success >= totalRoi / 2,
        )
    }

    /** 모델 다운로드/초기화 워밍업 — 성공할 때까지 짧게 재시도(최대 ~15초). */
    private suspend fun warmup(ocr: OcrEngine, bmp: Bitmap) {
        repeat(10) { i ->
            val r = runCatching { ocr.recognize(bmp) }
            if (r.isSuccess) return
            Log.i(TAG, "OCR 모델 워밍업 재시도 ${i + 1} (다운로드 대기 중일 수 있음)")
            Thread.sleep(1500)
        }
    }

    private fun loadAsset(path: String): Bitmap? =
        runCatching {
            testCtx.assets.open(path).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

    private fun short(p: String) = p.substringAfterLast('/')

    private fun pct(a: Int, b: Int) = if (b == 0) 0 else a * 100 / b

    /** 이름 비교: 공백/기호 제거 + 소문자 후 동일하면 일치. */
    private fun normalizeEq(a: String, b: String): Boolean {
        fun n(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()
        return n(a) == n(b)
    }
}

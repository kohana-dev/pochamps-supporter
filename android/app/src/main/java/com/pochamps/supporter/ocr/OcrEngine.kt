package com.pochamps.supporter.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [4] OcrEngine — ML Kit Text Recognition v2(온디바이스, "번들" 모델)로 크롭 비트맵에서 텍스트를 추출한다.
 *  (P9) bundled recognizer 사용 — 모델이 APK 에 동봉되어 최초 다운로드/초기화 지연·실패가 없다.
 *  옵션 클래스(KoreanTextRecognizerOptions 등)는 bundled/unbundled 공통 패키지(com.google.mlkit.vision.text.*)라 코드 변경 없음.
 *
 * ## 언어별 인식기 선택
 *  게임 화면 이름은 "내" 게임 언어로 렌더되므로, 설정된 언어에 맞는 recognizer 를 고른다.
 *  ML Kit 는 스크립트 단위 모델을 제공한다:
 *   - korean  : 한글 + 라틴
 *   - japanese: 가나/한자 + 라틴
 *   - chinese : 한자(간/번) + 라틴
 *   - latin   : 라틴 전용(en/de/es/fr/it 커버)
 *  → DESIGN.md 9개 언어를 4개 recognizer 로 커버(라틴계 5개 언어는 latin 하나로).
 *
 * ## 스레딩
 *  ML Kit process() 는 Task 를 비동기로 돌린다. 여기선 코루틴 suspend 로 감싸(백프레셔·취소 대응)
 *  호출부(파이프라인)가 Dispatchers.Default/IO 에서 순차 대기할 수 있게 한다.
 *
 * @param language 게임 언어 코드(v1 기본 "ko"). SUPPORTED_LANGUAGES 중 하나.
 * @param preprocess 인식 전 비트맵 전처리. 기본 NONE. (P9) GRAYSCALE_CONTRAST 는 이탤릭/마젠타 이름표의
 *   저대비 프레임에서 인식률을 올린다(실측: en_single_hippowdon2 'vopmoddy'→'Hippowdon' 복구).
 */
class OcrEngine(
    language: String = "ko",
    private val preprocess: Preprocess = Preprocess.NONE,
) {

    private val script: OcrScript = OcrScript.forLanguage(language)

    /** 스크립트별 recognizer(지연 생성, 재사용). */
    private val recognizer: TextRecognizer = createRecognizer(script)

    /**
     * 크롭 비트맵에서 텍스트 블록들을 추출한다.
     * @return 인식된 텍스트 라인 리스트(위→아래 순, ML Kit 블록/라인 텍스트). 실패/공백이면 빈 리스트.
     */
    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): List<String> {
        val input = applyPreprocess(bitmap)
        val image = InputImage.fromBitmap(input, rotationDegrees)
        val visionText = image.process()
        // 전처리로 새 비트맵을 만들었으면 회수(원본은 호출부 소유이므로 건드리지 않음).
        if (input !== bitmap) input.recycle()
        // 라인 단위로 평탄화(이름표가 여러 줄일 수 있으므로).
        return visionText.textBlocks
            .flatMap { block -> block.lines }
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 여러 후보 라인 중 "이름일 가능성이 가장 높은" 한 줄을 고른다(휴리스틱).
     * 이름표는 보통 가장 긴/문자 비율 높은 라인. 매칭기가 어차피 fuzzy 이므로 과하게 정제하지 않는다.
     */
    suspend fun recognizeBestLine(bitmap: Bitmap, rotationDegrees: Int = 0): String? {
        val lines = recognize(bitmap, rotationDegrees)
        return pickNameLine(lines)
    }

    /**
     * 인식된 모든 라인을 반환(길이 상한 필터만 적용). 호출부(파이프라인)가 각 라인을 매칭기에 통과시켜
     * 최적 매칭 라인을 고르게 한다.
     *
     * ## 왜 단일 pickNameLine 이 아니라 전체 라인인가 (P12)
     *  ROI 밴드를 넓히거나 이름표 인접 UI("MOVE TIME 45"/"Battle Info")가 크롭에 들어오면
     *  pickNameLine 은 letter/digit 이 더 많은 UI 라인을 이름으로 오선택해 NoMatch 가 난다(실측).
     *  → 대신 모든 라인을 매칭기에 넣고 "가장 잘 매칭되는(최소 editDistance)" 라인을 채택하면,
     *    사전 매칭이 오탐(UI 텍스트)을 걸러주므로 ROI 를 넓혀도 안전하다.
     */
    suspend fun recognizeAllLines(bitmap: Bitmap, rotationDegrees: Int = 0): List<String> =
        recognize(bitmap, rotationDegrees)

    /** ML Kit Task → suspend 변환(취소 지원). */
    private suspend fun InputImage.process() =
        suspendCancellableCoroutine { cont ->
            recognizer.process(this)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    /**
     * 전처리 적용. NONE 이면 원본 그대로 반환(새 비트맵 생성 안 함).
     * GRAYSCALE_CONTRAST: 채도 제거 + 대비 강화(ColorMatrix) — 마젠타 배경/이탤릭 폰트 대비를 키워 OCR 안정화.
     */
    private fun applyPreprocess(src: Bitmap): Bitmap {
        if (preprocess == Preprocess.NONE) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // 1) 그레이스케일(채도 0) → 2) 대비 강화(contrast≈1.4, 중간톤 기준).
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.4f
        val t = (-0.5f * contrast + 0.5f) * 255f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, t,
                0f, contrast, 0f, 0f, t,
                0f, 0f, contrast, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        gray.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(gray)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** 서비스 종료 시 recognizer 자원 해제. */
    fun close() {
        runCatching { recognizer.close() }
    }

    companion object {
        private fun createRecognizer(script: OcrScript): TextRecognizer = when (script) {
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        /**
         * OCR 후보 라인 중 이름 라인 후보를 고른다(순수 로직 — JVM 테스트 가능).
         * 규칙: 비어있지 않은 라인 중 letter/digit 비율이 가장 높은 라인, 동률이면 더 긴 라인.
         */
        fun pickNameLine(lines: List<String>): String? {
            return lines
                .filter { it.isNotBlank() }
                .maxByOrNull { line ->
                    val letters = line.count { it.isLetterOrDigit() }
                    // 점수: 문자 수를 우선, 라틴/CJK 문자 위주면 가산.
                    letters * 100 + line.length
                }
        }
    }
}

/**
 * OCR 전처리 종류.
 *  - NONE: 전처리 없음(기본, 최소 지연).
 *  - GRAYSCALE_CONTRAST: 그레이스케일 + 대비 강화. 저대비/기울임 이름표에서 인식률 개선(P9 실측).
 */
enum class Preprocess { NONE, GRAYSCALE_CONTRAST }

/** ML Kit recognizer 스크립트 종류. */
enum class OcrScript {
    KOREAN, JAPANESE, CHINESE, LATIN;

    companion object {
        /**
         * 게임 언어 코드 → 사용할 스크립트.
         * ko→korean, ja→japanese, zh-cn/zh-tw→chinese, 그 외(en/de/es/fr/it)→latin.
         * (순수 로직 — JVM 테스트 가능)
         */
        fun forLanguage(lang: String): OcrScript = when (lang.lowercase()) {
            "ko" -> KOREAN
            "ja" -> JAPANESE
            "zh-cn", "zh-tw", "zh" -> CHINESE
            else -> LATIN // en, de, es, fr, it
        }
    }
}

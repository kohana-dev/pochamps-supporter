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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [4] OcrEngine — ML Kit Text Recognition v2(온디바이스, "번들" 모델)로 크롭 비트맵에서 텍스트를 추출한다.
 *  (P9) bundled recognizer 사용 — 모델이 APK 에 동봉되어 최초 다운로드/초기화 지연·실패가 없다.
 *  옵션 클래스(KoreanTextRecognizerOptions 등)는 bundled/unbundled 공통 패키지(com.google.mlkit.vision.text.*)라 코드 변경 없음.
 *
 * ## 항상 다국어 인식 (P31 — 언어 고정 제거)
 *  이 게임은 **전세계 유저와 매칭**되어 상대 이름표의 언어가 게임마다·상대마다 바뀔 수 있다.
 *  따라서 "게임 언어 하나를 골라 recognizer 하나만" 쓰는 방식(P19 이전 captureLang)은 틀렸다.
 *  상대가 어느 언어로 떠도 읽으려면 **모든 스크립트 인식기를 항상 병행**해야 한다.
 *
 *  ML Kit 에는 "모든 스크립트" 단일 인식기가 없다. 스크립트별 별도 모델이 있고, 각 CJK 인식기는
 *  라틴도 함께 읽는다:
 *   - korean  : 한글 + 라틴
 *   - japanese: 가나/한자 + 라틴
 *   - chinese : 한자(간/번) + 라틴
 *   - latin   : 라틴 전용(en/de/es/fr/it 커버)
 *  → **4개 인식기로 DESIGN.md 9개 언어 전부 커버**(라틴계 5개 언어는 latin 하나로).
 *  이 4개를 모두 보유하고 [recognizeAllLines] 에서 **병렬 실행**한 뒤 라인을 합쳐(중복 제거) 반환한다.
 *
 * ## 병렬 실행 → 지연은 4배가 아니다
 *  4개 recognizer 를 코루틴 `async` 로 동시에 띄우고 `awaitAll` 로 모은다. 각 recognizer 는
 *  독립 ML Kit Task 라 병렬로 진행되므로, 전체 지연 ≈ **가장 느린 하나**(순차 합이 아님).
 *  "엉뚱한 스크립트 인식기가 만든 garbage 라인"은 닫힌 사전(208종) 매칭이 자연히 걸러준다
 *  (NameMatcher — 이것이 다국어 병합의 핵심 안전장치).
 *
 * @param preprocess 인식 전 비트맵 전처리. 기본 NONE. (P9) GRAYSCALE_CONTRAST 는 이탤릭/마젠타 이름표의
 *   저대비 프레임에서 인식률을 올린다(실측: en_single_hippowdon2 'vopmoddy'→'Hippowdon' 복구).
 * @param language (Deprecated, P31) 더 이상 recognizer 선택에 쓰지 않는다. 항상 4개 스크립트를 모두 읽는다.
 *   과거 호출부/테스트 호환을 위해 시그니처만 남겨두며 **무시**된다.
 */
class OcrEngine(
    // (Deprecated, P31) captureLang 폐지 — 항상 다국어 인식. 이 파라미터는 무시된다(호환용 시그니처).
    @Suppress("UNUSED_PARAMETER") language: String? = null,
    private val preprocess: Preprocess = Preprocess.NONE,
) {

    /**
     * 스크립트별 recognizer(항상 4개 전부 보유, P31). 스크립트 태그와 함께 묶어 진단에 활용한다.
     * korean/japanese/chinese 는 각자 라틴도 읽으므로 라틴 이름은 여러 인식기가 중복 반환할 수 있고,
     * [recognizeAllLines] 가 중복을 제거한다.
     */
    private val recognizers: List<ScriptRecognizer> = OcrScript.entries.map { script ->
        ScriptRecognizer(script, createRecognizer(script))
    }

    /**
     * 크롭 비트맵에서 텍스트 라인들을 추출한다(4개 스크립트 인식기 **병렬** 실행 후 합쳐 반환).
     * @return 인식된 텍스트 라인 리스트(중복 제거, 정규화 기준 유일). 실패/공백이면 빈 리스트.
     */
    suspend fun recognize(bitmap: Bitmap, rotationDegrees: Int = 0): List<String> =
        recognizeTagged(bitmap, rotationDegrees).map { it.text }

    /**
     * [P31] 라인 + 어느 스크립트 인식기가 읽었는지 태그를 함께 반환(진단용).
     * 같은 텍스트(정규화 기준)를 여러 인식기가 읽으면 **한 번만** 남기고, 어느 인식기들이 읽었는지
     * 스크립트 집합을 [TaggedLine.scripts] 에 모은다. 라인 순서는 처음 등장 순서를 유지한다.
     */
    suspend fun recognizeTagged(bitmap: Bitmap, rotationDegrees: Int = 0): List<TaggedLine> {
        val input = applyPreprocess(bitmap)
        val image = InputImage.fromBitmap(input, rotationDegrees)
        // 4개 인식기를 동시에 띄워(async) 병렬 실행 → 지연은 가장 느린 하나 수준.
        val perScript: List<Pair<OcrScript, List<String>>> = coroutineScope {
            recognizers
                .map { sr -> async { sr.script to runCatching { sr.recognizeLines(image) }.getOrDefault(emptyList()) } }
                .awaitAll()
        }
        // 전처리로 새 비트맵을 만들었으면 회수(원본은 호출부 소유이므로 건드리지 않음).
        if (input !== bitmap) input.recycle()
        return mergeTagged(perScript)
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
     * 인식된 모든 라인을 반환(4개 스크립트 병렬 인식 + 중복 제거). 호출부(파이프라인)가 각 라인을
     * 매칭기에 통과시켜 9개 언어 사전 전체 중 최적 매칭 라인을 고르게 한다.
     *
     * ## 왜 단일 pickNameLine 이 아니라 전체 라인인가 (P12)
     *  ROI 밴드를 넓히거나 이름표 인접 UI("MOVE TIME 45"/"Battle Info")가 크롭에 들어오면
     *  pickNameLine 은 letter/digit 이 더 많은 UI 라인을 이름으로 오선택해 NoMatch 가 난다(실측).
     *  → 대신 모든 라인을 매칭기에 넣고 "가장 잘 매칭되는(최소 editDistance)" 라인을 채택하면,
     *    사전 매칭이 오탐(UI 텍스트/엉뚱한 스크립트 garbage)을 걸러주므로 ROI/스크립트를 넓혀도 안전하다.
     */
    suspend fun recognizeAllLines(bitmap: Bitmap, rotationDegrees: Int = 0): List<String> =
        recognize(bitmap, rotationDegrees)

    /** ML Kit Task → suspend 변환(취소 지원). recognizer 별로 호출. */
    private suspend fun TextRecognizer.processAwait(image: InputImage) =
        suspendCancellableCoroutine { cont ->
            process(image)
                .addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    /** 스크립트 인식기 하나 — recognizer + 스크립트 태그. */
    private inner class ScriptRecognizer(
        val script: OcrScript,
        private val recognizer: TextRecognizer,
    ) {
        /** 이 인식기로 라인 목록 추출(위→아래 순, 공백 제거). */
        suspend fun recognizeLines(image: InputImage): List<String> =
            recognizer.processAwait(image)
                .textBlocks
                .flatMap { block -> block.lines }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }

        fun close() = runCatching { recognizer.close() }
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

    /** 서비스 종료 시 recognizer 자원 해제(4개 전부, P31). */
    fun close() {
        recognizers.forEach { it.close() }
    }

    companion object {
        private fun createRecognizer(script: OcrScript): TextRecognizer = when (script) {
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }

        /**
         * [P31] 스크립트별 라인 목록을 **중복 제거하며 합친다**(순수 로직 — JVM 테스트 가능).
         *  - 중복 판정은 [normalizeForDedup](공백/기호 제거 + 소문자) 기준 — 같은 이름을 여러 인식기가
         *    미세하게 다르게(대소문자/공백) 뱉어도 하나로 본다.
         *  - 라인 순서는 **처음 등장 순서**를 유지하고, 어느 스크립트들이 그 라인을 읽었는지 모은다.
         *  - 라틴 이름은 korean/japanese/chinese 인식기도 읽으므로 scripts 집합이 여럿일 수 있다.
         */
        fun mergeTagged(perScript: List<Pair<OcrScript, List<String>>>): List<TaggedLine> {
            val order = ArrayList<String>() // 정규화 키의 최초 등장 순서
            val byKey = LinkedHashMap<String, MutableTagged>()
            for ((script, lines) in perScript) {
                for (raw in lines) {
                    val key = normalizeForDedup(raw)
                    if (key.isEmpty()) continue
                    val existing = byKey[key]
                    if (existing == null) {
                        order.add(key)
                        byKey[key] = MutableTagged(text = raw, scripts = linkedSetOf(script))
                    } else {
                        existing.scripts.add(script)
                        // 표시 텍스트는 최초 등장본을 유지(안정적 진단 표시).
                    }
                }
            }
            return order.map { key ->
                val m = byKey.getValue(key)
                TaggedLine(text = m.text, scripts = m.scripts.toList())
            }
        }

        /** 중복/매칭 정규화와 동일 규칙(공백/기호 제거 + 소문자). CJK/한글/라틴 문자는 보존. */
        fun normalizeForDedup(raw: String): String {
            val sb = StringBuilder(raw.length)
            for (ch in raw) if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
            return sb.toString()
        }

        private data class MutableTagged(val text: String, val scripts: MutableSet<OcrScript>)

        /**
         * OCR 후보 라인 중 이름 라인 후보를 고른다(순수 로직 — JVM 테스트 가능).
         * 규칙: 비어있지 않은 라인 중 letter/digit 비율이 가장 높은 라인, 동률이면 더 긴 라인.
         */
        fun pickNameLine(lines: List<String>): String? {
            return lines
                .filter { it.isNotBlank() }
                .maxByOrNull { line ->
                    val letters = line.count { it.isLetterOrDigit() }
                    letters * 100 + line.length
                }
        }
    }
}

/**
 * [P31] 인식 라인 + 어느 스크립트 인식기가 읽었는지 태그(진단용).
 * @param text 라인 텍스트(최초 등장본).
 * @param scripts 이 라인을 읽은 스크립트 인식기들(라틴 이름은 여러 개일 수 있음).
 */
data class TaggedLine(val text: String, val scripts: List<OcrScript>)

/**
 * OCR 전처리 종류.
 *  - NONE: 전처리 없음(기본, 최소 지연).
 *  - GRAYSCALE_CONTRAST: 그레이스케일 + 대비 강화. 저대비/기울임 이름표에서 인식률 개선(P9 실측).
 */
enum class Preprocess { NONE, GRAYSCALE_CONTRAST }

/** ML Kit recognizer 스크립트 종류. 항상 4개 전부 병행 인식(P31). */
enum class OcrScript {
    KOREAN, JAPANESE, CHINESE, LATIN;

    /** 진단 스트립용 짧은 태그(K/J/C/L). */
    val tag: String
        get() = when (this) {
            KOREAN -> "K"; JAPANESE -> "J"; CHINESE -> "C"; LATIN -> "L"
        }

    companion object {
        /**
         * (Deprecated, P31) 게임 언어 코드 → 스크립트. 이제 항상 4개 스크립트를 모두 읽으므로
         * recognizer 선택엔 쓰지 않는다. 과거 호환/참고용으로만 남긴다.
         */
        @Deprecated("P31: 언어 고정 폐지 — 항상 다국어 인식. recognizer 선택에 쓰지 않는다.")
        fun forLanguage(lang: String): OcrScript = when (lang.lowercase()) {
            "ko" -> KOREAN
            "ja" -> JAPANESE
            "zh-cn", "zh-tw", "zh" -> CHINESE
            else -> LATIN // en, de, es, fr, it
        }
    }
}

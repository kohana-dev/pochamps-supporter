package com.pochamps.supporter.capture

import com.pochamps.supporter.data.BattleFormat

/**
 * 화면 비율 기준 ROI 사각형 하나. 값은 0.0~1.0 비율(해상도 독립).
 *
 * ⚠️ 순수 JVM — Android 의존성 없음. 실제 픽셀 rect 계산/오버라이드 저장 로직만 담아 유닛 테스트 가능.
 *
 * @param left   좌측 x 비율(0=화면 왼쪽)
 * @param top    상단 y 비율(0=화면 위)
 * @param right  우측 x 비율(1=화면 오른쪽)
 * @param bottom 하단 y 비율(1=화면 아래)
 */
data class RoiRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0) {
            "ROI 비율은 0.0~1.0 이어야 함: $this"
        }
        require(right > left && bottom > top) { "ROI right/bottom 이 left/top 보다 커야 함: $this" }
    }

    /** 비율 rect → 픽셀 rect(정수). 캡처 비트맵 폭/높이에 맞춰 스케일. */
    fun toPixels(bitmapWidth: Int, bitmapHeight: Int): PixelRect {
        val l = (left * bitmapWidth).toInt().coerceIn(0, bitmapWidth - 1)
        val t = (top * bitmapHeight).toInt().coerceIn(0, bitmapHeight - 1)
        val r = (right * bitmapWidth).toInt().coerceIn(l + 1, bitmapWidth)
        val b = (bottom * bitmapHeight).toInt().coerceIn(t + 1, bitmapHeight)
        return PixelRect(l, t, r - l, b - t)
    }
}

/** 픽셀 단위 rect(크롭용). x/y = 좌상단, width/height = 크기. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * [3] RoiConfig — 이름 인식용 ROI 설정.
 *
 * SharedPreferences 로 오버라이드 가능(추후 보정 UI 연결).
 *
 * ## 기본값 근거 — K2 실측 확정(P8, 2026-07-05)
 *  실게임 배틀 화면(웹의 모바일/스위치 녹화 다수)에서 **상대 포켓몬 이름표(name plate)** 위치를 측정.
 *  자료: field_samples/ (모바일 raw 캡처 + 한국어/영어 배틀). 자세한 근거는 field_samples/SOURCES.md.
 *
 *  ### 측정 결과 (게임 뷰포트 비율)
 *  - **이름표는 화면 상단 "우측" 영역**에 뜬다(좌측 아님 — 좌하단은 "내" 포켓몬).
 *  - 이름표 = [아이콘 + 종족명 텍스트 + 성별기호] / 아래 HP바 / HP%.
 *  - **싱글배틀**: 상대 1마리 → 우상단 이름표 1개. 측정 x≈0.75~0.88, y≈0.05~0.14 (박스 x≈0.72~0.89).
 *  - **더블배틀**: 상대 2마리 → 우상단에 **나란히** 2개(좌우 절반 아님!).
 *      좌 플레이트 x≈0.60~0.76, 우 플레이트 x≈0.80~1.00, 둘 다 y≈0.03~0.14.
 *  - 표시명 = **폼/닉네임 없는 base 종족명**(예: "갸라도스", "Charizard", "Typhlosion"). K2 확정.
 *
 *  ### 기본값 좌표(측정치 + OCR 안전 여백)
 *  - 우측 절반에 밀집하므로, 종전 "좌/우 대칭" 추정을 폐기하고 **우상단 밀집 배치**로 교정.
 *  - 아래 [DEFAULT_LANDSCAPE_DOUBLES] 2개 ROI 는 실측 좌/우 플레이트에 여백을 더한 값.
 *  - 싱글배틀용 [DEFAULT_LANDSCAPE_SINGLE] 도 제공(향후 배틀형식 감지 시 스왑 가능).
 *  둘 다 못 잡으면 [FULL_TOP_HALF] 로 상단 절반 전체 OCR fallback.
 *
 * @param rois 감시/크롭할 ROI 목록(순서 = roiIndex). 더블배틀이면 2개, 싱글이면 1개.
 */
data class RoiConfig(
    val rois: List<RoiRect>,
) {
    companion object {
        /**
         * 가로화면 더블배틀 기본 ROI — **우상단 이름표 2개(나란히)**.
         *
         * ## 기본값 근거 — Galaxy S25+ 19.5:9 실기기 더블배틀 스크린샷 실측(P22, 2026-07-05)
         *  실사용자가 제공한 **실제 안드로이드 포챔스 더블배틀 스크린샷**(Galaxy S25+, 19.5:9)에서
         *  상대 이름표 2개의 위치를 화면 비율로 직접 측정했다. 두 플레이트는 화면 우상단에 나란히 뜬다.
         *  DB 이름 매칭은 정상 확인(나인테일→ninetales, 파이어로→talonflame) — 순수 ROI 조준 문제였다.
         *
         *  ### 실측 좌표 표 (화면 비율)
         *  | 플레이트        | 가로(left~right) | 세로(top~bottom) |
         *  |-----------------|------------------|------------------|
         *  | 좌(예 "나인테일") | 0.60 ~ 0.75      | 0.03 ~ 0.16      |
         *  | 우(예 "파이어로") | 0.765 ~ 0.925    | 0.03 ~ 0.16      |
         *
         *  ### 기본값 좌표(실측치 + OCR 안전 여백)
         *  | 플레이트 | left  | top  | right | bottom |
         *  |----------|-------|------|-------|--------|
         *  | 좌       | 0.585 | 0.02 | 0.758 | 0.185  |
         *  | 우       | 0.758 | 0.02 | 0.940 | 0.185  |
         *
         *  ### 히스토리 (종전 웹수집 기준을 교정)
         *  - ~P21: 웹수집 스크린샷 기준 좌 0.57~0.78 / 우 **0.78~1.0** / 세로 0.02~0.24.
         *    우 밴드가 0.78 에서 시작해 **파이어로 왼쪽 글자를 자르고**, 1.0 까지 가서 커맨드 버튼
         *    영역을 포함했다(실기기에서 오조준). P22 에서 실기기 실측값으로 교정.
         *  - 웹수집 더블 샘플(en_doubles_*)은 게임 UI 위치가 실기기와 달라 [OcrFieldTest] 에서
         *    샘플 전용 ROI(WEB_DOUBLES_LEGACY)를 명시해 default 교정과 분리했다.
         */
        val DEFAULT_LANDSCAPE_DOUBLES = RoiConfig(
            rois = listOf(
                // 좌측(상대1) 이름표: 실측 x 0.60~0.75 → 여백 포함 left 0.585, right 0.758.
                RoiRect(left = 0.585, top = 0.02, right = 0.758, bottom = 0.185),
                // 우측(상대2) 이름표: 실측 x 0.765~0.925 → 여백 포함 left 0.758, right 0.940.
                // (종전 0.78~1.0 은 파이어로 왼쪽 글자 클리핑 + 커맨드 버튼 포함 → 교정)
                RoiRect(left = 0.758, top = 0.02, right = 0.940, bottom = 0.185),
            ),
        )

        /**
         * 가로화면 싱글배틀 기본 ROI — **우상단 이름표 1개**, K2 실측 교정(P8) + K3 실측 하단 확장(P9).
         * 실측 박스 x≈0.72~0.89, y≈0.05~0.14 → 여백 포함 0.70~0.94, 0.02~0.22.
         *
         * ⚠️ P9 실측 교정: bottom 0.17 로는 이름표가 살짝 아래 뜬 프레임(en_single_hippowdon2)의 텍스트가
         *   하단 클리핑되어 'vopmoddy' 로 오인식됐다. bottom 을 0.22 로 넓히니 동일 프레임이 'Hippowdon' 으로 복구
         *   (진단 로그: k3_diag_hippowdon2_roi_variants — default→MISS, taller/lower/downshift→OK).
         *   top 은 0.02 로 유지해 더 높이 뜬 프레임(hippowdon1)도 계속 포함. right 도 0.94 로 소폭 확장.
         *
         * ⚠️ P12 세로밴드 확장: bottom 0.22→0.30. 장면별(배틀/기술선택) 이름표 y 편차·레터박스를 흡수한다.
         *   확장으로 인접 UI("MOVE TIME 45"/"Battle Info")가 크롭에 들어와도, 파이프라인 matchBest(다중 라인
         *   매칭)가 종족명 라인만 채택하므로 안전(에뮬 실측: current/tallerBottom/wideBand 전부 editDist≤1 OK).
         *
         * ⚠️ P22 범위 밖: 이번 교정(P22)은 더블배틀만 실기기 실측으로 갱신했다. 싱글은 아직 실기기
         *   스크린샷이 없어 웹수집 기준값을 유지한다. **싱글은 실기기 스크린샷 확보 후 P22 방식으로 교정 예정.**
         */
        val DEFAULT_LANDSCAPE_SINGLE = RoiConfig(
            rois = listOf(
                RoiRect(left = 0.70, top = 0.02, right = 0.94, bottom = 0.30),
            ),
        )

        /** ROI 크롭이 모두 실패했을 때 쓰는 전체화면 상단 절반 fallback(가운데 좌우 여백 살짝 제외). */
        val FULL_TOP_HALF = RoiRect(left = 0.02, top = 0.0, right = 0.98, bottom = 0.5)

        /** 기본 설정(오버라이드 없을 때). 게임이 더블배틀 중심이므로 더블을 기본으로 둔다. */
        fun default(): RoiConfig = DEFAULT_LANDSCAPE_DOUBLES

        /**
         * 배틀 형식별 기본 ROI(P20). 싱글=1밴드([DEFAULT_LANDSCAPE_SINGLE]),
         * 더블=2밴드([DEFAULT_LANDSCAPE_DOUBLES]). 형식 토글이 이 값으로 활성 ROI 를 스왑한다.
         */
        fun activeDefault(format: BattleFormat): RoiConfig = when (format) {
            BattleFormat.SINGLES -> DEFAULT_LANDSCAPE_SINGLE
            BattleFormat.DOUBLES -> DEFAULT_LANDSCAPE_DOUBLES
        }

        /**
         * 직렬화 문자열 → RoiConfig 파싱(오버라이드 저장/복원용, 순수 JVM).
         * 포맷: "l,t,r,b;l,t,r,b" (ROI 를 ';' 로 구분, 각 값은 ','). 파싱 실패/빈 문자열이면 null.
         */
        fun parse(serialized: String?): RoiConfig? {
            if (serialized.isNullOrBlank()) return null
            return try {
                val rects = serialized.split(';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { part ->
                        val v = part.split(',').map { it.trim().toDouble() }
                        require(v.size == 4) { "ROI 는 4개 값이어야 함: $part" }
                        RoiRect(v[0], v[1], v[2], v[3])
                    }
                if (rects.isEmpty()) null else RoiConfig(rects)
            } catch (_: Exception) {
                null
            }
        }

        /** RoiConfig → 직렬화 문자열([parse] 역함수). */
        fun serialize(config: RoiConfig): String =
            config.rois.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
    }
}

/**
 * RoiConfig 영속 저장소(오버라이드). Android 구현은 SharedPreferences,
 * 테스트 구현은 인메모리. 오버라이드가 없으면 형식별 기본값 사용.
 *
 * ## P20 — 형식별 오버라이드 분리
 * 싱글(1밴드)/더블(2밴드)은 밴드 수가 달라 **같은 오버라이드를 공유하면 깨진다**(더블 보정을 싱글에
 * 적용하면 두 번째 밴드가 붕 뜬다). 그래서 오버라이드를 **형식별로 분리**([BattleFormat] 로 키를 나눔).
 * 형식별 오버라이드가 없으면 그 형식 기본값([RoiConfig.activeDefault])을 반환한다.
 *
 * 하위호환: 무인자 [load]/[save]/[clear]/[effective] 는 더블 형식에 위임한다(기존 호출부 유지).
 */
interface RoiConfigStore {
    /** 형식별 저장된 오버라이드. 없으면 null(→ 호출부가 형식 기본값 사용). */
    fun load(format: BattleFormat): RoiConfig?

    /** 형식별 오버라이드 저장. */
    fun save(format: BattleFormat, config: RoiConfig)

    /** 형식별 오버라이드 제거(그 형식 기본값으로 복귀). */
    fun clear(format: BattleFormat)

    /** 형식별 오버라이드가 있으면 그것, 없으면 그 형식 기본값. */
    fun effective(format: BattleFormat): RoiConfig =
        load(format) ?: RoiConfig.activeDefault(format)

    // --- 하위호환(더블 형식 위임). 기존 무인자 호출부 유지용. ---
    /** @deprecated 형식 인자 버전을 쓸 것. 더블 오버라이드에 위임. */
    fun load(): RoiConfig? = load(BattleFormat.DOUBLES)

    /** @deprecated 형식 인자 버전을 쓸 것. 더블 오버라이드에 위임. */
    fun save(config: RoiConfig) = save(BattleFormat.DOUBLES, config)

    /** @deprecated 형식 인자 버전을 쓸 것. 더블 오버라이드에 위임. */
    fun clear() = clear(BattleFormat.DOUBLES)

    /** @deprecated 형식 인자 버전을 쓸 것. 더블 오버라이드에 위임. */
    fun effective(): RoiConfig = effective(BattleFormat.DOUBLES)
}

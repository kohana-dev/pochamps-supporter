package com.pochamps.supporter.data

/**
 * [P32] 스피드 실능치(실속) 범위 계산(순수 JVM — Android 의존성 없음, 유닛 테스트 가능).
 *
 * 포챔스 대전은 Lv50 고정이므로, 종족값(base) 하나만으로 상대의 스피드 **실능치 범위**를 낸다:
 *  - **min**  : 무투자·중립성격(개체값 0, 노력치 0, 성격 ×1.0) → "가장 느린 배분"의 하한.
 *  - **max**  : 풀투자·상향성격(개체값 31, 노력치 252→능력 계산상 +63, 성격 ×1.1) → 상한.
 *  - **scarf**: max 에 구애의스카프(×1.5)를 얹은 값 — "스카프 의심 시" 최고 실속.
 *
 * 실능치 공식(Lv50, HP 이외 스탯):
 *   stat = floor( (2*base + iv + ev/4) * level / 100 + 5 ) * nature
 * 여기서 ev/4 의 최대는 252/4 = 63 이므로, 코드에선 (iv + evQuarter) 를 직접 넣는다.
 *
 *  - min   = floor( (2*base + 0 + 0)  * 50 / 100 ) + 5              (성격 ×1.0)
 *  - max   = floor( floor( (2*base + 31 + 63) * 50 / 100 + 5 ) * 1.1 )
 *  - scarf = floor( max * 1.5 )
 *
 * 경계(검증): base 102(한카리아스) → min 107 / max 169 / scarf 253.
 *   (169 은 커뮤니티에 널리 알려진 한카리아스 Lv50 풀보정 실스피드값과 일치.)
 */
object SpeedCalc {

    /** 스피드 실속 범위(Lv50). */
    data class SpeedRange(val min: Int, val max: Int, val scarf: Int)

    private const val LEVEL = 50

    /**
     * 종족 스피드(base)로 Lv50 실속 범위를 계산한다.
     * @param baseSpe 종족값 스피드(예: 한카리아스 102). 음수/0 도 방어적으로 처리(그대로 계산).
     */
    fun rangeLv50(baseSpe: Int): SpeedRange {
        // min: 무투자 중립성격(iv=0, ev=0, nature ×1.0).
        val min = (2 * baseSpe + 0 + 0) * LEVEL / 100 + 5
        // max: 풀투자 상향성격(iv=31, ev 252→+63, nature ×1.1).
        val maxNeutral = (2 * baseSpe + 31 + 63) * LEVEL / 100 + 5
        val max = (maxNeutral * 1.1).toInt() // floor (양수 truncate)
        // scarf: max 에 구애의스카프 ×1.5.
        val scarf = (max * 1.5).toInt()
        return SpeedRange(min = min, max = max, scarf = scarf)
    }
}

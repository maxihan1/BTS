// LexoRank 순서 키 값 객체 — 백로그/보드 정렬을 위한 base-26 알파벳 문자열 VO

package com.bts.shared.lexorank

/**
 * LexoRank 순서 키 값 객체.
 *
 * 백로그/보드의 드래그 정렬에 사용하는 base-26 알파벳 문자열 VO 이다.
 * 사전순 비교가 곧 정렬 순서이므로 DB 인덱스 스캔과 직접 호환된다.
 *
 * 불변식.
 * - 소문자 알파벳 a-z 로만 구성, 1~50자.
 * - 끝 문자가 'a' 이어선 안 된다(trailing-a 금지).
 *   trailing-a 는 직전 키의 바로 다음이라 사이에 삽입 공간이 없기 때문이다.
 *
 * @param value 순서 키 문자열 (소문자 a-z, 1~50자, 끝 문자 a 아님)
 * @throws IllegalArgumentException 불변식을 위반하는 경우
 */
@JvmInline
value class Rank(val value: String) : Comparable<Rank> {

    init {
        require(value.isNotEmpty()) { "Rank 값은 빈 문자열이 아니어야 한다" }
        require(value.length <= MAX_LENGTH) { "Rank 값은 ${MAX_LENGTH}자를 초과할 수 없다: '$value'" }
        require(ALPHABET_REGEX.matches(value)) { "Rank 값은 소문자 알파벳 a-z 만 허용한다: '$value'" }
        require(value.last() != LOWER_BOUND_CHAR) {
            "Rank 값의 끝 문자는 'a' 가 아니어야 한다 (trailing-a 금지): '$value'"
        }
    }

    override fun compareTo(other: Rank): Int = value.compareTo(other.value)

    companion object {

        /** 최대 허용 길이 (VARCHAR(50) 준수) */
        const val MAX_LENGTH = 50

        /** 알파벳 하한 문자 ('a', 인덱스 0) */
        private const val LOWER_BOUND_CHAR = 'a'

        /** 알파벳 상한 문자 ('z', 인덱스 25) */
        private const val UPPER_BOUND_CHAR = 'z'

        /** 알파벳 크기 (26) */
        private const val ALPHABET_SIZE = 26

        /** 내부 연산에서 prev 소진 시 사용하는 하한 인덱스 (-1) */
        private const val LOWER_SENTINEL = -1

        /** 내부 연산에서 next 소진 시 사용하는 상한 인덱스 (26) */
        private const val UPPER_SENTINEL = ALPHABET_SIZE

        /** 소문자 알파벳만 허용하는 정규식 */
        private val ALPHABET_REGEX = Regex("^[a-z]+\$")

        /**
         * 문자열로부터 Rank 를 생성한다.
         *
         * @param value 소문자 a-z, 1~50자, 끝 문자 a 아닌 문자열
         * @throws IllegalArgumentException 불변식을 위반하는 경우
         */
        fun of(value: String): Rank = Rank(value)

        /**
         * 빈 백로그의 첫 이슈에 부여하는 기본 순서 키를 반환한다.
         *
         * between(null, null) 과 동일한 값이다.
         */
        fun initial(): Rank = between(null, null)

        /**
         * 두 경계 사이 사전순 중간 키를 생성한다.
         *
         * 경계 규칙.
         * - prev = null: 하한 경계 (알파벳 'a' 이전)
         * - next = null: 상한 경계 (알파벳 'z' 이후)
         *
         * 불변식.
         * - prev != null 이면 prev lt result
         * - next != null 이면 result lt next
         * - result 는 trailing-a 가 아님
         *
         * @param prev 하위 경계 Rank (null 이면 하한)
         * @param next 상위 경계 Rank (null 이면 상한)
         * @throws RankSpaceExhaustedException MAX_LENGTH 내 중간값 생성 불가 시
         */
        fun between(prev: Rank?, next: Rank?): Rank {
            val result = StringBuilder()

            for (i in 0 until MAX_LENGTH) {
                val p: Int = prev?.value?.getOrNull(i)?.toIndex() ?: LOWER_SENTINEL
                val n: Int = next?.value?.getOrNull(i)?.toIndex() ?: UPPER_SENTINEL

                val mid = (p + n) / 2

                if (mid > p) {
                    result.append(mid.toRankChar())
                    // trailing-a 인 경우에는 종료하지 않고 다음 자리를 계속 계산한다.
                    // 이렇게 하면 최종 결과의 끝 문자가 'a' 가 되지 않는다.
                    if (mid != 0) {
                        return Rank(result.toString())
                    }
                    // mid == 0('a'): 이 자리를 채택하고 다음 자리에서 prev=-1, next=26 으로 계속
                } else {
                    // mid == p: n - p == 1 인접 케이스. prev 문자를 채택하고 다음 자리 연장.
                    result.append(p.toRankChar())
                }
            }

            throw RankSpaceExhaustedException(
                "Rank 키 공간 고갈: prev=${prev?.value}, next=${next?.value} 사이에서 " +
                    "${MAX_LENGTH}자 내 중간값 생성 불가. rebalance 를 트리거하라."
            )
        }

        /** 알파벳 인덱스(0~25)를 Rank 문자로 변환한다 ('a'=0, 'z'=25). */
        private fun Int.toRankChar(): Char = (this + LOWER_BOUND_CHAR.code).toChar()

        /** Rank 문자를 알파벳 인덱스(0~25)로 변환한다. */
        private fun Char.toIndex(): Int = this.code - LOWER_BOUND_CHAR.code
    }
}

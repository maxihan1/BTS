// Argon2id 해싱 파라미터 상수 및 timing attack 방어용 더미 해시

package com.atlas.bts.identity.credential

/**
 * Argon2id 파라미터 상수.
 *
 * 출처: OWASP Password Storage Cheat Sheet 2024
 * https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
 *
 * ## DUMMY_HASH 생성 방법
 * argon2-jvm 라이브러리로 일회 실행:
 * ```
 * Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
 *     .hash(ITERATIONS, MEMORY_KB, PARALLELISM, "DUMMY".toCharArray())
 * ```
 * 결과 문자열을 DUMMY_HASH 에 붙여 넣는다. 파라미터 변경 시 재생성 필요.
 */
object Argon2Params {
    const val MEMORY_KB = 65536
    const val ITERATIONS = 3
    const val PARALLELISM = 4

    /**
     * verifyForUser 가 row 없는 경우 timing attack 방어 목적으로 사용하는 더미 해시.
     *
     * 존재하지 않는 사용자 조회 시 응답 시간이 존재하는 사용자와 동일하도록
     * 실제 Argon2id verify 호출을 수행하기 위한 플레이스홀더 해시.
     *
     * - 사용 시 절대 평문 직접 비교 안 함 — 반드시 [Argon2.verify] 를 통해서만 호출.
     * - verify 결과는 항상 false ("DUMMY" 와 일치하는 실제 사용자 비밀번호는 없어야 함).
     *
     * ## DUMMY_HASH 재생성 방법 (파라미터 변경 시)
     * argon2-jvm 라이브러리로 일회 실행:
     * ```kotlin
     * Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
     *     .hash(ITERATIONS, MEMORY_KB, PARALLELISM, "DUMMY".toCharArray())
     * ```
     * 결과 문자열을 이 상수에 붙여 넣는다.
     * [MEMORY_KB], [ITERATIONS], [PARALLELISM] 변경 시 반드시 재생성.
     */
    const val DUMMY_HASH =
        "\$argon2id\$v=19\$m=65536,t=3,p=4\$JqBo0yO1FXh5APsSwPIrmQ\$RST0s/txadZqJbiUAhKG6EsqxvoAJdtYsWEedJZo1G0"
}

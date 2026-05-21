// dev seed 용 Argon2id 해시 재생성 도구 — 평소엔 @Disabled, Argon2Params 변경 시 수동 실행

package com.atlas.bts.identity.credential

import de.mkammerer.argon2.Argon2Factory
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * data-dev.sql 의 alice/password Argon2id 해시를 재생성하기 위한 dev 도구.
 *
 * - 평소엔 @Disabled — 일반 test run 에서 실행 안 됨.
 * - Argon2Params 상수 (ITERATIONS / MEMORY_KB / PARALLELISM) 변경 시에만 수동 실행:
 *
 *   ./gradlew :modules:identity-access:test \
 *     --tests "com.atlas.bts.identity.credential.DevSeedHashGenerator" \
 *     -PrunDisabledTests=true
 *
 *   (혹은 IDE 에서 @Disabled 일시 해제 후 실행)
 *
 * - 출력된 hash 문자열을 data-dev.sql 의 INSERT 의 password_hash 자리에 박는다.
 * - 매 실행마다 salt 가 다르므로 hash 도 다르지만, 평문 "password" 와 verify 는 항상 성공.
 */
class DevSeedHashGenerator {
    @Test
    @Disabled("dev tool — Argon2Params 변경 시에만 수동 실행")
    fun `print alice password argon2id hash`() {
        val argon2 = Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)
        val hash = argon2.hash(
            Argon2Params.ITERATIONS,
            Argon2Params.MEMORY_KB,
            Argon2Params.PARALLELISM,
            "password".toCharArray(),
        )
        println("===ALICE_DEV_SEED_HASH_BEGIN===")
        println(hash)
        println("===ALICE_DEV_SEED_HASH_END===")
    }
}

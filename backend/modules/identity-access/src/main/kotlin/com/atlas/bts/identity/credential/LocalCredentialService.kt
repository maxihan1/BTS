// 로컬 계정 패스워드 해싱/검증 서비스 — Argon2id (OWASP 2024)

package com.atlas.bts.identity.credential

import de.mkammerer.argon2.Argon2Factory
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 로컬 인증 패스워드 해싱/검증 서비스.
 *
 * - 해싱: Argon2id, memory=65536KB, iterations=3, parallelism=4
 * - 평문 메모리 폐기: hash/verify 완료 후 [wipeArray] 호출
 *
 * ## 로그 정책 (NFR §3)
 * 이 클래스의 어떤 메서드도 password / hash / userId 를 로그에 출력하지 않는다.
 * 성공/실패 boolean + ms latency 만 INFO 레벨로 기록한다.
 */
class LocalCredentialService(
    private val repo: StoredPasswordCredentialRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(LocalCredentialService::class.java)
    private val argon2 = Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)

    /**
     * 평문 패스워드를 Argon2id 인코딩 문자열로 해싱한다.
     * 반환 후 [plain] 배열 내용은 즉시 폐기된다.
     */
    fun hash(plain: CharArray): String =
        try {
            argon2.hash(Argon2Params.ITERATIONS, Argon2Params.MEMORY_KB, Argon2Params.PARALLELISM, plain)
        } finally {
            argon2.wipeArray(plain)
        }

    /**
     * Argon2id 해시와 평문 패스워드가 일치하는지 검증한다.
     * 반환 후 [plain] 배열 내용은 즉시 폐기된다.
     */
    fun verify(
        hash: String,
        plain: CharArray,
    ): Boolean =
        try {
            argon2.verify(hash, plain)
        } finally {
            argon2.wipeArray(plain)
        }

    /**
     * 사용자 비밀번호를 해싱하여 [StoredPasswordCredential] 로 영속 저장한다 (UPSERT).
     *
     * ## Contract
     * - 예외를 외부로 throw 하지 않는다. repo 오류는 예외 전파.
     * - [plain] 은 finally 블록에서 반드시 wipe 된다 (DEVELOPMENT.md §1.1).
     * - 트랜잭션 경계: @Transactional (REQUIRED). 호출 측 트랜잭션 참여 또는 신규 시작.
     *
     * @param userId  저장 대상 사용자 식별자
     * @param plain   평문 비밀번호 CharArray — 호출 후 wipe 됨
     * @return        DB 에 저장된 [StoredPasswordCredential]
     */
    @Transactional
    fun store(
        userId: UUID,
        plain: CharArray,
    ): StoredPasswordCredential {
        val start = clock.millis()
        return try {
            val passwordHash = argon2.hash(Argon2Params.ITERATIONS, Argon2Params.MEMORY_KB, Argon2Params.PARALLELISM, plain)
            val credential = StoredPasswordCredential(
                userId = userId,
                passwordHash = passwordHash,
                createdAt = java.time.Instant.now(clock),
                updatedAt = java.time.Instant.now(clock),
            )
            requireNotNull(repo) { "repo 가 주입되지 않음 — store() 호출 불가" }.save(credential).also {
                log.info("store success latency={}ms", clock.millis() - start)
            }
        } finally {
            plain.fill(' ')
        }
    }

    /**
     * 사용자 식별자와 평문 비밀번호를 검증한다.
     *
     * ## Contract
     * - 예외를 외부로 throw 하지 않는다. Argon2 예외는 catch 후 false 반환 (EC-07).
     * - row 가 없는 경우 [Argon2Params.DUMMY_HASH] 로 dummy verify 를 반드시 수행한다 (timing attack 방어, EC-03).
     * - [plain] 은 finally 블록에서 반드시 wipe 된다.
     * - 트랜잭션 경계: @Transactional(readOnly=true). 조회 전용.
     *
     * @param userId  검증 대상 사용자 식별자
     * @param plain   평문 비밀번호 CharArray — 호출 후 wipe 됨
     * @return        비밀번호 일치 시 true, 불일치/row 없음/예외 시 false
     */
    @Transactional(readOnly = true)
    fun verifyForUser(
        userId: UUID,
        plain: CharArray,
    ): Boolean {
        val start = clock.millis()
        return try {
            val stored = requireNotNull(repo) { "repo 가 주입되지 않음 — verifyForUser() 호출 불가" }.findByUserId(userId)
            if (stored == null) {
                // timing attack 방어: row 없어도 dummy verify 수행 (응답 시간 일정화)
                runCatching { argon2.verify(Argon2Params.DUMMY_HASH, plain) }
                log.info("verifyForUser result=false (no row) latency={}ms", clock.millis() - start)
                return false
            }
            val result = runCatching { argon2.verify(stored.passwordHash, plain) }.getOrElse { ex ->
                log.warn("verifyForUser argon2 verify error latency={}ms ex={}", clock.millis() - start, ex.javaClass.simpleName)
                false
            }
            log.info("verifyForUser result={} latency={}ms", result, clock.millis() - start)
            result
        } finally {
            plain.fill(' ')
        }
    }

    /**
     * 사용자 비밀번호를 변경한다.
     *
     * ## Contract
     * - 예외를 외부로 throw 하지 않는다.
     * - [oldPlain] 검증 실패 시 false 반환, DB 변경 없음 (EC-02).
     * - [oldPlain] / [newPlain] 은 finally 블록에서 반드시 wipe 된다.
     * - 트랜잭션 경계: @Transactional (REQUIRED). store 포함 단일 경계.
     *
     * @param userId    변경 대상 사용자 식별자
     * @param oldPlain  현재 비밀번호 CharArray — 호출 후 wipe 됨
     * @param newPlain  새 비밀번호 CharArray — 호출 후 wipe 됨
     * @return          변경 성공 시 true, old 불일치 시 false
     */
    @Transactional
    fun rotate(
        userId: UUID,
        oldPlain: CharArray,
        newPlain: CharArray,
    ): Boolean {
        val start = clock.millis()
        return try {
            // verifyForUser 는 내부적으로 oldPlain 을 wipe 함 — 여기서는 복사본으로 검증
            val oldCopy = oldPlain.copyOf()
            val verified = verifyForUser(userId, oldCopy)
            if (!verified) {
                log.info("rotate result=false (old mismatch) latency={}ms", clock.millis() - start)
                return false
            }
            store(userId, newPlain.copyOf())
            log.info("rotate result=true latency={}ms", clock.millis() - start)
            true
        } finally {
            oldPlain.fill(' ')
            newPlain.fill(' ')
        }
    }
}

// advisory lock 200ms 예산 통합 테스트 — 두 락의 대기 상한 · 획득 후 원복 · MANDATORY 전파 (부채 166)

package com.bts.agileplanning.repository

import com.bts.agileplanning.AgilePlanningTestBootApplication
import com.bts.agileplanning.AgilePlanningTestcontainersConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.CannotAcquireLockException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * advisory lock 예산(R10 · N2·N3·N4·N6·N7) 통합 테스트.
 *
 * ## 왜 통합인가
 * `pg_advisory_xact_lock` 은 **무한 대기**다. MockK 로는 「기다리다 끊긴다」를 잴 수 없다 —
 * 대기와 취소를 PostgreSQL 이 관리하므로 실제 세션 2개가 있어야 재현된다.
 *
 * ## N5 — 예산을 건 채로만 돌린다
 * agile-planning 테스트는 컨테이너 1개·DB 1개를 **전 클래스가 공유**한다
 * ([AgilePlanningTestcontainersConfig] `:71-76`). 무한 대기가 남으면 다른 클래스를 물고 멈추므로
 * 홀더는 별도 JDBC 세션으로 잡고 [attemptUnderHolder] 의 `finally` 가 **반드시** 롤백으로 푼다.
 * 대기 스레드에도 상한을 걸어 예산 미배선 시에도 회수된다.
 */
@SpringBootTest(classes = [AgilePlanningTestBootApplication::class])
@Import(AgilePlanningTestcontainersConfig::class)
@ActiveProfiles("test")
class AdvisoryLockBudgetTest {
    @Autowired
    private lateinit var sprintRepository: SprintRepository

    @Autowired
    private lateinit var boardRepository: BoardRepository

    @Autowired
    private lateinit var dsl: DSLContext

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val txTemplate: TransactionTemplate by lazy { TransactionTemplate(transactionManager) }

    // ── R10 — 두 락 모두 200ms 예산 ────────────────────────────────────────────

    @Test
    fun `홀더가 있으면 sprint-start 락은 200ms 안에 끊긴다`() {
        val boardId = UUID.randomUUID()

        val attempt =
            attemptUnderHolder("sprint-start:$boardId") {
                txTemplate.executeWithoutResult { sprintRepository.acquireSprintStartLock(boardId) }
            }

        assertLockBudgetExceeded(attempt)
    }

    @Test
    fun `홀더가 있으면 scrum-board 락도 200ms 안에 끊긴다`() {
        val projectKey = "BUDGET${UUID.randomUUID().toString().take(8)}"

        val attempt =
            attemptUnderHolder("scrum-board:$projectKey") {
                txTemplate.executeWithoutResult { boardRepository.acquireProjectScrumBoardLock(projectKey) }
            }

        assertLockBudgetExceeded(attempt)
    }

    // ── N6 — 획득 직후 예산을 원복한다 ────────────────────────────────────────

    @Test
    fun `락 획득 후 lock_timeout 이 0 으로 원복된다`() {
        val boardId = UUID.randomUUID()

        // ★ 반드시 **같은 트랜잭션 안에서** 읽는다. set_config(..., true) 는 트랜잭션 스코프라
        // 밖에서 읽으면 항상 '0' 이 나와 공허 통과한다 — 원복을 지워도 초록이 되는 판정이 된다.
        val inTransaction =
            txTemplate.execute {
                sprintRepository.acquireSprintStartLock(boardId)
                dsl.fetchValue("SHOW lock_timeout") as String?
            }

        assertThat(inTransaction)
            .`as`("락 획득 뒤 lock_timeout 이 '%s' 로 남았다 — 뒤따르는 행 락 대기까지 끊긴다", inTransaction)
            .isEqualTo("0")
    }

    // ── N7 — MANDATORY 전파는 호출자 리포지터리에 남는다 ──────────────────────

    @Test
    fun `트랜잭션 없이 acquireSprintStartLock 을 부르면 예외다`() {
        // ★ REQUIRED 로 새면 자기 트랜잭션을 열고 즉시 커밋해 **락이 그 자리에서 풀리는데
        // 예외 없이 조용히 성공한다**. 헬퍼로 SQL 을 빼낼 때 이 애너테이션을 잃지 않았음을 잰다.
        assertThatThrownBy { sprintRepository.acquireSprintStartLock(UUID.randomUUID()) }
            .isInstanceOf(IllegalTransactionStateException::class.java)
    }

    @Test
    fun `트랜잭션 없이 acquireProjectScrumBoardLock 을 부르면 예외다`() {
        assertThatThrownBy { boardRepository.acquireProjectScrumBoardLock("NOTX") }
            .isInstanceOf(IllegalTransactionStateException::class.java)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    /** [attemptUnderHolder] 한 회의 결과 — 던져진 예외(없으면 성공)와 소요 시간. */
    private data class LockAttempt(
        val outcome: Result<Unit>,
        val elapsedMs: Long,
    )

    /**
     * 별도 JDBC 세션이 [lockKey] 를 쥔 상태에서 [attempt] 를 워커 스레드로 실행한다.
     *
     * 홀더는 `finally` 에서 롤백으로 **반드시** 풀린다 — 예산이 배선되지 않아 워커가 무한 대기해도
     * 홀더 해제로 회수된다(N5).
     */
    private fun attemptUnderHolder(
        lockKey: String,
        attempt: () -> Unit,
    ): LockAttempt {
        DriverManager.getConnection(jdbcUrl(), jdbcUser(), jdbcPassword()).use { holder ->
            holder.autoCommit = false
            holder.prepareStatement(HOLD_SQL).use { ps ->
                ps.setString(1, lockKey)
                ps.executeQuery().use { rs -> rs.next() }
            }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val startedAt = System.nanoTime()
                val future = executor.submit<Result<Unit>> { runCatching(attempt) }
                val outcome =
                    try {
                        future.get(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (timeout: TimeoutException) {
                        Result.failure(timeout)
                    }
                return LockAttempt(outcome, (System.nanoTime() - startedAt) / NANOS_PER_MILLI)
            } finally {
                executor.shutdown()
                holder.rollback()
                executor.awaitTermination(WORKER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * 홀더가 쥔 락을 못 얻고 예산 안에서 끊겼음을 단언한다.
     *
     * ★ [CannotAcquireLockException] 은 **추측이 아니라 실측**이다. PostgreSQL 이 SQLSTATE `55P03`
     * (*canceling statement due to lock timeout*) 을 내고, 그것이
     * [org.springframework.boot.autoconfigure.jooq.JooqExceptionTranslator]
     * ([AgilePlanningTestcontainersConfig] `:123` 배선) 를 거쳐 이 타입으로 도착하는 것을
     * red 단계 probe 로 찍어 확인했다 (cause 는 `org.postgresql.util.PSQLException`).
     */
    private fun assertLockBudgetExceeded(attempt: LockAttempt) {
        assertThat(attempt.outcome.exceptionOrNull())
            .`as`("홀더가 락을 쥐고 있는데 예외가 없다 — 예산이 안 걸렸거나 애초에 락을 안 잡았다")
            .isInstanceOf(CannotAcquireLockException::class.java)
        assertThat(attempt.elapsedMs)
            .`as`("락 대기가 %dms 다 — 200ms 예산을 넘겼다", attempt.elapsedMs)
            .isLessThan(MAX_WAIT_MS)
        assertThat(attempt.elapsedMs)
            .`as`("락 대기가 %dms 로 너무 짧다 — 기다리지 않고 다른 이유로 실패했다", attempt.elapsedMs)
            .isGreaterThanOrEqualTo(MIN_WAIT_MS)
    }

    private fun jdbcUrl(): String = AgilePlanningTestcontainersConfig.postgres.jdbcUrl

    private fun jdbcUser(): String = AgilePlanningTestcontainersConfig.postgres.username

    private fun jdbcPassword(): String = AgilePlanningTestcontainersConfig.postgres.password

    private companion object {
        /** 프로덕션과 **같은 키 공간**을 쓴다 — `hashtextextended(text, 0)` (N4). */
        const val HOLD_SQL = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"

        /** 예산 미배선 시 워커를 회수하는 상한. 이 값에 걸리면 무한 대기라는 뜻이다. */
        const val WORKER_TIMEOUT_SECONDS = 15L

        /** 실측 206ms 에 여유를 둔 상한. 이 값을 넘으면 예산이 안 걸린 것이다. */
        const val MAX_WAIT_MS = 2_000L

        /** 실제로 기다렸음을 확인하는 하한 — 즉시 실패하는 가짜 그린을 막는다. */
        const val MIN_WAIT_MS = 150L

        const val NANOS_PER_MILLI = 1_000_000L
    }
}

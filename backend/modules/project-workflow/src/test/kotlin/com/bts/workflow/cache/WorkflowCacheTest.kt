// WorkflowCache 4 case — miss / invalidate / advisory lock timeout / seed read 대기

package com.bts.workflow.cache

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.repository.WorkflowRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * WorkflowCache — 4 시나리오 검증.
 *
 * 시나리오 (1), (2) — Testcontainers PostgreSQL + 실제 WorkflowRepository.
 * 시나리오 (3)     — MockK DSLContext: pg_try_advisory_xact_lock false 반환 → timeout 예외.
 *                   (실제 멀티 connection lock contention 대신 협업 lock 실패 경로를 단위 검증)
 * 시나리오 (4)     — cache 에 미리 값 삽입 → withWriteLock block 실행 중 findByKey 가 cache hit 즉시 반환.
 *                   (advisory lock 은 findByKey 를 block 하지 않는다는 보장 — cache hit 경로)
 */
@Testcontainers
class WorkflowCacheTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        /** Testcontainers 기반 실제 WorkflowRepository (시나리오 1, 2 용). */
        private lateinit var realRepository: WorkflowRepository

        /** Testcontainers 기반 실제 DSLContext (시나리오 1, 2 용). */
        private lateinit var realDsl: org.jooq.DSLContext

        @JvmStatic
        @org.junit.jupiter.api.BeforeAll
        fun setupAll() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            seedWorkflow()

            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            realDsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            realRepository = WorkflowRepository(realDsl)
        }

        private fun seedWorkflow() {
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.autoCommit = false

                val wfId =
                    conn.prepareStatement(
                        "INSERT INTO workflows (key, name) VALUES ('software-default', '소프트웨어 기본') RETURNING id",
                    ).use { stmt ->
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getObject(1) as java.util.UUID
                        }
                    }

                val openId =
                    conn.prepareStatement(
                        "INSERT INTO workflow_states" +
                            " (workflow_id, key, name, category, display_order)" +
                            " VALUES (?, 'open', '열림', 'TODO', 0) RETURNING id",
                    ).use { stmt ->
                        stmt.setObject(1, wfId)
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getObject(1) as java.util.UUID
                        }
                    }

                val doneId =
                    conn.prepareStatement(
                        "INSERT INTO workflow_states" +
                            " (workflow_id, key, name, category, display_order)" +
                            " VALUES (?, 'done', '완료', 'DONE', 1) RETURNING id",
                    ).use { stmt ->
                        stmt.setObject(1, wfId)
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getObject(1) as java.util.UUID
                        }
                    }

                conn.prepareStatement(
                    "INSERT INTO workflow_transitions" +
                        " (workflow_id, from_state_id, to_state_id, name) VALUES (?, ?, ?, '완료')",
                ).use { stmt ->
                    stmt.setObject(1, wfId)
                    stmt.setObject(2, openId)
                    stmt.setObject(3, doneId)
                    stmt.executeUpdate()
                }

                conn.commit()
            }
        }

        /** 테스트용 Workflow 픽스처 (mock repository 반환값으로 사용). */
        private fun fixtureWorkflow(key: String = "test-workflow"): Workflow =
            Workflow.of(
                key = key,
                name = "테스트 워크플로우",
                states =
                    listOf(
                        WorkflowState(key = "open", name = "열림", category = StateCategory.TODO, displayOrder = 0),
                        WorkflowState(key = "done", name = "완료", category = StateCategory.DONE, displayOrder = 1),
                    ),
                transitions =
                    listOf(
                        WorkflowTransition(fromStateKey = "open", toStateKey = "done", name = "완료"),
                    ),
            )
    }

    // ── 시나리오 1, 2 — Testcontainers 기반 ─────────────────────────────────────

    @Nested
    inner class IntegrationCases {
        private lateinit var cache: WorkflowCache

        @BeforeEach
        fun setup() {
            cache = WorkflowCache(realRepository, realDsl)
        }

        @Test
        fun `시나리오 1 - cache miss 시 DB 조회 후 cache 에 보존`() {
            // 첫 조회 — cache miss → DB 적재
            val first = cache.findByKey("software-default")
            assertThat(first).isNotNull
            assertThat(first!!.key).isEqualTo("software-default")

            // 두 번째 조회 — cache hit (spy 없이 동작 자체로 검증: invalidate 없으면 동일 객체 참조)
            val second = cache.findByKey("software-default")
            assertThat(second).isSameAs(first)
        }

        @Test
        fun `시나리오 2 - invalidate 후 재조회 시 DB 다시 조회`() {
            // 첫 조회로 cache 채움
            val first = cache.findByKey("software-default")
            assertThat(first).isNotNull

            // invalidate — cache 에서 제거
            cache.invalidate("software-default")

            // 재조회 — cache miss 이므로 DB 다시 조회 (null 아님 = DB 재조회 성공)
            val afterInvalidate = cache.findByKey("software-default")
            assertThat(afterInvalidate).isNotNull
            assertThat(afterInvalidate!!.key).isEqualTo("software-default")
        }
    }

    // ── 시나리오 3 — MockK 기반 advisory lock timeout ────────────────────────────

    @Nested
    inner class AdvisoryLockTimeoutCase {
        @Test
        fun `시나리오 3 - pg_try_advisory_xact_lock false 반환 시 200ms 후 WorkflowCacheLockTimeoutException`() {
            val mockRepo = mockk<WorkflowRepository>()
            val mockDsl = mockk<org.jooq.DSLContext>()

            // pg_try_advisory_xact_lock 이 항상 false 반환하도록 stub
            // (다른 트랜잭션이 이미 lock 을 점유한 상황을 모사)
            every { mockDsl.fetchValue(any<String>(), any<Long>()) } returns false

            val cache = WorkflowCache(mockRepo, mockDsl)

            assertThatThrownBy {
                cache.withWriteLock("lock-contention-key") { /* 실행 안 됨 */ }
            }
                .isInstanceOf(WorkflowCacheLockTimeoutException::class.java)
                .satisfies({ ex ->
                    ex as WorkflowCacheLockTimeoutException
                    assertThat(ex.workflowKey).isEqualTo("lock-contention-key")
                    assertThat(ex.timeoutMillis).isEqualTo(200L)
                })
        }
    }

    // ── 시나리오 4 — cache hit 은 advisory lock 과 무관 ──────────────────────────

    @Nested
    inner class SeedReadDuringLockCase {
        @Test
        fun `시나리오 4 - withWriteLock block 실행 중 findByKey 는 cache hit 으로 즉시 반환`() {
            val mockRepo = mockk<WorkflowRepository>()
            val mockDsl = mockk<org.jooq.DSLContext>()

            // lock 획득 성공
            every { mockDsl.fetchValue(any<String>(), any<Long>()) } returns true

            val cache = WorkflowCache(mockRepo, mockDsl)
            val fixture = fixtureWorkflow("cached-workflow")

            // cache 에 미리 값 삽입 (withWriteLock 진행 중 상황을 단일 스레드로 재현)
            cache.injectForTest("cached-workflow", fixture)

            var readResultDuringLock: Workflow? = null
            cache.withWriteLock("cached-workflow") {
                // block 내부에서 findByKey — advisory lock 과 관계없이 cache hit 즉시 반환해야 함
                readResultDuringLock = cache.findByKey("cached-workflow")
            }

            // block 내부에서 cache hit 반환 확인 (DB 미호출)
            assertThat(readResultDuringLock).isNotNull
            assertThat(readResultDuringLock!!.key).isEqualTo("cached-workflow")
            verify(exactly = 0) { mockRepo.findByKey(any()) }

            // withWriteLock 완료 후 cache 에서 해당 키 제거 확인 (invalidate 호출됨)
            clearMocks(mockRepo)
            every { mockRepo.findByKey("cached-workflow") } returns null
            val afterLock = cache.findByKey("cached-workflow")
            assertThat(afterLock).isNull()
            verify(exactly = 1) { mockRepo.findByKey("cached-workflow") }
        }
    }
}

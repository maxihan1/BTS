// slack_interaction_log 기록 통합 테스트 — record() insert + 컬럼 영속/널 처리/값 검증 (FR-SL-05 Task 4)

package com.bts.slack.persistence

import com.bts.slack.application.SlackInteractionLogRepository
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import javax.sql.DataSource

/**
 * [JdbcSlackInteractionLogRepository.record] 통합 테스트 (FR-SL-05 Task 4).
 *
 * [JdbcSlackUserMappingRepositoryReverseTest] 와 동형 — Spring 컨텍스트 없이 Testcontainers PostgreSQL 에
 * V700~V703 마이그레이션(V703 = slack_interaction_log 상호작용 로그 테이블)을 직접 적용하고
 * [NamedParameterJdbcTemplate] 을 손수 구성해 리포지토리를 직접 생성한다.
 *
 * V701 이 q_slack_deliveries pgmq 큐(CREATE EXTENSION pgmq)를 만들므로 pgmq 바이너리가 포함된
 * `quay.io/tembo/pg16-pgmq` 이미지를 사용한다(다른 slack persistence 테스트 선례).
 *
 * ## 검증 시나리오
 * - 연결된 사용자(bts_user_id 존재) 상호작용을 모든 필드와 함께 기록 → 행 영속 + 값 정합.
 * - 미연결 사용자(bts_user_id null · issue_key null, UNMAPPED) → nullable 컬럼이 null 로 영속.
 * - action_type / outcome 값이 기록된 그대로 저장(로그 원문 보존).
 */
class JdbcSlackInteractionLogRepositoryTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * (memory: concurrent-testcontainers-suite-flaky — singleton 패턴 표준.)
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_slack_interaction_log_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        @JvmStatic
        private var migrated = false

        /** Flyway V700~V703 마이그레이션을 JVM 당 1회만 실행한다. */
        @JvmStatic
        @Synchronized
        fun migrateOnce() {
            if (migrated) return
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/slack-integration")
                .load()
                .migrate()
            migrated = true
        }
    }

    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var repository: SlackInteractionLogRepository

    @BeforeEach
    fun setUp() {
        migrateOnce()
        val dataSource: DataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        jdbc = NamedParameterJdbcTemplate(dataSource)
        jdbc.update("DELETE FROM slack_interaction_log", emptyMap<String, Any>())
        repository = JdbcSlackInteractionLogRepository(jdbc)
    }

    private fun fetchSingleRow(): Map<String, Any?> =
        jdbc.queryForMap(
            "SELECT id, team_id, slack_user_id, bts_user_id, action_type, outcome, issue_key, created_at" +
                " FROM slack_interaction_log",
            emptyMap<String, Any>(),
        )

    // ── 연결된 사용자 — 모든 필드 기록 ─────────────────────────────────────────

    @Test
    fun `record — 연결된 사용자의 상호작용을 모든 필드와 함께 기록한다`() {
        val btsUserId = UUID.randomUUID()

        repository.record(
            teamId = "T_WORKSPACE_A",
            slackUserId = "U0AAA111",
            btsUserId = btsUserId,
            actionType = "COMPLETE",
            outcome = "SUCCESS",
            issueKey = "ATLAS-42",
        )

        val row = fetchSingleRow()
        assertThat(row["id"]).isInstanceOf(UUID::class.java)
        assertThat(row["team_id"]).isEqualTo("T_WORKSPACE_A")
        assertThat(row["slack_user_id"]).isEqualTo("U0AAA111")
        assertThat(row["bts_user_id"]).isEqualTo(btsUserId)
        assertThat(row["action_type"]).isEqualTo("COMPLETE")
        assertThat(row["outcome"]).isEqualTo("SUCCESS")
        assertThat(row["issue_key"]).isEqualTo("ATLAS-42")
        assertThat(row["created_at"]).isNotNull()
    }

    // ── 미연결 사용자 — bts_user_id / issue_key null (UNMAPPED) ────────────────

    @Test
    fun `record — 미연결 사용자는 bts_user_id 와 issue_key 를 null 로 기록한다`() {
        repository.record(
            teamId = "T_WORKSPACE_B",
            slackUserId = "U0BBB222",
            btsUserId = null,
            actionType = "VIEW",
            outcome = "UNMAPPED",
            issueKey = null,
        )

        val row = fetchSingleRow()
        assertThat(row["team_id"]).isEqualTo("T_WORKSPACE_B")
        assertThat(row["slack_user_id"]).isEqualTo("U0BBB222")
        assertThat(row["bts_user_id"]).isNull()
        assertThat(row["action_type"]).isEqualTo("VIEW")
        assertThat(row["outcome"]).isEqualTo("UNMAPPED")
        assertThat(row["issue_key"]).isNull()
    }

    // ── action_type / outcome 값 원문 보존 ────────────────────────────────────

    @Test
    fun `record — action_type 와 outcome 값을 기록된 그대로 저장한다`() {
        repository.record(
            teamId = "T_WORKSPACE_C",
            slackUserId = "U0CCC333",
            btsUserId = UUID.randomUUID(),
            actionType = "ASSIGN",
            outcome = "PERMISSION_DENIED",
            issueKey = "ATLAS-7",
        )

        val row = fetchSingleRow()
        assertThat(row["action_type"]).isEqualTo("ASSIGN")
        assertThat(row["outcome"]).isEqualTo("PERMISSION_DENIED")
    }
}

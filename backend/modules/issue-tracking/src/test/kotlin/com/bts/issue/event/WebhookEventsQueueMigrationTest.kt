// V034 마이그레이션 검증 — q_webhook_events pgmq 큐 존재 + send/read 왕복 가능 확인 (FR-API-03 PR3)

package com.bts.issue.event

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * Flyway V001~V034 전체 마이그레이션 체인 적용 후 V034 가 생성하는 `q_webhook_events` pgmq 큐를 검증한다.
 * `SummaryTrgmIndexMigrationTest`/`V028MigrationTest` 패턴 미러 — Spring 컨텍스트 없이 Testcontainers 직접 실행.
 *
 * `q_webhook_events` 는 issue-tracking `IssueEventPublisher` 의 dual-send 대상 큐다(FR-API-03 PR3 Task 2).
 * PUBLISHABLE 이벤트(issue.created/issue.transitioned)를 search-export-import Webhook 발송 워커가
 * 이 큐를 폴링해 소비한다 — 기존 `q_issue_events` 는 NotificationWorker 가 독점 소비하므로 경합을 피하기
 * 위해 전용 큐로 분리한다(ADR 확정, 본 테스트 범위 외).
 *
 * 검증 범위.
 * (a) `pgmq.list_queues()` 에 `q_webhook_events` 존재
 * (b) `pgmq.send` 로 넣은 메시지를 `pgmq.read` 로 그대로 읽을 수 있음 (round-trip)
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 */
@Testcontainers
class WebhookEventsQueueMigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 이후 전체 체인이 pgmq 확장을 요구하므로 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun setup() {
            // 전체 마이그레이션 체인(V001~V034) 적용 — target 미지정.
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun conn() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    @Suppress("NestedBlockDepth")
    private fun pgmqQueueExists(queueName: String): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM pgmq.list_queues() WHERE queue_name = ?",
            ).use { stmt ->
                stmt.setString(1, queueName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    /** `pgmq.send` 로 [payloadJson] 을 [queueName] 에 넣고 생성된 `msg_id` 를 반환한다. */
    @Suppress("NestedBlockDepth")
    private fun sendMessage(
        queueName: String,
        payloadJson: String,
    ): Long =
        conn().use { c ->
            c.prepareStatement("SELECT * FROM pgmq.send(?, ?::jsonb)").use { stmt ->
                stmt.setString(1, queueName)
                stmt.setString(2, payloadJson)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }

    /** `pgmq.read` 로 [queueName] 에서 메시지 1건을 읽어 `message`(jsonb) 컬럼 텍스트를 반환한다. */
    @Suppress("NestedBlockDepth")
    private fun readMessage(queueName: String): String? =
        conn().use { c ->
            c.prepareStatement("SELECT message FROM pgmq.read(?, ?, ?)").use { stmt ->
                stmt.setString(1, queueName)
                stmt.setInt(2, 1)
                stmt.setInt(3, 1)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** 테스트 독립성을 위해 [queueName] 을 비운다. */
    private fun purgeQueue(queueName: String) {
        conn().use { c ->
            c.prepareStatement("SELECT pgmq.purge_queue(?)").use { stmt ->
                stmt.setString(1, queueName)
                stmt.executeQuery().use { it.next() }
            }
        }
    }

    // ── (a) 큐 존재 검증 ──────────────────────────────────────────────────────

    @Test
    fun `V034 q_webhook_events 큐가 pgmq list_queues 에 존재한다`() {
        assertThat(pgmqQueueExists("q_webhook_events")).isTrue()
    }

    // ── (b) send round-trip 검증 ─────────────────────────────────────────────

    @Test
    fun `V034 q_webhook_events 큐에 send 한 메시지를 read 로 그대로 읽을 수 있다`() {
        purgeQueue("q_webhook_events")

        val msgId = sendMessage("q_webhook_events", """{"type":"issue.created","issueKey":"ATLAS-1"}""")
        assertThat(msgId).isGreaterThan(0)

        val message = readMessage("q_webhook_events")
        assertThat(message).isNotNull()
        assertThat(message).contains("issue.created")
        assertThat(message).contains("ATLAS-1")
    }
}

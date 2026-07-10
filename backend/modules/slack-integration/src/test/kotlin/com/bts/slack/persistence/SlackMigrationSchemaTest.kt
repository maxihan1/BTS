// V701 마이그레이션 검증 — user_slack_mapping(사용자↔Slack 매핑) + slack_delivery_log(전송 멱등 dedup) (FR-SL-02)

package com.bts.slack.persistence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * Flyway V701 마이그레이션 적용 후 FR-SL-02 신설 두 테이블을 검증한다.
 * - `user_slack_mapping` — Slack DM 대상 해석용 사용자(user_id)↔Slack(slack_user_id/team_id) 매핑.
 * - `slack_delivery_log` — Slack 전송 멱등 dedup 로그(dedup_key PK 재삽입 거부로 중복 발송 차단).
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을 직접 사용하며
 * Spring 컨텍스트 없이 실행한다. slack-integration BC 마이그레이션은 pgmq 확장을 요구하지 않으므로
 * postgres:16-alpine 이미지로 충분하다([SlackInstallSchemaMigrationTest] 동일 패턴).
 *
 * **JVM 단위 singleton container 패턴** — companion object `.apply { start() }` 로 JVM 라이프사이클에 바인딩.
 * `@Container` 라이프사이클 대신 Ryuk 의 JVM 종료 시 자동 정리에 위임해 동시 suite flaky 를 회피한다
 * (메모리 concurrent-testcontainers-suite-flaky / [SlackInstallSchemaMigrationTest] 동일 패턴).
 *
 * 검증 범위 (FR-SL-02 plan Task 4 / DATA.md §4 TIMESTAMPTZ 강제).
 * - user_slack_mapping 테이블 + 4개 컬럼(user_id, slack_user_id, team_id, linked_at)
 * - user_id = uuid PK NOT NULL — Slack DM 대상은 PK user_id 경유 조회(별도 인덱스 불요)
 * - slack_user_id / team_id = text NOT NULL
 * - linked_at = timestamptz NOT NULL DEFAULT now() (DATA.md §4.1#4 — TIMESTAMP without tz 금지)
 * - slack_delivery_log 테이블 + 2개 컬럼(dedup_key, sent_at)
 * - dedup_key = text PK NOT NULL — 재삽입 거부(전송 멱등 dedup 의 근거)
 * - sent_at = timestamptz NOT NULL DEFAULT now()
 *
 * 정보 스키마(information_schema / pg_constraint) 조회로 단언한다. SQL 문자열 결합 없이 prepared statement 사용.
 */
class SlackMigrationSchemaTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리한다.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_slack_notify_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        // user_slack_mapping 이 보유해야 하는 4개 컬럼 (V701 DDL 정합).
        private val USER_SLACK_MAPPING_COLUMNS =
            listOf(
                "user_id",
                "slack_user_id",
                "team_id",
                "linked_at",
            )

        // slack_delivery_log 가 보유해야 하는 2개 컬럼 (V701 DDL 정합).
        private val SLACK_DELIVERY_LOG_COLUMNS =
            listOf(
                "dedup_key",
                "sent_at",
            )

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/slack-integration")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun tableExists(tableName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    @Suppress("NestedBlockDepth")
    private fun columnsOf(tableName: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT column_name FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val cols = mutableListOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols
                }
            }
        }

    // Connection → prepareStatement → executeQuery 3중 use 블록 중첩. SQL 헬퍼의 관용적 패턴이므로 Suppress.
    @Suppress("NestedBlockDepth")
    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Suppress("NestedBlockDepth")
    private fun columnIsNullable(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Suppress("NestedBlockDepth")
    private fun columnDefault(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT column_default FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // 지정 테이블의 PRIMARY KEY(contype = 'p') 구성 컬럼명 목록 조회 — user_id / dedup_key PK 검증용.
    @Suppress("NestedBlockDepth")
    private fun primaryKeyColumns(tableName: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT a.attname FROM pg_constraint c" +
                    " JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)" +
                    " WHERE c.contype = 'p' AND c.conrelid = ?::regclass",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val cols = mutableListOf<String>()
                    while (rs.next()) cols.add(rs.getString(1))
                    cols
                }
            }
        }

    // slack_delivery_log 한 행 INSERT — dedup_key PK 재삽입 위반 유도용(전송 멱등 dedup 검증).
    private fun insertDeliveryLog(dedupKey: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("INSERT INTO slack_delivery_log (dedup_key) VALUES (?)").use { stmt ->
                stmt.setString(1, dedupKey)
                stmt.executeUpdate()
            }
        }
    }

    // user_slack_mapping 한 행 INSERT — user_id PK 재삽입 위반 유도용. NOT NULL 컬럼을 모두 채운다.
    private fun insertMapping(userId: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO user_slack_mapping (user_id, slack_user_id, team_id) VALUES (?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, userId)
                stmt.setString(2, "U0USER")
                stmt.setString(3, "T_WORKSPACE_A")
                stmt.executeUpdate()
            }
        }
    }

    // ── user_slack_mapping 테이블 / 컬럼 검증 ──────────────────────────────────

    @Test
    fun `V701 user_slack_mapping 테이블 존재`() {
        assertThat(tableExists("user_slack_mapping")).isTrue()
    }

    @Test
    fun `V701 user_slack_mapping 4개 컬럼 존재`() {
        assertThat(columnsOf("user_slack_mapping"))
            .containsExactlyInAnyOrderElementsOf(USER_SLACK_MAPPING_COLUMNS)
    }

    @Test
    fun `V701 user_slack_mapping user_id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("user_slack_mapping", "user_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("user_slack_mapping", "user_id")).isEqualTo("NO")
        assertThat(primaryKeyColumns("user_slack_mapping")).containsExactly("user_id")
    }

    @Test
    fun `V701 user_slack_mapping slack_user_id 는 text NOT NULL`() {
        assertThat(columnDataType("user_slack_mapping", "slack_user_id")).isEqualTo("text")
        assertThat(columnIsNullable("user_slack_mapping", "slack_user_id")).isEqualTo("NO")
    }

    @Test
    fun `V701 user_slack_mapping team_id 는 text NOT NULL`() {
        assertThat(columnDataType("user_slack_mapping", "team_id")).isEqualTo("text")
        assertThat(columnIsNullable("user_slack_mapping", "team_id")).isEqualTo("NO")
    }

    @Test
    fun `V701 user_slack_mapping linked_at 은 timestamptz NOT NULL DEFAULT now`() {
        assertThat(columnDataType("user_slack_mapping", "linked_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("user_slack_mapping", "linked_at")).isEqualTo("NO")
        assertThat(columnDefault("user_slack_mapping", "linked_at")).isEqualTo("now()")
    }

    @Test
    fun `V701 같은 user_id 중복 INSERT 는 PK 위반`() {
        val userId = UUID.randomUUID()
        insertMapping(userId)
        // 같은 user_id 재삽입 시 PK 위반 — 사용자당 매핑 1행 보장.
        assertThatThrownBy { insertMapping(userId) }
            .hasMessageContaining("user_slack_mapping_pkey")
    }

    // ── slack_delivery_log 테이블 / 컬럼 검증 ──────────────────────────────────

    @Test
    fun `V701 slack_delivery_log 테이블 존재`() {
        assertThat(tableExists("slack_delivery_log")).isTrue()
    }

    @Test
    fun `V701 slack_delivery_log 2개 컬럼 존재`() {
        assertThat(columnsOf("slack_delivery_log"))
            .containsExactlyInAnyOrderElementsOf(SLACK_DELIVERY_LOG_COLUMNS)
    }

    @Test
    fun `V701 slack_delivery_log dedup_key 는 text PK NOT NULL`() {
        assertThat(columnDataType("slack_delivery_log", "dedup_key")).isEqualTo("text")
        assertThat(columnIsNullable("slack_delivery_log", "dedup_key")).isEqualTo("NO")
        assertThat(primaryKeyColumns("slack_delivery_log")).containsExactly("dedup_key")
    }

    @Test
    fun `V701 slack_delivery_log sent_at 은 timestamptz NOT NULL DEFAULT now`() {
        assertThat(columnDataType("slack_delivery_log", "sent_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("slack_delivery_log", "sent_at")).isEqualTo("NO")
        assertThat(columnDefault("slack_delivery_log", "sent_at")).isEqualTo("now()")
    }

    @Test
    fun `V701 같은 dedup_key 중복 INSERT 는 PK 위반 (전송 멱등 dedup)`() {
        insertDeliveryLog("issue-123:assigned:U0USER")
        // 같은 dedup_key 재삽입 시 PK 위반 — 중복 Slack 발송 차단(전송 멱등)의 근거.
        assertThatThrownBy { insertDeliveryLog("issue-123:assigned:U0USER") }
            .hasMessageContaining("slack_delivery_log_pkey")
    }
}

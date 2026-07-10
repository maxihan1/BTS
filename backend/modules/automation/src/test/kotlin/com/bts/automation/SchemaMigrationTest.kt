// V300/V301 마이그레이션 검증 — automation_rules 컬럼 세트 + q_automation_execution 큐 존재 확인 (FR-AT-01 Task 2)

package com.bts.automation

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
 * Flyway V300~V301 마이그레이션 적용 후 automation BC 스키마(automation_rules 테이블 + q_automation_execution
 * pgmq 큐)를 검증한다 (FR-AT-01 Task 2).
 *
 * ## 이미지 선택 — 왜 [AutomationTestcontainersBase] 를 상속/재사용하지 않는가
 * [AutomationTestcontainersBase] 의 컨테이너는 `postgres:16-alpine` 이며 pgmq 확장을 **탑재하지 않는다**.
 * 이 테스트는 V301 `SELECT pgmq.create('q_automation_execution')` 를 검증해야 하므로 pgmq 확장이
 * 사전 설치된 `quay.io/tembo/pg16-pgmq` 이미지가 필요하다. 따라서 issue-tracking
 * `WebhookEventsQueueMigrationTest`(q_webhook_events 큐 검증) 선례를 그대로 미러해 Spring 컨텍스트 없이
 * 전용 tembo 컨테이너로 Flyway 를 직접 구성해 `.migrate()` 한다.
 * (ADR 2026-05-22-pgmq-postgres-image 동일 결정.)
 *
 * ## JVM 단위 singleton container 패턴 ([[concurrent-testcontainers-suite-flaky]])
 * companion object `.apply { start() }` 로 JVM 라이프사이클에 바인딩한다. `@Container` 대신 Ryuk 의 JVM
 * 종료 시 자동 정리에 위임해 동시 suite flaky 를 회피한다(SlackInstallSchemaMigrationTest 동형).
 *
 * ## 검증 범위 (plan Task 2 / ADR D1·D4 / DATA.md §4 TIMESTAMPTZ 강제)
 * - automation_rules 테이블 존재 + 13개 컬럼(ADR D1 트리거 전용 스키마 정합)
 * - id = uuid PK NOT NULL / created_by = uuid NOT NULL(cross-BC user id, BC 격리로 FK 아님)
 * - project_key / name / trigger_type = character varying NOT NULL
 * - enabled = boolean NOT NULL DEFAULT true
 * - trigger_config = jsonb NOT NULL DEFAULT '{}'
 * - webhook_token_hash = character varying NULL(WEBHOOK 전용) / next_fire_at = timestamptz NULL(SCHEDULED 전용)
 * - created_at / updated_at = timestamptz NOT NULL(DATA.md §4.1#4 — TIMESTAMP without tz 금지)
 * - version = bigint NOT NULL DEFAULT 0(OCC 낙관적 잠금)
 * - deleted_at = timestamptz NULL(소프트 삭제)
 * - trigger_type CHECK 제약(5종 화이트리스트) — 잘못된 값 거부
 * - q_automation_execution 큐 존재(automation 소유, FR-AT-02 액션 executor 가 소비)
 * - **q_automation_events 는 automation 이 생성하지 않음**(plan-eng-review E1 — producer 인 issue-tracking 이
 *   Task 10 에서 소유·생성. automation 마이그레이션 경계 명시)
 *
 * 정보 스키마(information_schema / pg_constraint / pgmq.list_queues) 조회로 단언한다.
 * SQL 문자열 결합 없이 prepared statement 파라미터 바인딩만 사용한다.
 */
class SchemaMigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V301 pgmq.create 가 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_automation_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        // automation_rules 가 보유해야 하는 13개 컬럼 (ADR D1 트리거 전용 스키마 정합).
        private val AUTOMATION_RULES_COLUMNS =
            listOf(
                "id",
                "project_key",
                "name",
                "enabled",
                "trigger_type",
                "trigger_config",
                "webhook_token_hash",
                "next_fire_at",
                "created_by",
                "created_at",
                "updated_at",
                "version",
                "deleted_at",
            )

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/automation")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun conn() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun tableExists(tableName: String): Boolean =
        conn().use { c ->
            c.prepareStatement(
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
        conn().use { c ->
            c.prepareStatement(
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

    // Connection → prepareStatement → executeQuery 3중 use 중첩은 SQL 헬퍼의 관용적 패턴이므로 Suppress.
    @Suppress("NestedBlockDepth")
    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        conn().use { c ->
            c.prepareStatement(
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
        conn().use { c ->
            c.prepareStatement(
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
        conn().use { c ->
            c.prepareStatement(
                "SELECT column_default FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

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

    // automation_rules 한 행 INSERT — NOT NULL 이면서 DEFAULT 가 없는 최소 컬럼만 채운다.
    // (project_key / name / trigger_type / created_by. 나머지는 DEFAULT.)
    private fun insertRule(triggerType: String) {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO automation_rules (project_key, name, trigger_type, created_by)" +
                    " VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, "ATLAS")
                stmt.setString(2, "자동 라벨 부여 룰")
                stmt.setString(3, triggerType)
                stmt.setObject(4, UUID.randomUUID())
                stmt.executeUpdate()
            }
        }
    }

    // ── 테이블 / 컬럼 존재 검증 ────────────────────────────────────────────────

    @Test
    fun `V300 automation_rules 테이블 존재`() {
        assertThat(tableExists("automation_rules")).isTrue()
    }

    @Test
    fun `V300 automation_rules 13개 컬럼 존재`() {
        assertThat(columnsOf("automation_rules"))
            .containsExactlyInAnyOrderElementsOf(AUTOMATION_RULES_COLUMNS)
    }

    // ── 컬럼 타입 / NOT NULL 검증 ──────────────────────────────────────────────

    @Test
    fun `V300 id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("automation_rules", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("automation_rules", "id")).isEqualTo("NO")
    }

    @Test
    fun `V300 project_key 는 character varying NOT NULL`() {
        assertThat(columnDataType("automation_rules", "project_key")).isEqualTo("character varying")
        assertThat(columnIsNullable("automation_rules", "project_key")).isEqualTo("NO")
    }

    @Test
    fun `V300 name 은 character varying NOT NULL`() {
        assertThat(columnDataType("automation_rules", "name")).isEqualTo("character varying")
        assertThat(columnIsNullable("automation_rules", "name")).isEqualTo("NO")
    }

    @Test
    fun `V300 enabled 는 boolean NOT NULL DEFAULT true`() {
        assertThat(columnDataType("automation_rules", "enabled")).isEqualTo("boolean")
        assertThat(columnIsNullable("automation_rules", "enabled")).isEqualTo("NO")
        assertThat(columnDefault("automation_rules", "enabled")).isEqualTo("true")
    }

    @Test
    fun `V300 trigger_type 은 character varying NOT NULL`() {
        assertThat(columnDataType("automation_rules", "trigger_type")).isEqualTo("character varying")
        assertThat(columnIsNullable("automation_rules", "trigger_type")).isEqualTo("NO")
    }

    @Test
    fun `V300 trigger_config 는 jsonb NOT NULL DEFAULT 빈 객체`() {
        assertThat(columnDataType("automation_rules", "trigger_config")).isEqualTo("jsonb")
        assertThat(columnIsNullable("automation_rules", "trigger_config")).isEqualTo("NO")
        assertThat(columnDefault("automation_rules", "trigger_config")).contains("'{}'")
    }

    @Test
    fun `V300 webhook_token_hash 는 character varying NULL (WEBHOOK 전용)`() {
        assertThat(columnDataType("automation_rules", "webhook_token_hash")).isEqualTo("character varying")
        assertThat(columnIsNullable("automation_rules", "webhook_token_hash")).isEqualTo("YES")
    }

    @Test
    fun `V300 next_fire_at 은 timestamptz NULL (SCHEDULED 전용)`() {
        assertThat(columnDataType("automation_rules", "next_fire_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_rules", "next_fire_at")).isEqualTo("YES")
    }

    @Test
    fun `V300 created_by 는 uuid NOT NULL`() {
        assertThat(columnDataType("automation_rules", "created_by")).isEqualTo("uuid")
        assertThat(columnIsNullable("automation_rules", "created_by")).isEqualTo("NO")
    }

    @Test
    fun `V300 version 은 bigint NOT NULL DEFAULT 0 (OCC)`() {
        assertThat(columnDataType("automation_rules", "version")).isEqualTo("bigint")
        assertThat(columnIsNullable("automation_rules", "version")).isEqualTo("NO")
        assertThat(columnDefault("automation_rules", "version")).isEqualTo("0")
    }

    // ── timestamptz 강제 검증 (DATA.md §4 — TIMESTAMP without tz 금지) ──────────

    @Test
    fun `V300 created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("automation_rules", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_rules", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V300 updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("automation_rules", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_rules", "updated_at")).isEqualTo("NO")
    }

    @Test
    fun `V300 deleted_at 은 timestamptz NULL (소프트 삭제)`() {
        assertThat(columnDataType("automation_rules", "deleted_at")).isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("automation_rules", "deleted_at")).isEqualTo("YES")
    }

    // ── trigger_type CHECK 제약 검증 (5종 화이트리스트) ─────────────────────────

    @Test
    fun `V300 유효한 trigger_type 5종은 INSERT 허용`() {
        listOf("ISSUE_CREATED", "ISSUE_UPDATED", "ISSUE_COMMENTED", "SCHEDULED", "WEBHOOK")
            .forEach { insertRule(it) }
    }

    @Test
    fun `V300 정의되지 않은 trigger_type 은 CHECK 제약 위반`() {
        assertThatThrownBy { insertRule("PR_MERGED") }
            .hasMessageContaining("ck_automation_rules_trigger_type")
    }

    // ── pgmq 큐 검증 (ADR D4 — automation 소유 실행 큐 / E1 — events 큐는 비소유) ─

    @Test
    fun `V301 q_automation_execution 큐가 pgmq list_queues 에 존재한다`() {
        assertThat(pgmqQueueExists("q_automation_execution")).isTrue()
    }

    @Test
    fun `automation 마이그레이션은 q_automation_events 큐를 생성하지 않는다 (E1 — issue-tracking 소유)`() {
        assertThat(pgmqQueueExists("q_automation_events"))
            .`as`("q_automation_events 는 producer 인 issue-tracking(Task 10)이 소유·생성한다 — automation 경계 밖")
            .isFalse()
    }
}

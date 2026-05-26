// V001 마이그레이션 검증 — Testcontainers postgres + Flyway migrate 후 information_schema 조회로 5 테이블 + 6 인덱스 존재 확인

package com.bts.workflow.db

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
 * Flyway V001 마이그레이션 적용 후 5 테이블 + 6 인덱스 존재를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위.
 * - 5 테이블 존재 (workflows, workflow_states, workflow_transitions, workflow_validators, workflow_post_actions)
 * - 6 인덱스 존재 (FK 컬럼 전부 포함 — DATA.md §7, CONCERN-6 해소)
 * - TIMESTAMPTZ 타입 강제 (DATA.md §4 — TIMESTAMP without time zone 금지)
 *
 * 이미지 변경 이유 (V004 추가 후).
 * V004 마이그레이션이 pgmq 확장(CREATE EXTENSION pgmq) + pgmq.create() 를 사용하므로
 * postgres:16-alpine 으로는 전체 마이그레이션 체인(V001~V004) 실행 불가.
 * quay.io/tembo/pg16-pgmq:latest 로 변경 (ADR 2026-05-22-pgmq-postgres-image 동일 결정).
 *
 * 참조. FR-WF-01 / ADR 2026-05-21-v001-initial-schema-non-concurrent.
 */
@Testcontainers
class V001MigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V004 pgmq 확장 요구로 인해 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
        // ADR 2026-05-22-pgmq-postgres-image 와 동일 패턴.
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
        fun applyMigrations() {
            // Flyway 2단계 — V004 issue_types cross-BC FK 대응
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate()

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                }
            }

            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/project-workflow")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun tableExists(tableName: String): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun indexExists(indexName: String): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes" +
                    " WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? {
        val conn = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        return conn.use { queryColumnDataType(it, tableName, columnName) }
    }

    private fun queryColumnDataType(
        conn: java.sql.Connection,
        tableName: String,
        columnName: String,
    ): String? {
        val sql =
            "SELECT data_type FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, tableName)
            stmt.setString(2, columnName)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    // ── 테이블 5개 존재 검증 ──────────────────────────────────────────────────

    @Test
    fun `V001 workflows 테이블 존재`() {
        assertThat(tableExists("workflows")).isTrue()
    }

    @Test
    fun `V001 workflow_states 테이블 존재`() {
        assertThat(tableExists("workflow_states")).isTrue()
    }

    @Test
    fun `V001 workflow_transitions 테이블 존재`() {
        assertThat(tableExists("workflow_transitions")).isTrue()
    }

    @Test
    fun `V001 workflow_validators 테이블 존재`() {
        assertThat(tableExists("workflow_validators")).isTrue()
    }

    @Test
    fun `V001 workflow_post_actions 테이블 존재`() {
        assertThat(tableExists("workflow_post_actions")).isTrue()
    }

    // ── 인덱스 6개 존재 검증 (DATA.md §7 FK 인덱스 룰 — CONCERN-6 해소) ───────

    @Test
    fun `V001 idx_workflow_states_workflow 인덱스 존재`() {
        assertThat(indexExists("idx_workflow_states_workflow")).isTrue()
    }

    @Test
    fun `V001 idx_workflow_transitions_workflow 인덱스 존재`() {
        assertThat(indexExists("idx_workflow_transitions_workflow")).isTrue()
    }

    @Test
    fun `V001 idx_workflow_transitions_from 인덱스 존재`() {
        assertThat(indexExists("idx_workflow_transitions_from")).isTrue()
    }

    @Test
    fun `V001 idx_workflow_transitions_to 인덱스 존재`() {
        assertThat(indexExists("idx_workflow_transitions_to")).isTrue()
    }

    @Test
    fun `V001 idx_workflow_validators_transition 인덱스 존재`() {
        assertThat(indexExists("idx_workflow_validators_transition")).isTrue()
    }

    @Test
    fun `V001 idx_workflow_post_actions_transition 인덱스 존재`() {
        assertThat(indexExists("idx_workflow_post_actions_transition")).isTrue()
    }

    // ── 타임스탬프 컬럼 타입 검증 (DATA.md §4 — TIMESTAMPTZ 강제) ─────────────

    @Test
    fun `V001 workflows created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflows", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V001 workflows updated_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflows", "updated_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V001 workflow_states created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflow_states", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V001 workflow_transitions created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflow_transitions", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V001 workflow_validators created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflow_validators", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V001 workflow_post_actions created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("workflow_post_actions", "created_at"))
            .isEqualTo("timestamp with time zone")
    }
}

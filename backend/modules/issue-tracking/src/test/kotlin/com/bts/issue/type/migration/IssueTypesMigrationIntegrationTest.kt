// issue-tracking V003 마이그레이션 검증 — issue_types 테이블 + 5 표준 seed (is_standard=true) 존재 확인

package com.bts.issue.type.migration

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
 * Flyway V001~V003 마이그레이션 적용 후 issue_types 테이블과 5 표준 seed 를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위.
 * - issue_types 테이블에 정확히 5 row 존재 (epic, story, task, subtask, bug)
 * - 5 row 전부 is_standard = true
 * - ix_issue_types_key_active 부분 인덱스 존재
 * - TIMESTAMPTZ 타입 강제 (DATA.md §4)
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 *
 * 참조. spec §5.2.1 / FR-WF-02 cross-BC 사전 도입 / ADR issue-type-cross-bc-introduction.
 */
@Testcontainers
class IssueTypesMigrationIntegrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
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
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
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

    private fun indexExists(indexName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes" +
                    " WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

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

    private fun countIssueTypeRows(): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM issue_types").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun countStandardRows(): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM issue_types WHERE is_standard = true").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun issueTypeKeyExists(key: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM issue_types WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // ── 테이블 존재 검증 ──────────────────────────────────────────────────────

    @Test
    fun `V003 issue_types 테이블 존재`() {
        assertThat(tableExists("issue_types")).isTrue()
    }

    // ── seed row 수 검증 ──────────────────────────────────────────────────────

    @Test
    fun `V003 issue_types 에 정확히 5 row 존재`() {
        assertThat(countIssueTypeRows()).isEqualTo(5)
    }

    @Test
    fun `V003 모든 5 row 의 is_standard 는 true`() {
        assertThat(countStandardRows()).isEqualTo(5)
    }

    // ── 5 표준 key 존재 검증 ─────────────────────────────────────────────────

    @Test
    fun `V003 epic key 존재`() {
        assertThat(issueTypeKeyExists("epic")).isTrue()
    }

    @Test
    fun `V003 story key 존재`() {
        assertThat(issueTypeKeyExists("story")).isTrue()
    }

    @Test
    fun `V003 task key 존재`() {
        assertThat(issueTypeKeyExists("task")).isTrue()
    }

    @Test
    fun `V003 subtask key 존재`() {
        assertThat(issueTypeKeyExists("subtask")).isTrue()
    }

    @Test
    fun `V003 bug key 존재`() {
        assertThat(issueTypeKeyExists("bug")).isTrue()
    }

    // ── 인덱스 존재 검증 ─────────────────────────────────────────────────────

    @Test
    fun `V005 ux_issue_types_key_active 부분 unique 인덱스 존재`() {
        // V003 에서 생성된 ix_issue_types_key_active 는 V005 에서
        // ux_issue_types_key_active (partial unique) 로 교체되었다 (B1 BLOCKER 해소).
        assertThat(indexExists("ux_issue_types_key_active")).isTrue()
    }

    @Test
    fun `V005 ix_issue_types_key_active 는 ux 로 교체되어 존재하지 않는다`() {
        assertThat(indexExists("ix_issue_types_key_active")).isFalse()
    }

    // ── TIMESTAMPTZ 타입 검증 (DATA.md §4) ───────────────────────────────────

    @Test
    fun `V003 issue_types created_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("issue_types", "created_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V003 issue_types updated_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("issue_types", "updated_at"))
            .isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V003 issue_types deleted_at 은 TIMESTAMPTZ`() {
        assertThat(columnDataType("issue_types", "deleted_at"))
            .isEqualTo("timestamp with time zone")
    }
}

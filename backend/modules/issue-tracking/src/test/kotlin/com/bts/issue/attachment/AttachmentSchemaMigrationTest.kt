// issue-tracking V023 마이그레이션 검증 — issue_attachments 테이블(8컬럼 + FK 인덱스 + issue_id FK CASCADE) 존재 확인 (FR-AC-01)

package com.bts.issue.attachment

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
 * Flyway V001~V023 전체 마이그레이션 체인 적용 후 issue_attachments 테이블을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위 (FR-AC-01 Task 1).
 * - issue_attachments 테이블 존재
 * - 8개 컬럼 모두 존재 (id, issue_id, filename, content_type, size_bytes, storage_key, uploaded_by, created_at)
 * - deleted_at 컬럼 없음 (첨부는 하드 삭제 — ADR 2026-06-15-fr-ac-01-attachment-storage 확정)
 * - FK 인덱스 idx_issue_attachments_issue_id 존재 (DATA.md §7 — PostgreSQL FK 자동 인덱스 안 함)
 * - issue_id FK 가 issues(id) 참조 + ON DELETE CASCADE
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * IssueComponentsSchemaMigrationTest 와 동일 결정.
 *
 * 참조. FR-AC-01 plan Task 1 / DATA.md §7 FK 인덱스 / V023__issue_attachments.sql.
 */
@Testcontainers
class AttachmentSchemaMigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
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
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()
        }

        // issue_attachments 가 보유해야 하는 8개 컬럼 (deleted_at 미포함 — 하드 삭제).
        private val EXPECTED_COLUMNS =
            listOf(
                "id",
                "issue_id",
                "filename",
                "content_type",
                "size_bytes",
                "storage_key",
                "uploaded_by",
                "created_at",
            )
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

    // 주어진 테이블의 모든 컬럼명을 반환.
    // JDBC try-with-resources(.use) 중첩이 깊으나 테스트 헬퍼라 가독성 영향 적다.
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

    // 주어진 테이블의 FK 가 (로컬 컬럼 → 참조 테이블) 형태로 존재하는지 확인.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyExists(
        tableName: String,
        column: String,
        referencedTable: String,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                 AND tc.table_schema = ccu.table_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY'
                  AND kcu.column_name = ?
                  AND ccu.table_name = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, column)
                stmt.setString(3, referencedTable)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // 주어진 테이블의 FK delete rule(예: CASCADE)이 기대값과 일치하는지 확인.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyDeleteRule(
        tableName: String,
        column: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.referential_constraints rc
                  ON tc.constraint_name = rc.constraint_name
                 AND tc.table_schema = rc.constraint_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY'
                  AND kcu.column_name = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, column)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    // ── 테이블/컬럼/FK/인덱스 존재 검증 ────────────────────────────────────────

    @Test
    fun `V023 issue_attachments 테이블 존재`() {
        assertThat(tableExists("issue_attachments")).isTrue()
    }

    @Test
    fun `V023 issue_attachments 8개 컬럼 존재`() {
        assertThat(columnsOf("issue_attachments"))
            .containsExactlyInAnyOrderElementsOf(EXPECTED_COLUMNS)
    }

    @Test
    fun `V023 issue_attachments deleted_at 컬럼 없음 (하드 삭제)`() {
        assertThat(columnsOf("issue_attachments")).doesNotContain("deleted_at")
    }

    @Test
    fun `V023 FK 인덱스 idx_issue_attachments_issue_id 존재`() {
        assertThat(indexExists("idx_issue_attachments_issue_id")).isTrue()
    }

    @Test
    fun `V023 issue_attachments issue_id FK 는 issues 를 참조`() {
        assertThat(foreignKeyExists("issue_attachments", "issue_id", "issues")).isTrue()
    }

    @Test
    fun `V023 issue_attachments issue_id FK 는 ON DELETE CASCADE`() {
        assertThat(foreignKeyDeleteRule("issue_attachments", "issue_id")).isEqualTo("CASCADE")
    }
}

// issue-tracking V024 마이그레이션 검증 — issue_watchers 조인 테이블(3컬럼 + 복합 PK + issue_id FK 인덱스 + CASCADE) 존재 확인 (FR-WT-01)

package com.bts.issue.watcher

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
 * Flyway V001~V024 전체 마이그레이션 체인 적용 후 issue_watchers 테이블을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위 (FR-WT-01 Task 1).
 * - issue_watchers 테이블 존재
 * - 3개 컬럼 모두 존재 (issue_id, user_id, created_at)
 * - 복합 기본 키 (issue_id, user_id)
 * - deleted_at 컬럼 없음 (조인 테이블 — 하드 remove 정상, 소프트 삭제 미적용)
 * - FK 인덱스 idx_issue_watchers_issue 존재 (DATA.md §7 — PostgreSQL FK 자동 인덱스 안 함)
 * - issue_id FK 가 issues(id) 참조 + ON DELETE CASCADE
 * - user_id FK 없음 (identity-access BC 소유 — BC 격리, assignee_id 선례)
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * AttachmentSchemaMigrationTest 와 동일 결정.
 *
 * 참조. FR-WT-01 plan Task 1 / DATA.md §7 FK 인덱스 / V024__issue_watchers.sql.
 */
@Testcontainers
class WatcherSchemaMigrationTest {
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

        // issue_watchers 가 보유해야 하는 3개 컬럼 (deleted_at 미포함 — 조인 테이블).
        private val EXPECTED_COLUMNS =
            listOf(
                "issue_id",
                "user_id",
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

    // 주어진 테이블의 기본 키 컬럼들을 순서대로 반환 (복합 PK 검증용).
    @Suppress("NestedBlockDepth")
    private fun primaryKeyColumns(tableName: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'PRIMARY KEY'
                ORDER BY kcu.ordinal_position
                """.trimIndent(),
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

    // ── 테이블/컬럼/PK/FK/인덱스 존재 검증 ─────────────────────────────────────

    @Test
    fun `V024 issue_watchers 테이블 존재`() {
        assertThat(tableExists("issue_watchers")).isTrue()
    }

    @Test
    fun `V024 issue_watchers 3개 컬럼 존재`() {
        assertThat(columnsOf("issue_watchers"))
            .containsExactlyInAnyOrderElementsOf(EXPECTED_COLUMNS)
    }

    @Test
    fun `V024 issue_watchers deleted_at 컬럼 없음 (조인 테이블)`() {
        assertThat(columnsOf("issue_watchers")).doesNotContain("deleted_at")
    }

    @Test
    fun `V024 issue_watchers 복합 PK (issue_id, user_id)`() {
        assertThat(primaryKeyColumns("issue_watchers"))
            .containsExactly("issue_id", "user_id")
    }

    @Test
    fun `V024 FK 인덱스 idx_issue_watchers_issue 존재`() {
        assertThat(indexExists("idx_issue_watchers_issue")).isTrue()
    }

    @Test
    fun `V024 issue_watchers issue_id FK 는 issues 를 참조`() {
        assertThat(foreignKeyExists("issue_watchers", "issue_id", "issues")).isTrue()
    }

    @Test
    fun `V024 issue_watchers issue_id FK 는 ON DELETE CASCADE`() {
        assertThat(foreignKeyDeleteRule("issue_watchers", "issue_id")).isEqualTo("CASCADE")
    }

    @Test
    fun `V024 issue_watchers user_id FK 없음 (BC 격리)`() {
        assertThat(foreignKeyExists("issue_watchers", "user_id", "users")).isFalse()
    }
}

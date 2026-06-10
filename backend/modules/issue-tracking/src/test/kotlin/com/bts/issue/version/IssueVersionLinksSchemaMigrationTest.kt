// issue-tracking V017 마이그레이션 검증 — issue_affects_versions / issue_fix_versions 조인 테이블(복합 PK + FK 2개 + FK 인덱스) 존재 확인

package com.bts.issue.version

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
 * Flyway V001~V017 마이그레이션 적용 후 이슈↔버전 연결 테이블 두 개를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위 (FR-VR-03 Task 1).
 * - issue_affects_versions / issue_fix_versions 테이블 존재
 * - 복합 PK (issue_id, version_id)
 * - FK 2개 — issue_id → issues(id), version_id → versions(id)
 * - FK 인덱스 idx_issue_affects_versions_version_id / idx_issue_fix_versions_version_id 존재 (DATA.md §7)
 *   (복합 PK 선두 컬럼 issue_id 는 PK 인덱스가 커버하므로 별도 인덱스 불요)
 * - FK ON DELETE CASCADE — 양쪽 엔티티 하드 삭제 시 고아 연결 행 자동 정리 (순수 관계 테이블)
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * IssueComponentsSchemaMigrationTest 와 동일 결정.
 *
 * 참조. FR-VR-03 plan Task 1 / DATA.md §7 FK 인덱스 / V012 issue_components 동형 선례.
 */
@Testcontainers
class IssueVersionLinksSchemaMigrationTest {
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

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
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

    // 주어진 테이블의 PRIMARY KEY 구성 컬럼을 ordinal 순서대로 반환.
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

    // 주어진 테이블의 FK 가 (로컬 컬럼 → 참조 테이블 + 삭제 규칙) 형태로 존재하는지 확인.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyDeleteRule(
        tableName: String,
        column: String,
        referencedTable: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                 AND tc.table_schema = ccu.table_schema
                JOIN information_schema.referential_constraints rc
                  ON tc.constraint_name = rc.constraint_name
                 AND tc.table_schema = rc.constraint_schema
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
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    // ── issue_affects_versions ────────────────────────────────────────────────

    @Test
    fun `V017 issue_affects_versions 테이블 존재`() {
        assertThat(tableExists("issue_affects_versions")).isTrue()
    }

    @Test
    fun `V017 issue_affects_versions 컬럼 issue_id_version_id_created_at 존재`() {
        assertThat(columnExists("issue_affects_versions", "issue_id")).isTrue()
        assertThat(columnExists("issue_affects_versions", "version_id")).isTrue()
        assertThat(columnExists("issue_affects_versions", "created_at")).isTrue()
    }

    @Test
    fun `V017 issue_affects_versions 복합 PK 는 (issue_id, version_id)`() {
        assertThat(primaryKeyColumns("issue_affects_versions"))
            .containsExactly("issue_id", "version_id")
    }

    @Test
    fun `V017 issue_affects_versions issue_id FK 는 issues 를 CASCADE 참조`() {
        assertThat(foreignKeyDeleteRule("issue_affects_versions", "issue_id", "issues"))
            .isEqualTo("CASCADE")
    }

    @Test
    fun `V017 issue_affects_versions version_id FK 는 versions 를 CASCADE 참조`() {
        assertThat(foreignKeyDeleteRule("issue_affects_versions", "version_id", "versions"))
            .isEqualTo("CASCADE")
    }

    @Test
    fun `V017 FK 인덱스 idx_issue_affects_versions_version_id 존재`() {
        assertThat(indexExists("idx_issue_affects_versions_version_id")).isTrue()
    }

    // ── issue_fix_versions ────────────────────────────────────────────────────

    @Test
    fun `V017 issue_fix_versions 테이블 존재`() {
        assertThat(tableExists("issue_fix_versions")).isTrue()
    }

    @Test
    fun `V017 issue_fix_versions 컬럼 issue_id_version_id_created_at 존재`() {
        assertThat(columnExists("issue_fix_versions", "issue_id")).isTrue()
        assertThat(columnExists("issue_fix_versions", "version_id")).isTrue()
        assertThat(columnExists("issue_fix_versions", "created_at")).isTrue()
    }

    @Test
    fun `V017 issue_fix_versions 복합 PK 는 (issue_id, version_id)`() {
        assertThat(primaryKeyColumns("issue_fix_versions"))
            .containsExactly("issue_id", "version_id")
    }

    @Test
    fun `V017 issue_fix_versions issue_id FK 는 issues 를 CASCADE 참조`() {
        assertThat(foreignKeyDeleteRule("issue_fix_versions", "issue_id", "issues"))
            .isEqualTo("CASCADE")
    }

    @Test
    fun `V017 issue_fix_versions version_id FK 는 versions 를 CASCADE 참조`() {
        assertThat(foreignKeyDeleteRule("issue_fix_versions", "version_id", "versions"))
            .isEqualTo("CASCADE")
    }

    @Test
    fun `V017 FK 인덱스 idx_issue_fix_versions_version_id 존재`() {
        assertThat(indexExists("idx_issue_fix_versions_version_id")).isTrue()
    }
}

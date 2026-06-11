// issue-tracking V018 마이그레이션 검증 — issue_change_group / issue_change_item 이력 테이블(append-only + FK 1개 + FK 인덱스) 존재 확인

package com.bts.issue.history

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
 * Flyway V001~V018 마이그레이션 적용 후 이슈 변경 이력 테이블 두 개를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위 (FR-HS-01 Task 1).
 * - issue_change_group / issue_change_item 테이블 존재
 * - issue_change_group 핵심 컬럼 (issue_id, issue_key, actor_id, created_at) 존재
 * - issue_change_item 핵심 컬럼 (group_id, field, from_value, to_value, from_label, to_label) 존재
 * - FK 1개 — issue_change_item.group_id → issue_change_group(id)
 *   (이력 보존 우선 — issues/users 로의 FK 는 두지 않는다. FR-AU-10 auth_audit_logs 패턴)
 * - FK 인덱스 idx_issue_change_item_group 존재 (DATA.md §7 — PostgreSQL 은 FK 인덱스 자동 생성 안 함)
 * - 조회 인덱스 idx_issue_change_group_issue / idx_issue_change_item_field 존재
 * - append-only — soft-delete(deleted_at) / updated_at 컬럼 없음 (DATA.md §3 감사 로그 절대 삭제 금지 정합)
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * IssueVersionLinksSchemaMigrationTest 와 동일 결정.
 *
 * 참조. FR-HS-01 plan Task 1 / DATA.md §7 FK 인덱스 / FR-AU-10 auth_audit_logs append-only 선례.
 */
@Testcontainers
class IssueChangeHistorySchemaTest {
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

    // 주어진 테이블에 FOREIGN KEY 제약이 존재하는지 (참조 테이블 무관) 카운트.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyCount(tableName: String): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND constraint_type = 'FOREIGN KEY'
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    // ── issue_change_group ─────────────────────────────────────────────────────

    @Test
    fun `V018 issue_change_group 테이블 존재`() {
        assertThat(tableExists("issue_change_group")).isTrue()
    }

    @Test
    fun `V018 issue_change_group 핵심 컬럼 존재`() {
        assertThat(columnExists("issue_change_group", "id")).isTrue()
        assertThat(columnExists("issue_change_group", "issue_id")).isTrue()
        assertThat(columnExists("issue_change_group", "issue_key")).isTrue()
        assertThat(columnExists("issue_change_group", "actor_id")).isTrue()
        assertThat(columnExists("issue_change_group", "created_at")).isTrue()
    }

    @Test
    fun `V018 issue_change_group append-only — deleted_at_updated_at 컬럼 없음`() {
        assertThat(columnExists("issue_change_group", "deleted_at")).isFalse()
        assertThat(columnExists("issue_change_group", "updated_at")).isFalse()
    }

    @Test
    fun `V018 issue_change_group 은 issues_users 로의 FK 를 두지 않는다`() {
        // 이력 보존 우선 — 부모 삭제와 무관하게 이력은 남아야 한다 (FR-AU-10 auth_audit_logs 패턴).
        assertThat(foreignKeyCount("issue_change_group")).isEqualTo(0)
    }

    @Test
    fun `V018 조회 인덱스 idx_issue_change_group_issue 존재`() {
        assertThat(indexExists("idx_issue_change_group_issue")).isTrue()
    }

    // ── issue_change_item ──────────────────────────────────────────────────────

    @Test
    fun `V018 issue_change_item 테이블 존재`() {
        assertThat(tableExists("issue_change_item")).isTrue()
    }

    @Test
    fun `V018 issue_change_item 핵심 컬럼 존재`() {
        assertThat(columnExists("issue_change_item", "id")).isTrue()
        assertThat(columnExists("issue_change_item", "group_id")).isTrue()
        assertThat(columnExists("issue_change_item", "field")).isTrue()
        assertThat(columnExists("issue_change_item", "from_value")).isTrue()
        assertThat(columnExists("issue_change_item", "to_value")).isTrue()
    }

    @Test
    fun `V018 issue_change_item from_label_to_label 컬럼 존재`() {
        assertThat(columnExists("issue_change_item", "from_label")).isTrue()
        assertThat(columnExists("issue_change_item", "to_label")).isTrue()
    }

    @Test
    fun `V018 issue_change_item group_id FK 는 issue_change_group 을 참조`() {
        assertThat(foreignKeyExists("issue_change_item", "group_id", "issue_change_group")).isTrue()
    }

    @Test
    fun `V018 issue_change_item FK 는 group_id 하나뿐 (issues_users FK 없음)`() {
        assertThat(foreignKeyCount("issue_change_item")).isEqualTo(1)
    }

    @Test
    fun `V018 FK 인덱스 idx_issue_change_item_group 존재`() {
        assertThat(indexExists("idx_issue_change_item_group")).isTrue()
    }

    @Test
    fun `V018 조회 인덱스 idx_issue_change_item_field 존재`() {
        assertThat(indexExists("idx_issue_change_item_field")).isTrue()
    }
}

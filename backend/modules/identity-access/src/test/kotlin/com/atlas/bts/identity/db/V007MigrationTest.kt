// V007 Flyway 마이그레이션 검증 — project_memberships 테이블 + UNIQUE(project_id,user_id) + role CHECK + user_id FK + 인덱스 3개 확인

package com.atlas.bts.identity.db

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * Flyway V001~V007 마이그레이션 자동 적용 후 project_memberships 테이블/인덱스/제약을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * project_id 는 cross-BC(issue-tracking) 참조이므로 FK 없음 (ADR D2).
 * user_id 는 users(id) ON DELETE CASCADE FK.
 */
@Testcontainers
class V007MigrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
        }
    }

    private fun tableExists(tableName: String): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
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
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun uniqueConstraintOnColumns(
        tableName: String,
        col1: String,
        col2: String,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM (
                    SELECT tc.constraint_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.constraint_column_usage ccu
                        ON tc.constraint_name = ccu.constraint_name
                    WHERE tc.table_schema = 'public'
                      AND tc.table_name = ?
                      AND tc.constraint_type = 'UNIQUE'
                      AND ccu.column_name IN (?, ?)
                    GROUP BY tc.constraint_name
                    HAVING COUNT(DISTINCT ccu.column_name) = 2
                ) sub
                """,
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, col1)
                stmt.setString(3, col2)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun checkConstraintExists(
        tableName: String,
        expectedValues: List<String>,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT cc.check_clause
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                    ON tc.constraint_name = cc.constraint_name
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'CHECK'
                """,
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val clause = rs.getString(1)
                        if (expectedValues.all { clause.contains(it) }) return true
                    }
                    return false
                }
            }
        }
    }

    private fun fkExistsToTable(
        fromTable: String,
        toTable: String,
        fromColumn: String,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM information_schema.referential_constraints rc
                JOIN information_schema.table_constraints tc
                    ON rc.constraint_name = tc.constraint_name
                JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = ?
                  AND kcu.column_name = ?
                  AND rc.unique_constraint_name IN (
                      SELECT constraint_name FROM information_schema.table_constraints
                      WHERE table_name = ? AND constraint_type = 'PRIMARY KEY'
                  )
                """,
            ).use { stmt ->
                stmt.setString(1, fromTable)
                stmt.setString(2, fromColumn)
                stmt.setString(3, toTable)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    @Test
    fun `V007 creates project_memberships table`() {
        assertThat(tableExists("project_memberships")).isTrue()
    }

    @Test
    fun `V007 project_memberships has all expected columns`() {
        assertThat(columnExists("project_memberships", "id")).isTrue()
        assertThat(columnExists("project_memberships", "project_id")).isTrue()
        assertThat(columnExists("project_memberships", "user_id")).isTrue()
        assertThat(columnExists("project_memberships", "role")).isTrue()
        assertThat(columnExists("project_memberships", "created_at")).isTrue()
        assertThat(columnExists("project_memberships", "updated_at")).isTrue()
    }

    @Test
    fun `V007 UNIQUE constraint on project_id and user_id`() {
        assertThat(uniqueConstraintOnColumns("project_memberships", "project_id", "user_id")).isTrue()
    }

    @Test
    fun `V007 role CHECK constraint enforces PROJECT_ADMIN or MEMBER`() {
        assertThat(
            checkConstraintExists("project_memberships", listOf("PROJECT_ADMIN", "MEMBER")),
        ).isTrue()
    }

    @Test
    fun `V007 user_id FK references users ON DELETE CASCADE`() {
        assertThat(fkExistsToTable("project_memberships", "users", "user_id")).isTrue()
    }

    @Test
    fun `V007 idx_project_memberships_project index exists`() {
        assertThat(indexExists("idx_project_memberships_project")).isTrue()
    }

    @Test
    fun `V007 idx_project_memberships_user index exists`() {
        assertThat(indexExists("idx_project_memberships_user")).isTrue()
    }

    @Test
    fun `V007 idx_project_memberships_admins partial index exists`() {
        assertThat(indexExists("idx_project_memberships_admins")).isTrue()
    }
}

// V001 + V002 Flyway 마이그레이션 검증 — Testcontainers PostgreSQL + 테이블/인덱스/제약 존재 확인

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
 * Flyway V001 + V002 마이그레이션 자동 적용 후 테이블/인덱스/제약 존재를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 */
@Testcontainers
class V002MigrationTest {
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

    @Test
    fun `V001 users 테이블 존재`() {
        assertThat(tableExists("users")).isTrue()
    }

    @Test
    fun `V001 users 테이블 필수 컬럼 존재`() {
        assertThat(columnExists("users", "id")).isTrue()
        assertThat(columnExists("users", "username")).isTrue()
        assertThat(columnExists("users", "email")).isTrue()
        assertThat(columnExists("users", "display_name")).isTrue()
        assertThat(columnExists("users", "created_at")).isTrue()
        assertThat(columnExists("users", "updated_at")).isTrue()
    }

    @Test
    fun `V002 authn_providers 테이블 존재`() {
        assertThat(tableExists("authn_providers")).isTrue()
    }

    @Test
    fun `V002 authn_providers 필수 컬럼 존재`() {
        assertThat(columnExists("authn_providers", "id")).isTrue()
        assertThat(columnExists("authn_providers", "type")).isTrue()
        assertThat(columnExists("authn_providers", "name")).isTrue()
        assertThat(columnExists("authn_providers", "config")).isTrue()
        assertThat(columnExists("authn_providers", "enabled")).isTrue()
        assertThat(columnExists("authn_providers", "sort_order")).isTrue()
    }

    @Test
    fun `V002 authn_providers 부분 인덱스 존재`() {
        assertThat(indexExists("idx_authn_providers_type_enabled")).isTrue()
    }

    @Test
    fun `V002 user_external_accounts 테이블 존재`() {
        assertThat(tableExists("user_external_accounts")).isTrue()
    }

    @Test
    fun `V002 user_external_accounts 필수 컬럼 존재`() {
        assertThat(columnExists("user_external_accounts", "id")).isTrue()
        assertThat(columnExists("user_external_accounts", "provider_id")).isTrue()
        assertThat(columnExists("user_external_accounts", "external_subject")).isTrue()
        assertThat(columnExists("user_external_accounts", "user_id")).isTrue()
        assertThat(columnExists("user_external_accounts", "groups")).isTrue()
        assertThat(columnExists("user_external_accounts", "failed_attempts")).isTrue()
        assertThat(columnExists("user_external_accounts", "locked_until")).isTrue()
        assertThat(columnExists("user_external_accounts", "last_login_at")).isTrue()
    }

    @Test
    fun `V002 user_external_accounts 인덱스 존재`() {
        assertThat(indexExists("idx_uea_user_id")).isTrue()
        assertThat(indexExists("idx_uea_provider_subject")).isTrue()
    }

    @Test
    fun `V002 user_external_accounts unique 제약 provider_id external_subject`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                WHERE tc.table_name = 'user_external_accounts'
                  AND tc.constraint_type = 'UNIQUE'
                  AND kcu.column_name IN ('provider_id', 'external_subject')
                """,
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isGreaterThan(0)
                }
            }
        }
    }
}

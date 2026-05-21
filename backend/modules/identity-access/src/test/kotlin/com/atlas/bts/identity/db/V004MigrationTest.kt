// V004 Flyway 마이그레이션 검증 — sessions 테이블 11 컬럼 + partial index 2개 존재 확인

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
 * Flyway V001~V004 마이그레이션 자동 적용 후 sessions 테이블/인덱스/컬럼 존재를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 */
@Testcontainers
class V004MigrationTest {

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

    @Test
    fun `V004 creates sessions table with expected columns and indexes`() {
        // 테이블 존재
        assertThat(tableExists("sessions")).isTrue()

        // 컬럼 11개 (spec §5 V004)
        assertThat(columnExists("sessions", "id")).isTrue()
        assertThat(columnExists("sessions", "user_id")).isTrue()
        assertThat(columnExists("sessions", "provider_id")).isTrue()
        assertThat(columnExists("sessions", "device_fingerprint")).isTrue()
        assertThat(columnExists("sessions", "ip_address")).isTrue()
        assertThat(columnExists("sessions", "user_agent")).isTrue()
        assertThat(columnExists("sessions", "created_at")).isTrue()
        assertThat(columnExists("sessions", "expires_at")).isTrue()
        assertThat(columnExists("sessions", "last_seen_at")).isTrue()
        assertThat(columnExists("sessions", "revoked_at")).isTrue()
        assertThat(columnExists("sessions", "revoke_reason")).isTrue()

        // partial index 2개
        assertThat(indexExists("idx_sessions_user_active")).isTrue()
        assertThat(indexExists("idx_sessions_expires_active")).isTrue()
    }
}

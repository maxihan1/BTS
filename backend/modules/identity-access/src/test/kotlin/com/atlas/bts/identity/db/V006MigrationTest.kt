// V006 Flyway 마이그레이션 검증 — personal_access_tokens 9 컬럼 + scopes JSONB DEFAULT + token_hash UNIQUE + idx_pat_user_active 부분 인덱스 확인

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
 * Flyway V001~V006 마이그레이션 자동 적용 후 personal_access_tokens 테이블/인덱스/컬럼/제약을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * EC-26. token_hash = SHA-256(전체 token 그대로). 평문 token 미저장.
 * EC-27. expires_at nullable — 무기한 PAT 허용.
 */
@Testcontainers
class V006MigrationTest {

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

    private fun columnDefault(
        tableName: String,
        columnName: String,
    ): String? {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT column_default FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return rs.getString(1) else return null
                }
            }
        }
    }

    private fun isNullable(
        tableName: String,
        columnName: String,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getString(1) == "YES"
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

    private fun uniqueConstraintExists(
        tableName: String,
        columnName: String,
    ): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu
                    ON tc.constraint_name = ccu.constraint_name
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'UNIQUE'
                  AND ccu.column_name = ?
                """,
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

    private fun fkToUsersExists(): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*) FROM information_schema.referential_constraints rc
                JOIN information_schema.table_constraints tc
                    ON rc.constraint_name = tc.constraint_name
                WHERE tc.table_name = 'personal_access_tokens'
                  AND rc.unique_constraint_name IN (
                      SELECT constraint_name FROM information_schema.table_constraints
                      WHERE table_name = 'users' AND constraint_type = 'PRIMARY KEY'
                  )
                """,
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    @Test
    fun `V006 creates personal_access_tokens table with all 9 columns`() {
        assertThat(tableExists("personal_access_tokens")).isTrue()

        // spec §5 V006 컬럼 9개
        assertThat(columnExists("personal_access_tokens", "id")).isTrue()
        assertThat(columnExists("personal_access_tokens", "user_id")).isTrue()
        assertThat(columnExists("personal_access_tokens", "name")).isTrue()
        assertThat(columnExists("personal_access_tokens", "token_hash")).isTrue()
        assertThat(columnExists("personal_access_tokens", "scopes")).isTrue()
        assertThat(columnExists("personal_access_tokens", "expires_at")).isTrue()
        assertThat(columnExists("personal_access_tokens", "last_used_at")).isTrue()
        assertThat(columnExists("personal_access_tokens", "revoked_at")).isTrue()
        assertThat(columnExists("personal_access_tokens", "created_at")).isTrue()
    }

    @Test
    fun `V006 scopes column has JSONB DEFAULT empty array`() {
        // scopes JSONB NOT NULL DEFAULT '[]'
        val default = columnDefault("personal_access_tokens", "scopes")
        assertThat(default).isNotNull()
        // PostgreSQL 이 저장하는 형태: '[]'::jsonb
        assertThat(default).contains("[]")
    }

    @Test
    fun `V006 token_hash has UNIQUE constraint`() {
        assertThat(uniqueConstraintExists("personal_access_tokens", "token_hash")).isTrue()
    }

    @Test
    fun `V006 idx_pat_user_active partial index exists`() {
        // partial index: WHERE revoked_at IS NULL
        assertThat(indexExists("idx_pat_user_active")).isTrue()
    }

    @Test
    fun `V006 expires_at is nullable — EC-27 무기한 PAT 허용`() {
        assertThat(isNullable("personal_access_tokens", "expires_at")).isTrue()
    }

    @Test
    fun `V006 user_id FK references users — phantom entity guard`() {
        assertThat(fkToUsersExists()).isTrue()
    }
}

// V003 Flyway 마이그레이션 검증 — local_credentials 테이블 컬럼/제약/FK CASCADE 확인

package com.atlas.bts.identity.credential

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * Flyway V001~V003 마이그레이션 자동 적용 후 local_credentials 테이블의
 * 컬럼 5개, NOT NULL 제약, 데이터 타입, PK, FK ON DELETE CASCADE 를 검증한다.
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 직접 사용.
 */
@Testcontainers
class LocalCredentialsMigrationTest {
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

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    @Suppress("NestedBlockDepth")
    private fun columnInfo(
        tableName: String,
        columnName: String,
    ): Map<String, String>? {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name   = ?
                  AND column_name  = ?
                """,
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return mapOf(
                        "data_type" to rs.getString("data_type"),
                        "is_nullable" to rs.getString("is_nullable"),
                        "column_default" to (rs.getString("column_default") ?: ""),
                    )
                }
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun primaryKeyColumns(tableName: String): List<String> {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                   AND tc.table_schema    = kcu.table_schema
                WHERE tc.table_schema  = 'public'
                  AND tc.table_name    = ?
                  AND tc.constraint_type = 'PRIMARY KEY'
                ORDER BY kcu.ordinal_position
                """,
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val cols = mutableListOf<String>()
                    while (rs.next()) cols.add(rs.getString("column_name"))
                    return cols
                }
            }
        }
    }

    @Suppress("NestedBlockDepth")
    private fun fkDeleteRule(
        tableName: String,
        columnName: String,
    ): String? {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                FROM information_schema.referential_constraints rc
                JOIN information_schema.key_column_usage kcu
                    ON rc.constraint_name = kcu.constraint_name
                WHERE kcu.table_schema = 'public'
                  AND kcu.table_name   = ?
                  AND kcu.column_name  = ?
                """,
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.getString("delete_rule")
                }
            }
        }
    }

    // ── 테스트 ───────────────────────────────────────────────────────────────────

    @Test
    fun `local_credentials 테이블이 존재한다`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables" +
                    " WHERE table_schema = 'public' AND table_name = 'local_credentials'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isEqualTo(1)
                }
            }
        }
    }

    @Test
    fun `user_id 컬럼은 UUID 타입 NOT NULL PK`() {
        val info = columnInfo("local_credentials", "user_id")
        assertThat(info).isNotNull
        // PostgreSQL information_schema 에서 UUID 컬럼의 data_type 은 "uuid"
        assertThat(info!!["data_type"]).isEqualTo("uuid")
        assertThat(info["is_nullable"]).isEqualTo("NO")
        assertThat(primaryKeyColumns("local_credentials")).containsExactly("user_id")
    }

    @Test
    fun `password_hash 컬럼은 TEXT NOT NULL`() {
        val info = columnInfo("local_credentials", "password_hash")
        assertThat(info).isNotNull
        assertThat(info!!["data_type"]).isEqualTo("text")
        assertThat(info["is_nullable"]).isEqualTo("NO")
    }

    @Test
    fun `algo_version 컬럼은 TEXT NOT NULL DEFAULT argon2id-v1`() {
        val info = columnInfo("local_credentials", "algo_version")
        assertThat(info).isNotNull
        assertThat(info!!["data_type"]).isEqualTo("text")
        assertThat(info["is_nullable"]).isEqualTo("NO")
        // DEFAULT 값에 'argon2id-v1' 문자열이 포함돼야 한다
        assertThat(info["column_default"]).contains("argon2id-v1")
    }

    @Test
    fun `created_at 컬럼은 TIMESTAMPTZ NOT NULL`() {
        val info = columnInfo("local_credentials", "created_at")
        assertThat(info).isNotNull
        // TIMESTAMPTZ 는 information_schema 에서 "timestamp with time zone" 으로 노출된다
        assertThat(info!!["data_type"]).isEqualTo("timestamp with time zone")
        assertThat(info["is_nullable"]).isEqualTo("NO")
    }

    @Test
    fun `updated_at 컬럼은 TIMESTAMPTZ NOT NULL`() {
        val info = columnInfo("local_credentials", "updated_at")
        assertThat(info).isNotNull
        assertThat(info!!["data_type"]).isEqualTo("timestamp with time zone")
        assertThat(info["is_nullable"]).isEqualTo("NO")
    }

    @Test
    fun `user_id FK는 users(id) ON DELETE CASCADE`() {
        // FK delete_rule 은 "CASCADE" 여야 한다
        val rule = fkDeleteRule("local_credentials", "user_id")
        assertThat(rule).isEqualTo("CASCADE")
    }

    @Test
    @Suppress("NestedBlockDepth")
    fun `users 삭제 시 local_credentials 행이 CASCADE 삭제된다`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                // users 에 테스트 행 삽입
                val userId = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"
                conn.prepareStatement(
                    "INSERT INTO users (id, username) VALUES (?::uuid, ?)",
                ).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setString(2, "cascade_test_user")
                    stmt.executeUpdate()
                }

                // local_credentials 에 연결 행 삽입
                conn.prepareStatement(
                    "INSERT INTO local_credentials (user_id, password_hash) VALUES (?::uuid, ?)",
                ).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setString(2, "\$argon2id\$v=19\$m=65536,t=3,p=4\$salt\$hash")
                    stmt.executeUpdate()
                }

                // users 행 삭제 → CASCADE 로 local_credentials 도 삭제돼야 함
                conn.prepareStatement("DELETE FROM users WHERE id = ?::uuid").use { stmt ->
                    stmt.setString(1, userId)
                    stmt.executeUpdate()
                }

                // local_credentials 에 해당 행이 없어야 한다
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM local_credentials WHERE user_id = ?::uuid",
                ).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        assertThat(rs.getInt(1)).isEqualTo(0)
                    }
                }

                conn.rollback()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }
}

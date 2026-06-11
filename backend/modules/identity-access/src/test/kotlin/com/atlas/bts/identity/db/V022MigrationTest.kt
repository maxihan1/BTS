// V022 마이그레이션 검증 — totp_secrets 테이블 + sessions.mfa_verified 컬럼 (FR-MF-01)

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
 * Flyway V001~V022 마이그레이션 자동 적용 후 totp_secrets 테이블/컬럼/제약 + sessions.mfa_verified 컬럼을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 회귀 가드.
 *  - totp_secrets.user_id UUID PK + users(id) ON DELETE CASCADE — 사용자 삭제 시 TOTP secret 자동 삭제
 *  - status CHECK IN ('PENDING','ACTIVE') — 잘못된 상태값 거부
 *  - secret_cipher NOT NULL — 평문 secret 금지(암호문만 저장, DEVELOPMENT.md §1.1.1)
 *  - sessions.mfa_verified BOOLEAN NOT NULL DEFAULT false — GAP-1 refresh 회전 시 mfa_verified 전파(기존 row 무회귀)
 */
@Testcontainers
class V022MigrationTest {
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

    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getString(1) ?: ""
                }
            }
        }
    }

    private fun columnDefault(
        tableName: String,
        columnName: String,
    ): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT column_default FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getString(1) ?: ""
                }
            }
        }
    }

    private fun primaryKeyColumn(tableName: String): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT a.attname
                FROM pg_constraint c
                JOIN pg_class t       ON t.oid = c.conrelid
                JOIN pg_attribute a   ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                WHERE c.contype = 'p'
                  AND t.relname = ?
                """,
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getString(1) ?: ""
                }
            }
        }
    }

    private fun fkToUsersWithCascadeExists(childTable: String): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_class child  ON child.oid  = c.conrelid
                JOIN pg_class parent ON parent.oid = c.confrelid
                WHERE c.contype = 'f'
                  AND child.relname  = ?
                  AND parent.relname = 'users'
                  AND c.confdeltype  = 'c'
                """,
            ).use { stmt ->
                stmt.setString(1, childTable)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun statusCheckRejectsInvalidValue(): Boolean {
        // status = 'BOGUS' INSERT 가 CHECK 제약으로 거부되는지 확인 (CheckViolation → false 반환)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO users(id, username, display_name, email, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-0000000000a1', 'totp_check_user', 'Totp User', 'totp@test.com', NOW(), NOW())
                    """,
                ).execute()

                return try {
                    conn.prepareStatement(
                        """
                        INSERT INTO totp_secrets(user_id, secret_cipher, status, created_at, updated_at)
                        VALUES ('00000000-0000-0000-0000-0000000000a1', 'cipher', 'BOGUS', NOW(), NOW())
                        """,
                    ).execute()
                    false // CHECK 제약 미존재 — INSERT 가 통과해버림
                } catch (e: java.sql.SQLException) {
                    // 23514 = check_violation (postgresql JDBC 는 runtimeOnly 이므로 표준 SQLState 로 확인)
                    e.sqlState == "23514"
                }
            } finally {
                conn.rollback()
            }
        }
    }

    private fun cascadeDeleteFromUsersWorks(): Boolean {
        // users row 삭제 시 연결 totp_secrets row 자동 삭제 확인 (ON DELETE CASCADE 동작 검증)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO users(id, username, display_name, email, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-0000000000a2', 'totp_cascade_user', 'Cascade User', 'tc@test.com', NOW(), NOW())
                    """,
                ).execute()

                conn.prepareStatement(
                    """
                    INSERT INTO totp_secrets(user_id, secret_cipher, status, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-0000000000a2', 'cipher', 'PENDING', NOW(), NOW())
                    """,
                ).execute()

                conn.prepareStatement(
                    "DELETE FROM users WHERE id = '00000000-0000-0000-0000-0000000000a2'",
                ).execute()

                val count =
                    conn.prepareStatement(
                        "SELECT COUNT(*) FROM totp_secrets WHERE user_id = '00000000-0000-0000-0000-0000000000a2'",
                    ).executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }

                conn.rollback()
                return count == 0
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    @Test
    fun `V022 creates totp_secrets with users FK CASCADE + status CHECK and adds sessions mfa_verified`() {
        // 테이블 존재 확인 — V022 없으면 "table does not exist" 로 실패
        assertThat(tableExists("totp_secrets")).isTrue()

        // totp_secrets 컬럼 7개 (plan §데이터 모델)
        assertThat(columnExists("totp_secrets", "user_id")).isTrue()
        assertThat(columnExists("totp_secrets", "secret_cipher")).isTrue()
        assertThat(columnExists("totp_secrets", "status")).isTrue()
        assertThat(columnExists("totp_secrets", "last_verified_step")).isTrue()
        assertThat(columnExists("totp_secrets", "confirmed_at")).isTrue()
        assertThat(columnExists("totp_secrets", "created_at")).isTrue()
        assertThat(columnExists("totp_secrets", "updated_at")).isTrue()

        // user_id 가 PK
        assertThat(primaryKeyColumn("totp_secrets")).isEqualTo("user_id")

        // secret_cipher NOT NULL (평문 secret 금지 — 암호문만)
        assertThat(isNullable("totp_secrets", "secret_cipher")).isFalse()

        // status NOT NULL
        assertThat(isNullable("totp_secrets", "status")).isFalse()

        // last_verified_step / confirmed_at NULL 허용 (PENDING 초기 상태)
        assertThat(isNullable("totp_secrets", "last_verified_step")).isTrue()
        assertThat(isNullable("totp_secrets", "confirmed_at")).isTrue()

        // 시각 컬럼은 TIMESTAMPTZ (DATA.md — TIMESTAMP without tz 금지)
        assertThat(columnDataType("totp_secrets", "confirmed_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("totp_secrets", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("totp_secrets", "updated_at")).isEqualTo("timestamp with time zone")

        // users FK ON DELETE CASCADE
        assertThat(fkToUsersWithCascadeExists("totp_secrets")).isTrue()
        assertThat(cascadeDeleteFromUsersWorks()).isTrue()

        // status CHECK IN ('PENDING','ACTIVE') — 잘못된 값 거부
        assertThat(statusCheckRejectsInvalidValue()).isTrue()

        // sessions.mfa_verified BOOLEAN NOT NULL DEFAULT false
        assertThat(columnExists("sessions", "mfa_verified")).isTrue()
        assertThat(columnDataType("sessions", "mfa_verified")).isEqualTo("boolean")
        assertThat(isNullable("sessions", "mfa_verified")).isFalse()
        assertThat(columnDefault("sessions", "mfa_verified")).isEqualTo("false")
    }
}

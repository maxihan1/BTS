// V026 마이그레이션 검증 — trusted_devices 테이블(30일 MFA 면제) + 컬럼/제약/인덱스 (FR-MF-05)

package com.atlas.bts.identity.mfa

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * Flyway V001~V026 마이그레이션 자동 적용 후 trusted_devices 테이블/컬럼/제약/인덱스를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다(V022MigrationTest 선례).
 *
 * 회귀 가드.
 *  - trusted_devices.id UUID PK + user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE — 사용자 삭제 시 연쇄 삭제
 *  - token_hash TEXT NOT NULL UNIQUE — SHA-256 해시만 저장(평문 비영속, DEVELOPMENT.md §1.1.1), 재신뢰 충돌 차단
 *  - expires_at TIMESTAMPTZ NOT NULL — 30일 TTL(고정), label/last_used_at nullable
 *  - 시각 컬럼은 모두 TIMESTAMPTZ(DATA.md — TIMESTAMP without tz 금지)
 *  - idx_trusted_devices_user(user_id) — FK 인덱스(PostgreSQL FK 인덱스 자동 생성 안 함)
 */
@Testcontainers
class TrustedDeviceMigrationTest {
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
                    return rs.getString(1).orEmpty()
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
                    return rs.getString(1).orEmpty()
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

    private fun tokenHashUniqueRejectsDuplicate(): Boolean {
        // 같은 token_hash 두 번 INSERT 시 UNIQUE 제약으로 거부되는지 확인 (UniqueViolation → true)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO users(id, username, display_name, email, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-0000000000b1', 'td_uq_user', 'TD User', 'td@test.com', NOW(), NOW())
                    """,
                ).execute()

                conn.prepareStatement(
                    """
                    INSERT INTO trusted_devices(id, user_id, token_hash, expires_at)
                    VALUES ('00000000-0000-0000-0000-0000000000c1', '00000000-0000-0000-0000-0000000000b1', 'dup_hash', NOW() + INTERVAL '30 days')
                    """,
                ).execute()

                return try {
                    conn.prepareStatement(
                        """
                        INSERT INTO trusted_devices(id, user_id, token_hash, expires_at)
                        VALUES ('00000000-0000-0000-0000-0000000000c2', '00000000-0000-0000-0000-0000000000b1', 'dup_hash', NOW() + INTERVAL '30 days')
                        """,
                    ).execute()
                    false // UNIQUE 제약 미존재 — 중복 INSERT 가 통과해버림
                } catch (e: java.sql.SQLException) {
                    // 23505 = unique_violation (표준 SQLState 로 확인)
                    e.sqlState == "23505"
                }
            } finally {
                conn.rollback()
            }
        }
    }

    private fun cascadeDeleteFromUsersWorks(): Boolean {
        // users row 삭제 시 연결 trusted_devices row 자동 삭제 확인 (ON DELETE CASCADE 동작 검증)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO users(id, username, display_name, email, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-0000000000b2', 'td_cascade_user', 'TD Cascade', 'tdc@test.com', NOW(), NOW())
                    """,
                ).execute()

                conn.prepareStatement(
                    """
                    INSERT INTO trusted_devices(id, user_id, token_hash, expires_at)
                    VALUES ('00000000-0000-0000-0000-0000000000c3', '00000000-0000-0000-0000-0000000000b2', 'cascade_hash', NOW() + INTERVAL '30 days')
                    """,
                ).execute()

                conn.prepareStatement(
                    "DELETE FROM users WHERE id = '00000000-0000-0000-0000-0000000000b2'",
                ).execute()

                val count =
                    conn.prepareStatement(
                        "SELECT COUNT(*) FROM trusted_devices WHERE user_id = '00000000-0000-0000-0000-0000000000b2'",
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
    fun `V026 creates trusted_devices with users FK CASCADE + token_hash UNIQUE + user index`() {
        // 테이블 존재 확인 — V026 없으면 "table does not exist" 로 실패
        assertThat(tableExists("trusted_devices")).isTrue()

        // 컬럼 7개 (spec §데이터 모델)
        assertThat(columnExists("trusted_devices", "id")).isTrue()
        assertThat(columnExists("trusted_devices", "user_id")).isTrue()
        assertThat(columnExists("trusted_devices", "token_hash")).isTrue()
        assertThat(columnExists("trusted_devices", "label")).isTrue()
        assertThat(columnExists("trusted_devices", "created_at")).isTrue()
        assertThat(columnExists("trusted_devices", "expires_at")).isTrue()
        assertThat(columnExists("trusted_devices", "last_used_at")).isTrue()

        // id 가 PK
        assertThat(primaryKeyColumn("trusted_devices")).isEqualTo("id")

        // user_id / token_hash / expires_at NOT NULL
        assertThat(isNullable("trusted_devices", "user_id")).isFalse()
        assertThat(isNullable("trusted_devices", "token_hash")).isFalse()
        assertThat(isNullable("trusted_devices", "expires_at")).isFalse()

        // label / last_used_at NULL 허용
        assertThat(isNullable("trusted_devices", "label")).isTrue()
        assertThat(isNullable("trusted_devices", "last_used_at")).isTrue()

        // id / user_id 는 uuid 타입
        assertThat(columnDataType("trusted_devices", "id")).isEqualTo("uuid")
        assertThat(columnDataType("trusted_devices", "user_id")).isEqualTo("uuid")

        // 시각 컬럼은 TIMESTAMPTZ (DATA.md — TIMESTAMP without tz 금지)
        assertThat(columnDataType("trusted_devices", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("trusted_devices", "expires_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("trusted_devices", "last_used_at")).isEqualTo("timestamp with time zone")

        // users FK ON DELETE CASCADE
        assertThat(fkToUsersWithCascadeExists("trusted_devices")).isTrue()
        assertThat(cascadeDeleteFromUsersWorks()).isTrue()

        // token_hash UNIQUE — 중복 INSERT 거부
        assertThat(tokenHashUniqueRejectsDuplicate()).isTrue()

        // idx_trusted_devices_user(user_id) FK 인덱스 존재
        assertThat(indexExists("idx_trusted_devices_user")).isTrue()
    }
}

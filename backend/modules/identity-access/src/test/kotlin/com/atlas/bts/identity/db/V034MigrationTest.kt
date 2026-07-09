// V034 마이그레이션 검증 — user_calendar_tokens 테이블 스키마(PK/FK CASCADE·token_hash UNIQUE·created_at) FR-CA-02

package com.atlas.bts.identity.db

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Flyway V001~V034 마이그레이션 자동 적용 후 user_calendar_tokens 테이블/컬럼/제약을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다(V033MigrationTest 선례).
 *
 * 회귀 가드.
 *  - user_calendar_tokens.user_id PRIMARY KEY — 사용자당 활성 토큰 1개(재발급=UPSERT rotate, ADR D4)
 *  - user_id REFERENCES users(id) ON DELETE CASCADE — 사용자 삭제 시 토큰 연쇄 삭제
 *    → 그 구독 URL 즉시 404. 오프보딩 유출 벡터 없음(ADR D5, UserLookupAdapter "행 존재=실재")
 *  - token_hash VARCHAR(64) NOT NULL UNIQUE — SHA-256 해시만 저장(원문 미저장, ADR D4).
 *    UNIQUE 는 익명 피드 해시 조회 인덱스 겸용
 *  - created_at TIMESTAMPTZ NOT NULL DEFAULT NOW() (DATA.md — TIMESTAMP without tz 금지)
 *
 * NestedBlockDepth 억제 사유 — 마이그레이션 검증 헬퍼의 raw JDBC 3중 use
 * (connection→statement→resultSet) + 결과 수집 루프는 구조상 불가피(V033MigrationTest 선례).
 */
@Testcontainers
@Suppress("NestedBlockDepth")
class V034MigrationTest {
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

    private fun columnCharMaxLength(
        tableName: String,
        columnName: String,
    ): Int {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT character_maximum_length FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1)
                }
            }
        }
    }

    private fun primaryKeyColumns(tableName: String): List<String> {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT a.attname
                FROM pg_constraint c
                JOIN pg_class t       ON t.oid = c.conrelid
                JOIN pg_attribute a   ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                WHERE c.contype = 'p'
                  AND t.relname = ?
                ORDER BY array_position(c.conkey, a.attnum)
                """,
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val cols = mutableListOf<String>()
                    while (rs.next()) {
                        cols.add(rs.getString(1))
                    }
                    return cols
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

    private fun insertUser(
        conn: java.sql.Connection,
        userId: String,
        username: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO users(id, username, display_name, email, created_at, updated_at) " +
                "VALUES (?::uuid, ?, 'Calendar Feed User', ?, NOW(), NOW())",
        ).use { stmt ->
            stmt.setString(1, userId)
            stmt.setString(2, username)
            stmt.setString(3, "$username@test.com")
            stmt.execute()
        }
    }

    private fun insertToken(
        conn: java.sql.Connection,
        userId: String,
        tokenHash: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO user_calendar_tokens(user_id, token_hash) VALUES (?::uuid, ?)",
        ).use { stmt ->
            stmt.setString(1, userId)
            stmt.setString(2, tokenHash)
            stmt.execute()
        }
    }

    private fun timestampDefaultAppliesOnInsert(): Boolean {
        // created_at 을 생략한 INSERT 가 DB 기본값(NOW())으로 채워지는지 확인 (DEFAULT 실동작)
        val userId = "00000000-0000-0000-0000-0000000000d0"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                insertUser(conn, userId, "cal_ts_user")
                insertToken(conn, userId, "a".repeat(64))
                return conn.prepareStatement(
                    "SELECT created_at IS NOT NULL AS ok FROM user_calendar_tokens WHERE user_id = ?::uuid",
                ).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getBoolean("ok")
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    private fun userIdPkRejectsSecondToken(): Boolean {
        // 같은 user_id 로 토큰 두 개 INSERT 는 user_id PRIMARY KEY 위반(SQLException)이어야 한다 (사용자당 1개)
        val userId = "00000000-0000-0000-0000-0000000000d1"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                insertUser(conn, userId, "cal_pk_user")
                insertToken(conn, userId, "b".repeat(64))
                return try {
                    insertToken(conn, userId, "c".repeat(64))
                    false // 두 번째 INSERT 성공 = user_id PK 없음 — 실패
                } catch (_: SQLException) {
                    true // PK 위반으로 차단됨 — 성공
                }
            } finally {
                conn.rollback()
            }
        }
    }

    private fun tokenHashUniqueRejectsDuplicate(): Boolean {
        // 서로 다른 사용자가 같은 token_hash 를 가지면 UNIQUE 위반(SQLException)이어야 한다
        val userA = "00000000-0000-0000-0000-0000000000d2"
        val userB = "00000000-0000-0000-0000-0000000000d3"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                insertUser(conn, userA, "cal_uniq_a")
                insertUser(conn, userB, "cal_uniq_b")
                insertToken(conn, userA, "d".repeat(64))
                return try {
                    insertToken(conn, userB, "d".repeat(64))
                    false // 중복 hash INSERT 성공 = UNIQUE 없음 — 실패
                } catch (_: SQLException) {
                    true // UNIQUE 위반으로 차단됨 — 성공
                }
            } finally {
                conn.rollback()
            }
        }
    }

    private fun cascadeDeleteFromUsersWorks(): Boolean {
        // users row 삭제 시 연결 user_calendar_tokens row 자동 삭제 확인 (ON DELETE CASCADE 동작 검증)
        val userId = "00000000-0000-0000-0000-0000000000d4"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                insertUser(conn, userId, "cal_cascade_user")
                insertToken(conn, userId, "e".repeat(64))
                conn.prepareStatement("DELETE FROM users WHERE id = ?::uuid").use { stmt ->
                    stmt.setString(1, userId)
                    stmt.execute()
                }
                val count =
                    conn.prepareStatement(
                        "SELECT COUNT(*) FROM user_calendar_tokens WHERE user_id = ?::uuid",
                    ).use { stmt ->
                        stmt.setString(1, userId)
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                return count == 0
            } finally {
                conn.rollback()
            }
        }
    }

    @Test
    fun `V034 creates user_calendar_tokens with user_id PK users FK CASCADE token_hash UNIQUE and created_at`() {
        // 테이블 존재 확인 — V034 없으면 "table does not exist" 로 실패
        assertThat(tableExists("user_calendar_tokens")).isTrue()

        // 컬럼 3개 (ADR D4/D7)
        assertThat(columnExists("user_calendar_tokens", "user_id")).isTrue()
        assertThat(columnExists("user_calendar_tokens", "token_hash")).isTrue()
        assertThat(columnExists("user_calendar_tokens", "created_at")).isTrue()

        // user_id 단일 PRIMARY KEY (사용자당 활성 토큰 1개)
        assertThat(primaryKeyColumns("user_calendar_tokens")).containsExactly("user_id")

        // 타입/길이
        assertThat(columnDataType("user_calendar_tokens", "user_id")).isEqualTo("uuid")
        assertThat(columnDataType("user_calendar_tokens", "token_hash")).isEqualTo("character varying")
        assertThat(columnCharMaxLength("user_calendar_tokens", "token_hash")).isEqualTo(64)

        // token_hash NOT NULL (SHA-256 해시 필수, 원문 미저장)
        assertThat(isNullable("user_calendar_tokens", "token_hash")).isFalse()

        // created_at TIMESTAMPTZ (DATA.md — TIMESTAMP without tz 금지) + DEFAULT NOW()
        assertThat(columnDataType("user_calendar_tokens", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(timestampDefaultAppliesOnInsert()).isTrue()

        // user_id PK 가 사용자당 두 번째 토큰을 차단
        assertThat(userIdPkRejectsSecondToken()).isTrue()

        // token_hash UNIQUE (익명 피드 해시 조회 인덱스 겸용) 가 중복 해시를 차단
        assertThat(tokenHashUniqueRejectsDuplicate()).isTrue()

        // users FK ON DELETE CASCADE — 사용자 삭제 시 토큰 연쇄 삭제 → 구독 URL 404
        assertThat(fkToUsersWithCascadeExists("user_calendar_tokens")).isTrue()
        assertThat(cascadeDeleteFromUsersWorks()).isTrue()
    }
}

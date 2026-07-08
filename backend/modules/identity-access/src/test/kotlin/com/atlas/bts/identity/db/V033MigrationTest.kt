// V033 마이그레이션 검증 — user_keymap 테이블(사용자별 단축키 override) + 복합PK(user_id,action) + action CHECK + FK CASCADE (FR-PF-03)

package com.atlas.bts.identity.db

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Flyway V001~V033 마이그레이션 자동 적용 후 user_keymap 테이블/컬럼/제약을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다(V031MigrationTest 선례).
 *
 * 회귀 가드.
 *  - user_keymap 복합 PRIMARY KEY (user_id, action) — 사용자당 action 하나만 override
 *  - user_id REFERENCES users(id) ON DELETE CASCADE — 사용자 삭제 시 단축키 override 연쇄 삭제
 *  - action VARCHAR(32) CHECK — override 대상 action 화이트리스트를 DB 최후 방어선으로 강제.
 *    앱 우회 raw SQL 쓰기도 무효 action 이면 제약 위반으로 차단
 *  - key_combo VARCHAR(16) NOT NULL — 신규 테이블이라 backfill 불요
 *  - 시각 컬럼은 모두 TIMESTAMPTZ(DATA.md — TIMESTAMP without tz 금지)
 *
 * NestedBlockDepth 억제 사유 — 마이그레이션 검증 헬퍼의 raw JDBC 3중 use
 * (connection→statement→resultSet) + 결과 수집 루프는 구조상 불가피
 * (V006/V007 detekt-baseline 선례와 동일 성격, 신규 코드라 baseline 대신 @Suppress 명시).
 */
@Testcontainers
@Suppress("NestedBlockDepth")
class V033MigrationTest {
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
                "VALUES (?::uuid, ?, 'Keymap User', ?, NOW(), NOW())",
        ).use { stmt ->
            stmt.setString(1, userId)
            stmt.setString(2, username)
            stmt.setString(3, "$username@test.com")
            stmt.execute()
        }
    }

    private fun insertKeymap(
        userIdSuffix: String,
        username: String,
        action: String,
        keyCombo: String,
    ): String {
        // 명시적 action/key_combo 로 user + user_keymap 을 INSERT 후 롤백. CHECK 위반 시 SQLException 을 던진다.
        val userId = "00000000-0000-0000-0000-0000000000$userIdSuffix"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                insertUser(conn, userId, username)

                conn.prepareStatement(
                    "INSERT INTO user_keymap(user_id, action, key_combo) VALUES (?::uuid, ?, ?)",
                ).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setString(2, action)
                    stmt.setString(3, keyCombo)
                    stmt.execute()
                }

                conn.prepareStatement(
                    "SELECT key_combo FROM user_keymap WHERE user_id = ?::uuid AND action = ?",
                ).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.setString(2, action)
                    return stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getString("key_combo")
                    }
                }
            } finally {
                conn.rollback()
            }
        }
    }

    private fun timestampDefaultsApplyOnInsert(): Boolean {
        // created_at/updated_at 를 생략한 INSERT 가 DB 기본값(now())으로 채워지는지 확인 (DEFAULT 실동작)
        val userId = "00000000-0000-0000-0000-0000000000c0"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                insertUser(conn, userId, "keymap_ts_user")
                conn.prepareStatement(
                    "INSERT INTO user_keymap(user_id, action, key_combo) VALUES (?::uuid, 'help', '?')",
                ).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.execute()
                }
                return conn.prepareStatement(
                    "SELECT created_at IS NOT NULL AND updated_at IS NOT NULL AS ok " +
                        "FROM user_keymap WHERE user_id = ?::uuid AND action = 'help'",
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

    private fun compositePkRejectsDuplicate(): Boolean {
        // 같은 (user_id, action) 두 번 INSERT 는 복합 PK 위반(SQLException)이어야 한다
        val userId = "00000000-0000-0000-0000-0000000000c1"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                insertUser(conn, userId, "keymap_pk_user")
                conn.prepareStatement(
                    "INSERT INTO user_keymap(user_id, action, key_combo) VALUES (?::uuid, 'help', 'h')",
                ).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.execute()
                }
                return try {
                    conn.prepareStatement(
                        "INSERT INTO user_keymap(user_id, action, key_combo) VALUES (?::uuid, 'help', 'g')",
                    ).use { stmt ->
                        stmt.setString(1, userId)
                        stmt.execute()
                    }
                    false // 중복 삽입이 성공하면 PK 가 없다는 뜻 — 실패
                } catch (_: SQLException) {
                    true // PK 위반으로 차단됨 — 성공
                }
            } finally {
                conn.rollback()
            }
        }
    }

    private fun cascadeDeleteFromUsersWorks(): Boolean {
        // users row 삭제 시 연결 user_keymap row 자동 삭제 확인 (ON DELETE CASCADE 동작 검증)
        val userId = "00000000-0000-0000-0000-0000000000c2"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                insertUser(conn, userId, "keymap_cascade_user")
                conn.prepareStatement(
                    "INSERT INTO user_keymap(user_id, action, key_combo) VALUES (?::uuid, 'search', '/')",
                ).use { stmt ->
                    stmt.setString(1, userId)
                    stmt.execute()
                }
                conn.prepareStatement("DELETE FROM users WHERE id = ?::uuid").use { stmt ->
                    stmt.setString(1, userId)
                    stmt.execute()
                }
                val count =
                    conn.prepareStatement(
                        "SELECT COUNT(*) FROM user_keymap WHERE user_id = ?::uuid",
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
    fun `V033 creates user_keymap with composite PK users FK CASCADE and timestamps`() {
        // 테이블 존재 확인 — V033 없으면 "table does not exist" 로 실패
        assertThat(tableExists("user_keymap")).isTrue()

        // 컬럼 5개 (spec §데이터 모델)
        assertThat(columnExists("user_keymap", "user_id")).isTrue()
        assertThat(columnExists("user_keymap", "action")).isTrue()
        assertThat(columnExists("user_keymap", "key_combo")).isTrue()
        assertThat(columnExists("user_keymap", "created_at")).isTrue()
        assertThat(columnExists("user_keymap", "updated_at")).isTrue()

        // 복합 PK (user_id, action)
        assertThat(primaryKeyColumns("user_keymap")).containsExactly("user_id", "action")

        // 타입/길이
        assertThat(columnDataType("user_keymap", "user_id")).isEqualTo("uuid")
        assertThat(columnDataType("user_keymap", "action")).isEqualTo("character varying")
        assertThat(columnCharMaxLength("user_keymap", "action")).isEqualTo(32)
        assertThat(columnDataType("user_keymap", "key_combo")).isEqualTo("character varying")
        assertThat(columnCharMaxLength("user_keymap", "key_combo")).isEqualTo(16)

        // key_combo NOT NULL
        assertThat(isNullable("user_keymap", "key_combo")).isFalse()

        // 시각 컬럼은 TIMESTAMPTZ (DATA.md — TIMESTAMP without tz 금지)
        assertThat(columnDataType("user_keymap", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("user_keymap", "updated_at")).isEqualTo("timestamp with time zone")
        assertThat(timestampDefaultsApplyOnInsert()).isTrue()

        // 복합 PK 가 (user_id, action) 중복을 차단
        assertThat(compositePkRejectsDuplicate()).isTrue()

        // users FK ON DELETE CASCADE
        assertThat(fkToUsersWithCascadeExists("user_keymap")).isTrue()
        assertThat(cascadeDeleteFromUsersWorks()).isTrue()
    }

    @Test
    fun `V033 CHECK accepts whitelisted action values`() {
        // 화이트리스트 5종 모두 INSERT 성공해야 한다
        assertThat(insertKeymap("a0", "km_help", "help", "?")).isEqualTo("?")
        assertThat(insertKeymap("a1", "km_create", "create-issue", "c")).isEqualTo("c")
        assertThat(insertKeymap("a2", "km_search", "search", "/")).isEqualTo("/")
        assertThat(insertKeymap("a3", "km_myissues", "goto-my-issues", "g i")).isEqualTo("g i")
        assertThat(insertKeymap("a4", "km_dashboard", "goto-dashboard", "g d")).isEqualTo("g d")
    }

    @Test
    fun `V033 CHECK rejects non-whitelisted action values`() {
        // 화이트리스트 밖 action 은 앱 우회 raw SQL 이라도 CHECK 제약 위반(SQLException)으로 차단돼야 한다
        assertThatThrownBy { insertKeymap("b0", "km_evil", "delete-everything", "x") }
            .isInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertKeymap("b1", "km_empty", "", "x") }
            .isInstanceOf(SQLException::class.java)
        assertThatThrownBy { insertKeymap("b2", "km_typo", "gotodashboard", "x") }
            .isInstanceOf(SQLException::class.java)
    }
}

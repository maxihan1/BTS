// V031 마이그레이션 검증 — user_preferences 테이블(테마/로케일/날짜형식) + 컬럼/기본값/PK/FK CASCADE (FR-PF-01)

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
 * Flyway V001~V031 마이그레이션 자동 적용 후 user_preferences 테이블/컬럼/기본값/제약을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다(V022MigrationTest 선례).
 *
 * 회귀 가드.
 *  - user_preferences.user_id UUID PK + REFERENCES users(id) ON DELETE CASCADE — 사용자 삭제 시 환경설정 연쇄 삭제
 *  - theme/locale/date_format VARCHAR(16) NOT NULL + 기본값('system'/'ko'/'iso') — 미설정 사용자도 기본 프로필 보장
 *  - 시각 컬럼은 모두 TIMESTAMPTZ(DATA.md — TIMESTAMP without tz 금지)
 */
@Testcontainers
class V031MigrationTest {
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

    private fun defaultsApplyOnInsert(): Triple<String, String, String> {
        // theme/locale/date_format 를 생략한 INSERT 가 DB 기본값으로 채워지는지 확인 (DEFAULT 실동작 검증)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO users(id, username, display_name, email, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-0000000000d1', 'pref_default_user', 'Pref User', 'pref@test.com', NOW(), NOW())
                    """,
                ).execute()

                conn.prepareStatement(
                    "INSERT INTO user_preferences(user_id) VALUES ('00000000-0000-0000-0000-0000000000d1')",
                ).execute()

                return conn.prepareStatement(
                    "SELECT theme, locale, date_format FROM user_preferences " +
                        "WHERE user_id = '00000000-0000-0000-0000-0000000000d1'",
                ).executeQuery().use { rs ->
                    rs.next()
                    Triple(rs.getString("theme"), rs.getString("locale"), rs.getString("date_format"))
                }
            } finally {
                conn.rollback()
            }
        }
    }

    private fun cascadeDeleteFromUsersWorks(): Boolean {
        // users row 삭제 시 연결 user_preferences row 자동 삭제 확인 (ON DELETE CASCADE 동작 검증)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO users(id, username, display_name, email, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-0000000000d2', 'pref_cascade_user', 'Pref Cascade', 'prefc@test.com', NOW(), NOW())
                    """,
                ).execute()

                conn.prepareStatement(
                    "INSERT INTO user_preferences(user_id) VALUES ('00000000-0000-0000-0000-0000000000d2')",
                ).execute()

                conn.prepareStatement(
                    "DELETE FROM users WHERE id = '00000000-0000-0000-0000-0000000000d2'",
                ).execute()

                val count =
                    conn.prepareStatement(
                        "SELECT COUNT(*) FROM user_preferences WHERE user_id = '00000000-0000-0000-0000-0000000000d2'",
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
    fun `V031 creates user_preferences with users FK CASCADE + theme locale date_format defaults`() {
        // 테이블 존재 확인 — V031 없으면 "table does not exist" 로 실패
        assertThat(tableExists("user_preferences")).isTrue()

        // 컬럼 6개 (spec §데이터 모델)
        assertThat(columnExists("user_preferences", "user_id")).isTrue()
        assertThat(columnExists("user_preferences", "theme")).isTrue()
        assertThat(columnExists("user_preferences", "locale")).isTrue()
        assertThat(columnExists("user_preferences", "date_format")).isTrue()
        assertThat(columnExists("user_preferences", "created_at")).isTrue()
        assertThat(columnExists("user_preferences", "updated_at")).isTrue()

        // user_id 가 PK
        assertThat(primaryKeyColumn("user_preferences")).isEqualTo("user_id")

        // user_id uuid 타입
        assertThat(columnDataType("user_preferences", "user_id")).isEqualTo("uuid")

        // theme/locale/date_format NOT NULL
        assertThat(isNullable("user_preferences", "theme")).isFalse()
        assertThat(isNullable("user_preferences", "locale")).isFalse()
        assertThat(isNullable("user_preferences", "date_format")).isFalse()

        // 기본값 — theme='system', locale='ko', date_format='iso'
        assertThat(columnDefault("user_preferences", "theme")).startsWith("'system'")
        assertThat(columnDefault("user_preferences", "locale")).startsWith("'ko'")
        assertThat(columnDefault("user_preferences", "date_format")).startsWith("'iso'")

        // 시각 컬럼은 TIMESTAMPTZ (DATA.md — TIMESTAMP without tz 금지)
        assertThat(columnDataType("user_preferences", "created_at")).isEqualTo("timestamp with time zone")
        assertThat(columnDataType("user_preferences", "updated_at")).isEqualTo("timestamp with time zone")

        // 기본값이 INSERT 시 실제로 채워지는지 (DEFAULT 실동작)
        val (theme, locale, dateFormat) = defaultsApplyOnInsert()
        assertThat(theme).isEqualTo("system")
        assertThat(locale).isEqualTo("ko")
        assertThat(dateFormat).isEqualTo("iso")

        // users FK ON DELETE CASCADE
        assertThat(fkToUsersWithCascadeExists("user_preferences")).isTrue()
        assertThat(cascadeDeleteFromUsersWorks()).isTrue()
    }
}

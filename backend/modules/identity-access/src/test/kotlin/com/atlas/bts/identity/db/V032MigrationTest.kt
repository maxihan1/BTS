// V032 마이그레이션 검증 — user_preferences.start_page 컬럼(시작 페이지) 존재/타입/NOT NULL/기본값/백필 (FR-PF-02)

package com.atlas.bts.identity.db

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager

/**
 * Flyway V001~V032 마이그레이션 자동 적용 후 user_preferences.start_page 컬럼/타입/기본값/백필을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다(V031MigrationTest 선례).
 *
 * 회귀 가드.
 *  - start_page VARCHAR(32) NOT NULL DEFAULT 'dashboards' — 미설정 사용자도 기본 시작 페이지 보장
 *  - NOT NULL + DEFAULT 이므로 V032 이전에 삽입된 user_preferences 행도 'dashboards' 로 백필됨
 *  - 값 화이트리스트(dashboards/my_issues/issues/inbox) 검증은 후속 백엔드 task 담당 — 이 마이그레이션은 스키마만
 */
@Testcontainers
class V032MigrationTest {
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

    private fun startPageDefaultOnInsert(): String {
        // start_page 를 생략한 INSERT 가 DB 기본값('dashboards')으로 채워지는지 확인 (DEFAULT 실동작 검증)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    INSERT INTO users(id, username, display_name, email, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-0000000000e0', 'sp_default_user', 'SP User', 'sp@test.com', NOW(), NOW())
                    """,
                ).execute()

                conn.prepareStatement(
                    "INSERT INTO user_preferences(user_id) VALUES ('00000000-0000-0000-0000-0000000000e0')",
                ).execute()

                return conn.prepareStatement(
                    "SELECT start_page FROM user_preferences WHERE user_id = '00000000-0000-0000-0000-0000000000e0'",
                ).executeQuery().use { rs ->
                    rs.next()
                    rs.getString("start_page")
                }
            } finally {
                conn.rollback()
            }
        }
    }

    @Test
    fun `V032 adds start_page to user_preferences NOT NULL default dashboards`() {
        // 컬럼 존재 확인 — V032 없으면 실패
        assertThat(columnExists("user_preferences", "start_page")).isTrue()

        // VARCHAR(32)
        assertThat(columnDataType("user_preferences", "start_page")).isEqualTo("character varying")
        assertThat(columnCharMaxLength("user_preferences", "start_page")).isEqualTo(32)

        // NOT NULL
        assertThat(isNullable("user_preferences", "start_page")).isFalse()

        // 기본값 'dashboards'
        assertThat(columnDefault("user_preferences", "start_page")).startsWith("'dashboards'")

        // 기본값이 INSERT 시 실제로 채워지는지 (DEFAULT 실동작)
        assertThat(startPageDefaultOnInsert()).isEqualTo("dashboards")
    }

    @Test
    fun `V032 backfills pre-existing user_preferences rows with dashboards`() {
        // V032 이전(V031 까지)에 삽입된 행이 NOT NULL + DEFAULT 로 'dashboards' 백필되는지 별도 컨테이너로 검증
        PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("bts_backfill")
            .withUsername("bts")
            .withPassword("bts_test")
            .use { container ->
                container.start()

                // 1) V001~V031 까지만 적용 (start_page 컬럼 아직 없음)
                Flyway.configure()
                    .dataSource(container.jdbcUrl, container.username, container.password)
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("031"))
                    .load()
                    .migrate()

                // 2) user + user_preferences 행 삽입 (start_page 컬럼 없이)
                DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                    conn.prepareStatement(
                        "INSERT INTO users(id, username, display_name, email, created_at, updated_at) " +
                            "VALUES ('00000000-0000-0000-0000-0000000000e1', 'sp_backfill_user', " +
                            "'SP Backfill', 'spb@test.com', NOW(), NOW())",
                    ).execute()
                    conn.prepareStatement(
                        "INSERT INTO user_preferences(user_id) VALUES ('00000000-0000-0000-0000-0000000000e1')",
                    ).execute()
                }

                // 3) V032 적용 (start_page 컬럼 추가 + 기존 행 백필)
                Flyway.configure()
                    .dataSource(container.jdbcUrl, container.username, container.password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()

                // 4) 기존 행이 'dashboards' 로 백필됐는지 확인
                DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                    val startPage =
                        conn.prepareStatement(
                            "SELECT start_page FROM user_preferences" +
                                " WHERE user_id = '00000000-0000-0000-0000-0000000000e1'",
                        ).executeQuery().use { rs ->
                            rs.next()
                            rs.getString("start_page")
                        }
                    assertThat(startPage).isEqualTo("dashboards")
                }
            }
    }
}

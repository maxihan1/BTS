// V005 Flyway 마이그레이션 검증 — refresh_tokens 7 컬럼 + sessions FK ON DELETE CASCADE + self FK replaced_by + token_hash UNIQUE + idx_refresh_session 인덱스 확인

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
 * Flyway V001~V005 마이그레이션 자동 적용 후 refresh_tokens 테이블/인덱스/컬럼/제약을 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 회귀 가드.
 *  - sessions FK ON DELETE CASCADE — sessions row 삭제 시 연결 refresh_tokens 자동 삭제
 *  - replaced_by self FK — rotation chain 추적 (US-05)
 *  - token_hash UNIQUE — 동일 해시 중복 저장 불가 (SHA-256 hex 64자)
 */
@Testcontainers
class V005MigrationTest {

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

    private fun fkToSessionsWithCascadeExists(): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            // pg_constraint 로 FK + ON DELETE CASCADE 동시 확인
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_class child  ON child.oid  = c.conrelid
                JOIN pg_class parent ON parent.oid = c.confrelid
                WHERE c.contype = 'f'
                  AND child.relname  = 'refresh_tokens'
                  AND parent.relname = 'sessions'
                  AND c.confdeltype  = 'c'
                """,
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun selfFkReplacedByExists(): Boolean {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            // replaced_by -> refresh_tokens(id) self FK 확인
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM pg_constraint c
                JOIN pg_class child  ON child.oid  = c.conrelid
                JOIN pg_class parent ON parent.oid = c.confrelid
                JOIN pg_attribute a  ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                WHERE c.contype = 'f'
                  AND child.relname  = 'refresh_tokens'
                  AND parent.relname = 'refresh_tokens'
                  AND a.attname      = 'replaced_by'
                """,
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    return rs.getInt(1) > 0
                }
            }
        }
    }

    private fun cascadeDeleteWorksCorrectly(): Boolean {
        // sessions row 삭제 시 연결 refresh_tokens row 자동 삭제 확인 (ON DELETE CASCADE 동작 검증)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                // 최소 데이터 삽입: users → sessions → refresh_tokens
                conn.prepareStatement(
                    """
                    INSERT INTO users(id, username, display_name, email, created_at, updated_at)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'cascade_test_user', 'Cascade User', 'cascade@test.com', NOW(), NOW())
                    """,
                ).execute()

                conn.prepareStatement(
                    """
                    INSERT INTO sessions(id, user_id, provider_id, created_at, expires_at, last_seen_at)
                    VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', 'local', NOW(), NOW() + INTERVAL '14 days', NOW())
                    """,
                ).execute()

                conn.prepareStatement(
                    """
                    INSERT INTO refresh_tokens(id, session_id, token_hash, issued_at, expires_at)
                    VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000002',
                            'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', NOW(), NOW() + INTERVAL '14 days')
                    """,
                ).execute()

                // sessions row 삭제
                conn.prepareStatement(
                    "DELETE FROM sessions WHERE id = '00000000-0000-0000-0000-000000000002'",
                ).execute()

                // refresh_tokens row 가 CASCADE 로 삭제됐는지 확인
                val count = conn.prepareStatement(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE id = '00000000-0000-0000-0000-000000000003'",
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
    fun `V005 creates refresh_tokens with sessions FK + self FK replaced_by + token_hash UNIQUE`() {
        // 테이블 존재 확인 — V005 없으면 "table does not exist" 로 실패
        assertThat(tableExists("refresh_tokens")).isTrue()

        // spec §5 V005 컬럼 7개
        assertThat(columnExists("refresh_tokens", "id")).isTrue()
        assertThat(columnExists("refresh_tokens", "session_id")).isTrue()
        assertThat(columnExists("refresh_tokens", "token_hash")).isTrue()
        assertThat(columnExists("refresh_tokens", "issued_at")).isTrue()
        assertThat(columnExists("refresh_tokens", "expires_at")).isTrue()
        assertThat(columnExists("refresh_tokens", "used_at")).isTrue()
        assertThat(columnExists("refresh_tokens", "replaced_by")).isTrue()

        // token_hash UNIQUE constraint
        assertThat(uniqueConstraintExists("refresh_tokens", "token_hash")).isTrue()

        // idx_refresh_session 인덱스 존재
        assertThat(indexExists("idx_refresh_session")).isTrue()

        // sessions FK ON DELETE CASCADE
        assertThat(fkToSessionsWithCascadeExists()).isTrue()

        // replaced_by self FK (rotation chain)
        assertThat(selfFkReplacedByExists()).isTrue()

        // nullable 컬럼 확인 — used_at / replaced_by 는 NULL 허용 (미사용 초기 상태)
        assertThat(isNullable("refresh_tokens", "used_at")).isTrue()
        assertThat(isNullable("refresh_tokens", "replaced_by")).isTrue()

        // ON DELETE CASCADE 실제 동작 검증
        assertThat(cascadeDeleteWorksCorrectly()).isTrue()
    }
}

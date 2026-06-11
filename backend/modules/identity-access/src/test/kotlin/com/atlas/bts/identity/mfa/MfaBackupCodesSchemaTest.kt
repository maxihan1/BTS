// V023 마이그레이션 검증 — MFA 백업 코드 테이블/부분 인덱스/UNIQUE/FK CASCADE 확인 (FR-MF-02 task-1)

package com.atlas.bts.identity.mfa

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Flyway V001~V023 마이그레이션 자동 적용 후 MFA 백업 코드 스키마를 검증한다.
 *
 * ## 검증 항목
 * - user_mfa_backup_codes 테이블 존재
 * - 부분 인덱스 idx_mfa_backup_codes_user_active (WHERE used_at IS NULL) 존재
 * - 유니크 인덱스 uq_mfa_backup_codes_user_hash (user_id, code_hash) 존재
 * - (user_id, code_hash) 중복 INSERT 차단 (UNIQUE 동작)
 * - users 삭제 시 백업 코드 연쇄 삭제 (FK ON DELETE CASCADE)
 *
 * IssueSecuritySchemaMigrationTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class MfaBackupCodesSchemaTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired
    @Suppress("VarCouldBeVal") // @Autowired lateinit var 는 val 불가(주입). detekt false-positive 억제
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private fun tableExists(name: String): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name = :name
            """,
            mapOf("name" to name),
            Int::class.java,
        )!!

    private fun indexExists(name: String): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM pg_indexes
            WHERE schemaname = 'public'
              AND tablename = 'user_mfa_backup_codes'
              AND indexname = :name
            """,
            mapOf("name" to name),
            Int::class.java,
        )!!

    private fun insertUser(username: String): String =
        jdbc.queryForObject(
            "INSERT INTO users (username) VALUES (:username) RETURNING id::text",
            mapOf("username" to username),
            String::class.java,
        )!!

    private fun insertBackupCode(
        userId: String,
        codeHash: String,
    ) = jdbc.update(
        """
        INSERT INTO user_mfa_backup_codes (user_id, code_hash)
        VALUES (CAST(:userId AS UUID), :codeHash)
        """,
        mapOf("userId" to userId, "codeHash" to codeHash),
    )

    @Test
    fun `user_mfa_backup_codes 테이블이 존재한다`() {
        assertThat(tableExists("user_mfa_backup_codes")).isEqualTo(1)
    }

    @Test
    fun `부분 인덱스 idx_mfa_backup_codes_user_active 가 존재한다`() {
        assertThat(indexExists("idx_mfa_backup_codes_user_active")).isEqualTo(1)
    }

    @Test
    fun `부분 인덱스는 used_at IS NULL 조건을 가진다`() {
        val indexDef =
            jdbc.queryForObject(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename = 'user_mfa_backup_codes'
                  AND indexname = 'idx_mfa_backup_codes_user_active'
                """,
                mapOf<String, Any>(),
                String::class.java,
            )!!
        assertThat(indexDef).contains("used_at IS NULL")
    }

    @Test
    fun `유니크 인덱스 uq_mfa_backup_codes_user_hash 가 존재한다`() {
        assertThat(indexExists("uq_mfa_backup_codes_user_hash")).isEqualTo(1)
    }

    @Test
    fun `같은 user_id code_hash 조합은 UNIQUE다`() {
        val userId = insertUser("mfa-unique-user")
        insertBackupCode(userId, "sha256-hash-aaa")
        // 같은 (user_id, code_hash) → uq_mfa_backup_codes_user_hash 위반.
        // (@JdbcTest 단일 트랜잭션이라 위반 후 추가 INSERT 불가 → 위반 단언만.)
        assertThatThrownBy { insertBackupCode(userId, "sha256-hash-aaa") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 code_hash라도 user_id가 다르면 허용한다`() {
        val userA = insertUser("mfa-userA")
        val userB = insertUser("mfa-userB")
        // (user_id, code_hash) 복합 UNIQUE라 사용자가 다르면 같은 해시 허용.
        insertBackupCode(userA, "shared-hash")
        insertBackupCode(userB, "shared-hash")
    }

    @Test
    fun `users 삭제 시 백업 코드가 연쇄 삭제된다 (FK CASCADE)`() {
        val userId = insertUser("mfa-cascade-user")
        insertBackupCode(userId, "cascade-hash-1")
        insertBackupCode(userId, "cascade-hash-2")

        jdbc.update(
            "DELETE FROM users WHERE id = CAST(:id AS UUID)",
            mapOf("id" to userId),
        )

        val remaining =
            jdbc.queryForObject(
                "SELECT count(*) FROM user_mfa_backup_codes WHERE user_id = CAST(:id AS UUID)",
                mapOf("id" to userId),
                Int::class.java,
            )
        assertThat(remaining).isEqualTo(0)
    }
}

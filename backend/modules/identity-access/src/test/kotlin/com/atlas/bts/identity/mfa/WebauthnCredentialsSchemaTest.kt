// V025 마이그레이션 검증 — WebAuthn 자격증명 테이블/컬럼/전역 UNIQUE/FK 인덱스/CASCADE 확인 (FR-MF-03 task-2)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.support.SharedPostgres
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

/**
 * Flyway V001~V025 마이그레이션 자동 적용 후 WebAuthn 자격증명 스키마를 검증한다.
 *
 * ## 검증 항목
 * - webauthn_credentials 테이블 존재
 * - 필수 컬럼 존재 (credential_id, attested_credential_data, sign_count, name, aaguid, last_used_at 등)
 * - 유니크 인덱스 uq_webauthn_credential_id (credential_id 전역 UNIQUE — 복합 아님) 존재
 * - FK 인덱스 idx_webauthn_credentials_user (user_id) 존재
 * - 같은 credential_id 는 사용자가 달라도 전역 UNIQUE 위반
 * - sign_count 기본값 0
 * - users 삭제 시 자격증명 연쇄 삭제 (FK ON DELETE CASCADE)
 *
 * MfaBackupCodesSchemaTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WebauthnCredentialsSchemaTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            r.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            r.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
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

    private fun columnExists(column: String): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'webauthn_credentials'
              AND column_name = :column
            """,
            mapOf("column" to column),
            Int::class.java,
        )!!

    private fun indexExists(name: String): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM pg_indexes
            WHERE schemaname = 'public'
              AND tablename = 'webauthn_credentials'
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

    private fun insertCredential(
        userId: String,
        credentialId: String,
    ) = jdbc.update(
        """
        INSERT INTO webauthn_credentials (user_id, credential_id, attested_credential_data)
        VALUES (CAST(:userId AS UUID), :credentialId, 'attested-data')
        """,
        mapOf("userId" to userId, "credentialId" to credentialId),
    )

    @Test
    fun `webauthn_credentials 테이블이 존재한다`() {
        assertThat(tableExists("webauthn_credentials")).isEqualTo(1)
    }

    @Test
    fun `필수 컬럼이 모두 존재한다`() {
        assertThat(columnExists("id")).isEqualTo(1)
        assertThat(columnExists("user_id")).isEqualTo(1)
        assertThat(columnExists("credential_id")).isEqualTo(1)
        assertThat(columnExists("attested_credential_data")).isEqualTo(1)
        assertThat(columnExists("sign_count")).isEqualTo(1)
        assertThat(columnExists("name")).isEqualTo(1)
        assertThat(columnExists("aaguid")).isEqualTo(1)
        assertThat(columnExists("last_used_at")).isEqualTo(1)
        assertThat(columnExists("created_at")).isEqualTo(1)
        assertThat(columnExists("updated_at")).isEqualTo(1)
    }

    @Test
    fun `유니크 인덱스 uq_webauthn_credential_id 가 존재한다`() {
        assertThat(indexExists("uq_webauthn_credential_id")).isEqualTo(1)
    }

    @Test
    fun `FK 인덱스 idx_webauthn_credentials_user 가 존재한다`() {
        assertThat(indexExists("idx_webauthn_credentials_user")).isEqualTo(1)
    }

    @Test
    fun `같은 credential_id 는 사용자가 달라도 전역 UNIQUE 위반이다`() {
        val userA = insertUser("webauthn-userA")
        val userB = insertUser("webauthn-userB")
        insertCredential(userA, "global-credential-id")
        // credential_id 는 전역 UNIQUE(복합 아님) → 다른 사용자라도 같은 credential_id 차단.
        assertThatThrownBy { insertCredential(userB, "global-credential-id") }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `sign_count 는 기본값 0 이다`() {
        val userId = insertUser("webauthn-signcount-user")
        insertCredential(userId, "signcount-credential")
        val signCount =
            jdbc.queryForObject(
                "SELECT sign_count FROM webauthn_credentials WHERE credential_id = :cid",
                mapOf("cid" to "signcount-credential"),
                Long::class.java,
            )
        assertThat(signCount).isEqualTo(0L)
    }

    @Test
    fun `users 삭제 시 자격증명이 연쇄 삭제된다 (FK CASCADE)`() {
        val userId = insertUser("webauthn-cascade-user")
        insertCredential(userId, "cascade-credential-1")
        insertCredential(userId, "cascade-credential-2")

        jdbc.update(
            "DELETE FROM users WHERE id = CAST(:id AS UUID)",
            mapOf("id" to userId),
        )

        val remaining =
            jdbc.queryForObject(
                "SELECT count(*) FROM webauthn_credentials WHERE user_id = CAST(:id AS UUID)",
                mapOf("id" to userId),
                Int::class.java,
            )
        assertThat(remaining).isEqualTo(0)
    }
}

// V021 auth_audit_logs 마이그레이션 스키마 검증 통합테스트 — FR-AU-10 Task 1

package com.atlas.bts.identity.audit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Flyway V001~V021 마이그레이션 자동 적용 후 auth_audit_logs 스키마를 검증한다 (FR-AU-10 Task 1).
 *
 * ## 검증 항목
 * - 테이블 auth_audit_logs 존재.
 * - 컬럼 9종 존재 + 타입/nullable 정합:
 *   id(bigint, IDENTITY, NOT NULL) · user_id(uuid, NULL) · event_type(varchar 40, NOT NULL) ·
 *   provider_id(varchar 50, NOT NULL) · ip_address(varchar 45, NULL) · user_agent(text, NULL) ·
 *   device_fingerprint(varchar 255, NULL) · metadata(jsonb, NOT NULL) · created_at(timestamptz, NOT NULL).
 * - 인덱스 3종 존재: idx_auth_audit_logs_user_created · idx_auth_audit_logs_event_type ·
 *   idx_auth_audit_logs_created_at.
 * - FK 없음 (append-only 포렌식 — 사용자 생명주기 비결합, spec §4.1).
 *
 * IssueSecuritySchemaMigrationTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제(경량 부팅).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AuthAuditLogsSchemaTest {
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
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Test
    fun `auth_audit_logs 테이블이 존재한다`() {
        assertThat(tableExists("auth_audit_logs")).isEqualTo(1)
    }

    @Test
    fun `id는 bigint IDENTITY NOT NULL PK다`() {
        val column = column("id")
        assertThat(column.dataType).isEqualTo("bigint")
        assertThat(column.isNullable).isFalse()
        // GENERATED ALWAYS AS IDENTITY — information_schema 의 is_identity 가 YES.
        assertThat(isIdentity("id")).isTrue()
    }

    @Test
    fun `user_id는 uuid nullable다`() {
        val column = column("user_id")
        assertThat(column.dataType).isEqualTo("uuid")
        assertThat(column.isNullable).isTrue()
    }

    @Test
    fun `event_type는 varchar 40 NOT NULL이다`() {
        val column = column("event_type")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(40)
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `provider_id는 varchar 50 NOT NULL이다`() {
        val column = column("provider_id")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(50)
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `ip_address는 varchar 45 nullable이다`() {
        val column = column("ip_address")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(45)
        assertThat(column.isNullable).isTrue()
    }

    @Test
    fun `user_agent는 text nullable이다`() {
        val column = column("user_agent")
        assertThat(column.dataType).isEqualTo("text")
        assertThat(column.isNullable).isTrue()
    }

    @Test
    fun `device_fingerprint는 varchar 255 nullable이다`() {
        val column = column("device_fingerprint")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(255)
        assertThat(column.isNullable).isTrue()
    }

    @Test
    fun `metadata는 jsonb NOT NULL이다`() {
        val column = column("metadata")
        assertThat(column.dataType).isEqualTo("jsonb")
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `created_at은 timestamptz NOT NULL이다`() {
        val column = column("created_at")
        assertThat(column.dataType).isEqualTo("timestamp with time zone")
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `인덱스 3종이 존재한다`() {
        assertThat(indexExists("idx_auth_audit_logs_user_created")).isEqualTo(1)
        assertThat(indexExists("idx_auth_audit_logs_event_type")).isEqualTo(1)
        assertThat(indexExists("idx_auth_audit_logs_created_at")).isEqualTo(1)
    }

    @Test
    fun `FK가 없다 (append-only 포렌식, 사용자 생명주기 비결합)`() {
        val fkCount =
            jdbc.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                  AND table_name = 'auth_audit_logs'
                  AND constraint_type = 'FOREIGN KEY'
                """,
                emptyMap<String, Any>(),
                Int::class.java,
            )!!
        assertThat(fkCount).isEqualTo(0)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private data class ColumnInfo(
        val dataType: String,
        val isNullable: Boolean,
        val maxLength: Int?,
    )

    private fun column(name: String): ColumnInfo =
        jdbc.queryForObject(
            """
            SELECT data_type, is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'auth_audit_logs'
              AND column_name = :name
            """,
            mapOf("name" to name),
        ) { rs, _ ->
            ColumnInfo(
                dataType = rs.getString("data_type"),
                isNullable = rs.getString("is_nullable") == "YES",
                maxLength = rs.getObject("character_maximum_length")?.let { (it as Number).toInt() },
            )
        }!!

    private fun isIdentity(name: String): Boolean =
        jdbc.queryForObject(
            """
            SELECT is_identity = 'YES'
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'auth_audit_logs'
              AND column_name = :name
            """,
            mapOf("name" to name),
            Boolean::class.java,
        )!!

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
              AND tablename = 'auth_audit_logs'
              AND indexname = :name
            """,
            mapOf("name" to name),
            Int::class.java,
        )!!
}

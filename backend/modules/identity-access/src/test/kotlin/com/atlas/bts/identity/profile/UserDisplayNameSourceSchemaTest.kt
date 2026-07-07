// V030 마이그레이션 검증 — users.display_name_source 컬럼/타입/NOT NULL/DEFAULT/CHECK 확인 (FR-PR-04 task-1)

package com.atlas.bts.identity.profile

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
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
 * Flyway V001~V030 마이그레이션 자동 적용 후 users.display_name_source 스키마를 검증한다 (FR-PR-04 task-1).
 *
 * ## 검증 항목
 * - users.display_name_source 컬럼 존재.
 * - 타입 character varying(8) · NOT NULL · DEFAULT 'LDAP'.
 * - CHECK 제약이 ('LDAP','USER')만 허용: 'INVALID' INSERT 는 거부, 'USER'/'LDAP' INSERT 는 정상.
 *
 * UserStatusesSchemaTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제(경량 부팅).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserDisplayNameSourceSchemaTest {
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

    @Test
    fun `display_name_source는 varchar 8 NOT NULL이다`() {
        val column = column("display_name_source")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(8)
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `display_name_source의 DEFAULT는 LDAP다`() {
        val column = column("display_name_source")
        assertThat(column.columnDefault).contains("LDAP")
    }

    @Test
    fun `INVALID 값은 CHECK 위반으로 거부된다`() {
        assertThatThrownBy {
            insertUserWithSource("checktest-invalid", "INVALID")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `USER 값은 정상 INSERT된다`() {
        assertThatCode {
            insertUserWithSource("checktest-user", "USER")
        }.doesNotThrowAnyException()
    }

    @Test
    fun `LDAP 값은 정상 INSERT된다`() {
        assertThatCode {
            insertUserWithSource("checktest-ldap", "LDAP")
        }.doesNotThrowAnyException()
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private data class ColumnInfo(
        val dataType: String,
        val isNullable: Boolean,
        val maxLength: Int?,
        val columnDefault: String?,
    )

    private fun column(name: String): ColumnInfo =
        jdbc.queryForObject(
            """
            SELECT data_type, is_nullable, character_maximum_length, column_default
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'users'
              AND column_name = :name
            """,
            mapOf("name" to name),
        ) { rs, _ ->
            ColumnInfo(
                dataType = rs.getString("data_type"),
                isNullable = rs.getString("is_nullable") == "YES",
                maxLength = rs.getObject("character_maximum_length")?.let { (it as Number).toInt() },
                columnDefault = rs.getString("column_default"),
            )
        }!!

    private fun insertUserWithSource(
        username: String,
        source: String,
    ) {
        jdbc.update(
            """
            INSERT INTO users (id, username, display_name_source)
            VALUES (gen_random_uuid(), :username, :source)
            """,
            mapOf("username" to username, "source" to source),
        )
    }
}

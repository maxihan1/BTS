// V028 마이그레이션 검증 — user_statuses 테이블/PK/FK CASCADE/CHECK(emoji 또는 text) 확인 (FR-PR-02 task-1)

package com.atlas.bts.identity.status

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
 * Flyway V001~V028 마이그레이션 자동 적용 후 user_statuses 스키마를 검증한다 (FR-PR-02 task-1).
 *
 * ## 검증 항목
 * - user_statuses 테이블 존재.
 * - 컬럼 6종 타입/nullable 정합:
 *   user_id(uuid, NOT NULL, PK) · emoji(varchar 32, NULL) · text(varchar 100, NULL) ·
 *   expires_at(timestamptz, NULL) · created_at(timestamptz, NOT NULL) · updated_at(timestamptz, NOT NULL).
 * - user_id 가 PRIMARY KEY.
 * - users 삭제 시 상태 연쇄 삭제 (FK ON DELETE CASCADE).
 * - CHECK 제약 존재 + emoji·text 둘 다 NULL 행 거부.
 *
 * UserProfilesSchemaTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제(경량 부팅).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserStatusesSchemaTest {
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
    fun `user_statuses 테이블이 존재한다`() {
        assertThat(tableExists("user_statuses")).isEqualTo(1)
    }

    @Test
    fun `user_id는 uuid NOT NULL이다`() {
        val column = column("user_id")
        assertThat(column.dataType).isEqualTo("uuid")
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `emoji는 varchar 32 nullable이다`() {
        val column = column("emoji")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(32)
        assertThat(column.isNullable).isTrue()
    }

    @Test
    fun `text는 varchar 100 nullable이다`() {
        val column = column("text")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(100)
        assertThat(column.isNullable).isTrue()
    }

    @Test
    fun `expires_at은 timestamptz nullable이다`() {
        val column = column("expires_at")
        assertThat(column.dataType).isEqualTo("timestamp with time zone")
        assertThat(column.isNullable).isTrue()
    }

    @Test
    fun `created_at은 timestamptz NOT NULL이다`() {
        val column = column("created_at")
        assertThat(column.dataType).isEqualTo("timestamp with time zone")
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `updated_at은 timestamptz NOT NULL이다`() {
        val column = column("updated_at")
        assertThat(column.dataType).isEqualTo("timestamp with time zone")
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `user_id가 PRIMARY KEY다`() {
        assertThat(primaryKeyColumn()).isEqualTo("user_id")
    }

    @Test
    fun `CHECK 제약이 존재한다 (emoji 또는 text non-null)`() {
        assertThat(checkConstraintCount()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `emoji와 text가 둘 다 NULL이면 CHECK 위반으로 거부된다`() {
        val userId = insertUser("status-check-user")
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO user_statuses (user_id) VALUES (CAST(:userId AS UUID))",
                mapOf("userId" to userId),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `emoji만 있어도 CHECK를 통과한다`() {
        val userId = insertUser("status-emoji-only-user")
        jdbc.update(
            "INSERT INTO user_statuses (user_id, emoji) VALUES (CAST(:userId AS UUID), :emoji)",
            mapOf("userId" to userId, "emoji" to "🌴"),
        )
        val stored =
            jdbc.queryForObject(
                "SELECT emoji FROM user_statuses WHERE user_id = CAST(:userId AS UUID)",
                mapOf("userId" to userId),
                String::class.java,
            )
        assertThat(stored).isEqualTo("🌴")
    }

    @Test
    fun `users 삭제 시 상태가 연쇄 삭제된다 (FK CASCADE)`() {
        val userId = insertUser("status-cascade-user")
        jdbc.update(
            "INSERT INTO user_statuses (user_id, text) VALUES (CAST(:userId AS UUID), :text)",
            mapOf("userId" to userId, "text" to "회의 중"),
        )

        jdbc.update(
            "DELETE FROM users WHERE id = CAST(:id AS UUID)",
            mapOf("id" to userId),
        )

        val remaining =
            jdbc.queryForObject(
                "SELECT count(*) FROM user_statuses WHERE user_id = CAST(:id AS UUID)",
                mapOf("id" to userId),
                Int::class.java,
            )
        assertThat(remaining).isEqualTo(0)
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
              AND table_name = 'user_statuses'
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

    private fun primaryKeyColumn(): String =
        jdbc.queryForObject(
            """
            SELECT kcu.column_name
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON tc.constraint_name = kcu.constraint_name
             AND tc.table_schema = kcu.table_schema
            WHERE tc.table_schema = 'public'
              AND tc.table_name = 'user_statuses'
              AND tc.constraint_type = 'PRIMARY KEY'
            """,
            emptyMap<String, Any>(),
            String::class.java,
        )!!

    private fun checkConstraintCount(): Int =
        jdbc.queryForObject(
            """
            SELECT count(*)
            FROM pg_constraint c
            JOIN pg_class t ON c.conrelid = t.oid
            JOIN pg_namespace n ON t.relnamespace = n.oid
            WHERE n.nspname = 'public'
              AND t.relname = 'user_statuses'
              AND c.contype = 'c'
            """,
            emptyMap<String, Any>(),
            Int::class.java,
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

    private fun insertUser(username: String): String =
        jdbc.queryForObject(
            "INSERT INTO users (username) VALUES (:username) RETURNING id::text",
            mapOf("username" to username),
            String::class.java,
        )!!
}

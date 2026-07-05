// V027 마이그레이션 검증 — user_profiles 테이블/PK/FK CASCADE/timezone 기본값 확인 (FR-PR-01 task-1)

package com.atlas.bts.identity.profile

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
 * Flyway V001~V027 마이그레이션 자동 적용 후 user_profiles 스키마를 검증한다 (FR-PR-01 task-1).
 *
 * ## 검증 항목
 * - user_profiles 테이블 존재.
 * - 컬럼 6종 타입/nullable 정합:
 *   user_id(uuid, NOT NULL, PK) · avatar_object_key(text, NULL) · timezone(varchar 64, NOT NULL) ·
 *   department(varchar 255, NULL) · created_at(timestamptz, NOT NULL) · updated_at(timestamptz, NOT NULL).
 * - user_id 가 PRIMARY KEY.
 * - timezone 기본값 'UTC' (미지정 INSERT 후 읽어 확인).
 * - users 삭제 시 프로필 연쇄 삭제 (FK ON DELETE CASCADE).
 *
 * MfaBackupCodesSchemaTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제(경량 부팅).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class UserProfilesSchemaTest {
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
    fun `user_profiles 테이블이 존재한다`() {
        assertThat(tableExists("user_profiles")).isEqualTo(1)
    }

    @Test
    fun `user_id는 uuid NOT NULL이다`() {
        val column = column("user_id")
        assertThat(column.dataType).isEqualTo("uuid")
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `avatar_object_key는 text nullable이다`() {
        val column = column("avatar_object_key")
        assertThat(column.dataType).isEqualTo("text")
        assertThat(column.isNullable).isTrue()
    }

    @Test
    fun `timezone는 varchar 64 NOT NULL이다`() {
        val column = column("timezone")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(64)
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `department는 varchar 255 nullable이다`() {
        val column = column("department")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(255)
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
    fun `timezone 기본값은 UTC다`() {
        val userId = insertUser("profile-tz-user")
        // timezone 미지정 INSERT → DEFAULT 'UTC' 적용.
        jdbc.update(
            "INSERT INTO user_profiles (user_id) VALUES (CAST(:userId AS UUID))",
            mapOf("userId" to userId),
        )
        val timezone =
            jdbc.queryForObject(
                "SELECT timezone FROM user_profiles WHERE user_id = CAST(:userId AS UUID)",
                mapOf("userId" to userId),
                String::class.java,
            )
        assertThat(timezone).isEqualTo("UTC")
    }

    @Test
    fun `users 삭제 시 프로필이 연쇄 삭제된다 (FK CASCADE)`() {
        val userId = insertUser("profile-cascade-user")
        jdbc.update(
            "INSERT INTO user_profiles (user_id, department) VALUES (CAST(:userId AS UUID), :dept)",
            mapOf("userId" to userId, "dept" to "Platform"),
        )

        jdbc.update(
            "DELETE FROM users WHERE id = CAST(:id AS UUID)",
            mapOf("id" to userId),
        )

        val remaining =
            jdbc.queryForObject(
                "SELECT count(*) FROM user_profiles WHERE user_id = CAST(:id AS UUID)",
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
              AND table_name = 'user_profiles'
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
              AND tc.table_name = 'user_profiles'
              AND tc.constraint_type = 'PRIMARY KEY'
            """,
            emptyMap<String, Any>(),
            String::class.java,
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

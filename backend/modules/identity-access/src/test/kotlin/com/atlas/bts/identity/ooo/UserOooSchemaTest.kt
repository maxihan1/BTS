// V029 마이그레이션 검증 — user_ooo 테이블/PK/FK CASCADE·SET NULL/CHECK(ends_at>starts_at) 확인 (FR-PR-03 task-1)

package com.atlas.bts.identity.ooo

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
 * Flyway V001~V029 마이그레이션 자동 적용 후 user_ooo 스키마를 검증한다 (FR-PR-03 task-1).
 *
 * ## 검증 항목
 * - user_ooo 테이블 존재.
 * - 컬럼 7종 타입/nullable 정합:
 *   user_id(uuid, NOT NULL, PK) · starts_at(timestamptz, NOT NULL) · ends_at(timestamptz, NOT NULL) ·
 *   delegate_user_id(uuid, NULL) · message(varchar 500, NULL) ·
 *   created_at(timestamptz, NOT NULL) · updated_at(timestamptz, NOT NULL).
 * - user_id 가 PRIMARY KEY.
 * - CHECK(ends_at > starts_at) 존재 + 위반 시 거부.
 * - users(본인) 삭제 시 user_ooo 연쇄 삭제 (FK ON DELETE CASCADE).
 * - delegate 사용자 삭제 시 delegate_user_id 만 NULL 로 (FK ON DELETE SET NULL, OOO 행 유지).
 *
 * UserStatusesSchemaTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제(경량 부팅).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserOooSchemaTest {
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

    @Test
    fun `user_ooo 테이블이 존재한다`() {
        assertThat(tableExists("user_ooo")).isEqualTo(1)
    }

    @Test
    fun `user_id는 uuid NOT NULL이다`() {
        val column = column("user_id")
        assertThat(column.dataType).isEqualTo("uuid")
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `starts_at은 timestamptz NOT NULL이다`() {
        val column = column("starts_at")
        assertThat(column.dataType).isEqualTo("timestamp with time zone")
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `ends_at은 timestamptz NOT NULL이다`() {
        val column = column("ends_at")
        assertThat(column.dataType).isEqualTo("timestamp with time zone")
        assertThat(column.isNullable).isFalse()
    }

    @Test
    fun `delegate_user_id는 uuid nullable이다`() {
        val column = column("delegate_user_id")
        assertThat(column.dataType).isEqualTo("uuid")
        assertThat(column.isNullable).isTrue()
    }

    @Test
    fun `message는 varchar 500 nullable이다`() {
        val column = column("message")
        assertThat(column.dataType).isEqualTo("character varying")
        assertThat(column.maxLength).isEqualTo(500)
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
    fun `CHECK 제약이 존재한다 (ends_at greater than starts_at)`() {
        assertThat(checkConstraintCount()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `ends_at이 starts_at 이하이면 CHECK 위반으로 거부된다`() {
        val userId = insertUser("ooo-check-user")
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO user_ooo (user_id, starts_at, ends_at)
                VALUES (CAST(:userId AS UUID), '2026-07-10T00:00:00Z', '2026-07-01T00:00:00Z')
                """,
                mapOf("userId" to userId),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `ends_at이 starts_at보다 이후면 CHECK를 통과한다`() {
        val userId = insertUser("ooo-valid-user")
        jdbc.update(
            """
            INSERT INTO user_ooo (user_id, starts_at, ends_at)
            VALUES (CAST(:userId AS UUID), '2026-07-01T00:00:00Z', '2026-07-10T00:00:00Z')
            """,
            mapOf("userId" to userId),
        )
        val stored =
            jdbc.queryForObject(
                "SELECT count(*) FROM user_ooo WHERE user_id = CAST(:userId AS UUID)",
                mapOf("userId" to userId),
                Int::class.java,
            )
        assertThat(stored).isEqualTo(1)
    }

    @Test
    fun `users 삭제 시 user_ooo가 연쇄 삭제된다 (FK CASCADE)`() {
        val userId = insertUser("ooo-cascade-user")
        jdbc.update(
            """
            INSERT INTO user_ooo (user_id, starts_at, ends_at)
            VALUES (CAST(:userId AS UUID), '2026-07-01T00:00:00Z', '2026-07-10T00:00:00Z')
            """,
            mapOf("userId" to userId),
        )

        jdbc.update("DELETE FROM users WHERE id = CAST(:id AS UUID)", mapOf("id" to userId))

        val remaining =
            jdbc.queryForObject(
                "SELECT count(*) FROM user_ooo WHERE user_id = CAST(:id AS UUID)",
                mapOf("id" to userId),
                Int::class.java,
            )
        assertThat(remaining).isEqualTo(0)
    }

    @Test
    fun `대리자 삭제 시 delegate_user_id만 NULL로 바뀌고 OOO 행은 유지된다 (FK SET NULL)`() {
        val userId = insertUser("ooo-owner-user")
        val delegateId = insertUser("ooo-delegate-user")
        jdbc.update(
            """
            INSERT INTO user_ooo (user_id, starts_at, ends_at, delegate_user_id)
            VALUES (CAST(:userId AS UUID), '2026-07-01T00:00:00Z', '2026-07-10T00:00:00Z', CAST(:delegateId AS UUID))
            """,
            mapOf("userId" to userId, "delegateId" to delegateId),
        )

        jdbc.update("DELETE FROM users WHERE id = CAST(:id AS UUID)", mapOf("id" to delegateId))

        val row =
            jdbc.queryForObject(
                "SELECT delegate_user_id FROM user_ooo WHERE user_id = CAST(:id AS UUID)",
                mapOf("id" to userId),
            ) { rs, _ -> rs.getObject("delegate_user_id") }
        assertThat(row).isNull()
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
              AND table_name = 'user_ooo'
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
              AND tc.table_name = 'user_ooo'
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
              AND t.relname = 'user_ooo'
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

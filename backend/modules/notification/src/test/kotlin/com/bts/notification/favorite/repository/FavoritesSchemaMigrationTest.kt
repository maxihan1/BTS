// notification V406 마이그레이션 검증 — favorites 테이블 + 컬럼 타입 + UNIQUE(user_id,target_type,target_id) + deleted_at 부재(하드 삭제) 확인

package com.bts.notification.favorite.repository

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * Flyway V400~V406 마이그레이션 적용 후 favorites 테이블을 검증한다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을 직접 사용하며
 * Spring 컨텍스트 없이 실행한다. notification BC 마이그레이션 체인(V400~V406)은 pgmq 확장을
 * 요구하지 않으므로 postgres:16-alpine 이미지로 충분하다 (V405DashboardsSchemaTest 동일 패턴).
 *
 * 검증 범위 (FR-UX-02 plan Task 2 / DATA.md §3 하드 삭제 / §4 TIMESTAMPTZ / §7 BC 격리).
 * - favorites 테이블 존재 + 전 컬럼(id/user_id/target_type/target_id/created_at)
 * - id uuid PK NOT NULL, user_id uuid NOT NULL, target_type varchar NOT NULL, target_id varchar NOT NULL
 * - created_at timestamptz NOT NULL (DATA.md §4 TIMESTAMPTZ 강제)
 * - UNIQUE(user_id, target_type, target_id) 제약(uq_favorites_user_target) 존재 + 중복 INSERT 위반 동작
 * - deleted_at 컬럼 부재 검증 (DATA.md §3 하드 삭제 — 즐겨찾기 해제는 행 즉시 제거)
 *
 * 정보 스키마(information_schema / pg_constraint) 조회로 단언한다. SQL 문자열 결합 없이 prepared statement 사용.
 */
@Testcontainers
class FavoritesSchemaMigrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bts_notification_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/notification")
                .load()
                .migrate()
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun tableExists(tableName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // Connection → prepareStatement → executeQuery 3중 use 블록 중첩. SQL 헬퍼의 관용적 패턴이므로 Suppress.
    @Suppress("NestedBlockDepth")
    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    @Suppress("NestedBlockDepth")
    private fun columnIsNullable(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // 지정 이름의 UNIQUE 제약(contype = 'u') 존재 여부 조회 — uq_favorites_user_target 검증용.
    @Suppress("NestedBlockDepth")
    private fun uniqueConstraintExists(
        tableName: String,
        constraintName: String,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_constraint" +
                    " WHERE contype = 'u' AND conrelid = ?::regclass AND conname = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, constraintName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // favorites 한 행 INSERT — UNIQUE 위반 유도용. id 는 명시 지정(테이블이 DEFAULT 를 두지 않을 수 있어 안전).
    private fun insertFavorite(
        userId: UUID,
        targetType: String,
        targetId: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO favorites (id, user_id, target_type, target_id)" +
                    " VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setObject(2, userId)
                stmt.setString(3, targetType)
                stmt.setString(4, targetId)
                stmt.executeUpdate()
            }
        }
    }

    // ── 테이블 존재 검증 ───────────────────────────────────────────────────────

    @Test
    fun `V406 favorites 테이블 존재`() {
        assertThat(tableExists("favorites")).isTrue()
    }

    // ── 전 컬럼 존재 검증 ──────────────────────────────────────────────────────

    @Test
    fun `V406 favorites 전 컬럼 존재`() {
        listOf("id", "user_id", "target_type", "target_id", "created_at").forEach { column ->
            assertThat(columnExists("favorites", column))
                .`as`("favorites.$column 컬럼 존재")
                .isTrue()
        }
    }

    // ── 컬럼 타입 / NOT NULL 검증 (DATA.md §4) ────────────────────────────────

    @Test
    fun `V406 id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("favorites", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("favorites", "id")).isEqualTo("NO")
    }

    @Test
    fun `V406 user_id 는 uuid NOT NULL`() {
        assertThat(columnDataType("favorites", "user_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("favorites", "user_id")).isEqualTo("NO")
    }

    @Test
    fun `V406 target_type 은 character varying NOT NULL`() {
        assertThat(columnDataType("favorites", "target_type")).isEqualTo("character varying")
        assertThat(columnIsNullable("favorites", "target_type")).isEqualTo("NO")
    }

    @Test
    fun `V406 target_id 는 character varying NOT NULL`() {
        assertThat(columnDataType("favorites", "target_id")).isEqualTo("character varying")
        assertThat(columnIsNullable("favorites", "target_id")).isEqualTo("NO")
    }

    @Test
    fun `V406 created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("favorites", "created_at"))
            .isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("favorites", "created_at")).isEqualTo("NO")
    }

    // ── deleted_at 부재 검증 (DATA.md §3 하드 삭제) ────────────────────────────

    @Test
    fun `V406 favorites 는 deleted_at 컬럼이 없다 (하드 삭제)`() {
        assertThat(columnExists("favorites", "deleted_at"))
            .`as`("favorites 는 하드 삭제 테이블이므로 deleted_at 컬럼이 없어야 한다 (DATA.md §3)")
            .isFalse()
    }

    // ── UNIQUE(user_id, target_type, target_id) 검증 ──────────────────────────

    @Test
    fun `V406 uq_favorites_user_target 유니크 제약 존재`() {
        assertThat(uniqueConstraintExists("favorites", "uq_favorites_user_target")).isTrue()
    }

    @Test
    fun `V406 같은 user_id+target_type+target_id 조합 중복 INSERT 는 유니크 위반`() {
        val userId = UUID.randomUUID()
        insertFavorite(userId, "ISSUE", "ATL-1")
        // 같은 (user_id, target_type, target_id) 조합 재삽입 시 제약명(uq_favorites_user_target) 위반.
        assertThatThrownBy { insertFavorite(userId, "ISSUE", "ATL-1") }
            .hasMessageContaining("uq_favorites_user_target")
    }
}

// notification V405 마이그레이션 검증 — dashboards + dashboard_shares 테이블/컬럼 타입/복합 PK/FK CASCADE/인덱스 존재 확인

package com.bts.notification.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * Flyway V400~V405 마이그레이션 적용 후 dashboards / dashboard_shares 테이블을 검증한다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을 직접 사용하며
 * Spring 컨텍스트 없이 실행한다. notification BC 마이그레이션 체인(V400~V405)은 pgmq 확장을
 * 요구하지 않으므로 postgres:16-alpine 이미지로 충분하다 (UserNotificationSubsSchemaTest 동일).
 *
 * 검증 범위 (FR-DB-01 §데이터 모델).
 * - dashboards 테이블 존재 + 전 컬럼(id/owner_id/name/description/visibility/layout/created_at/updated_at/deleted_at/version)
 * - dashboard_shares 테이블 + 복합 PK (dashboard_id, user_id)
 * - FK dashboard_shares.dashboard_id → dashboards.id ON DELETE CASCADE
 * - 인덱스 idx_dashboards_owner / idx_dashboards_visibility (부분 인덱스 WHERE deleted_at IS NULL) / idx_dashboard_shares_user
 * - layout 컬럼 타입 jsonb, 타임스탬프 컬럼 timestamptz
 *
 * 정보 스키마(information_schema / pg_indexes / pg_constraint) 조회로 단언한다. SQL 문자열 결합 없이 prepared statement 사용.
 *
 * 참조. FR-DB-01 plan Task 2 / DATA.md §3 소프트 삭제 / §4 TIMESTAMPTZ / §7 BC 격리(owner_id/user_id FK 없음).
 */
@Testcontainers
class V405DashboardsSchemaTest {
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

    private fun indexExists(indexName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes" +
                    " WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    @Suppress("NestedBlockDepth")
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

    // partial index 의 술어(WHERE 절)를 pg_indexes.indexdef 에서 조회 — WHERE deleted_at IS NULL 검증용.
    @Suppress("NestedBlockDepth")
    private fun indexDef(indexName: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // 테이블의 PRIMARY KEY 를 구성하는 컬럼 목록을 순서대로 조회 — 복합 PK 검증용.
    @Suppress("NestedBlockDepth")
    private fun primaryKeyColumns(tableName: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT a.attname FROM pg_index i" +
                    " JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)" +
                    " WHERE i.indrelid = ?::regclass AND i.indisprimary" +
                    " ORDER BY array_position(i.indkey, a.attnum)",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    // dashboard_shares.dashboard_id 외래키의 ON DELETE 동작(confdeltype)을 조회 — CASCADE = 'c'.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyDeleteAction(
        tableName: String,
        constraintNameLike: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT confdeltype FROM pg_constraint" +
                    " WHERE contype = 'f' AND conrelid = ?::regclass" +
                    " AND conname LIKE ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, constraintNameLike)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    // ── 테이블 존재 검증 ───────────────────────────────────────────────────────

    @Test
    fun `V405 dashboards 테이블 존재`() {
        assertThat(tableExists("dashboards")).isTrue()
    }

    @Test
    fun `V405 dashboard_shares 테이블 존재`() {
        assertThat(tableExists("dashboard_shares")).isTrue()
    }

    // ── dashboards 컬럼 존재 검증 ──────────────────────────────────────────────

    @Test
    fun `V405 dashboards 전 컬럼 존재`() {
        listOf(
            "id", "owner_id", "name", "description", "visibility",
            "layout", "created_at", "updated_at", "deleted_at", "version",
        ).forEach { column ->
            assertThat(columnExists("dashboards", column))
                .`as`("dashboards.$column 컬럼 존재")
                .isTrue()
        }
    }

    // ── dashboards 컬럼 타입 / NOT NULL 검증 (DATA.md §3/§4) ───────────────────

    @Test
    fun `V405 dashboards id 는 uuid PK NOT NULL`() {
        assertThat(columnDataType("dashboards", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("dashboards", "id")).isEqualTo("NO")
    }

    @Test
    fun `V405 dashboards owner_id 는 uuid NOT NULL`() {
        assertThat(columnDataType("dashboards", "owner_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("dashboards", "owner_id")).isEqualTo("NO")
    }

    @Test
    fun `V405 dashboards name 은 text NOT NULL`() {
        assertThat(columnDataType("dashboards", "name")).isEqualTo("text")
        assertThat(columnIsNullable("dashboards", "name")).isEqualTo("NO")
    }

    @Test
    fun `V405 dashboards description 은 text NULL 허용`() {
        assertThat(columnDataType("dashboards", "description")).isEqualTo("text")
        assertThat(columnIsNullable("dashboards", "description")).isEqualTo("YES")
    }

    @Test
    fun `V405 dashboards visibility 는 text NOT NULL`() {
        assertThat(columnDataType("dashboards", "visibility")).isEqualTo("text")
        assertThat(columnIsNullable("dashboards", "visibility")).isEqualTo("NO")
    }

    @Test
    fun `V405 dashboards layout 은 jsonb NOT NULL`() {
        assertThat(columnDataType("dashboards", "layout")).isEqualTo("jsonb")
        assertThat(columnIsNullable("dashboards", "layout")).isEqualTo("NO")
    }

    @Test
    fun `V405 dashboards created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("dashboards", "created_at"))
            .isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("dashboards", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V405 dashboards updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("dashboards", "updated_at"))
            .isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("dashboards", "updated_at")).isEqualTo("NO")
    }

    @Test
    fun `V405 dashboards deleted_at 은 timestamptz NULL 허용 (소프트 삭제)`() {
        assertThat(columnDataType("dashboards", "deleted_at"))
            .isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("dashboards", "deleted_at")).isEqualTo("YES")
    }

    @Test
    fun `V405 dashboards version 은 bigint NOT NULL`() {
        assertThat(columnDataType("dashboards", "version")).isEqualTo("bigint")
        assertThat(columnIsNullable("dashboards", "version")).isEqualTo("NO")
    }

    // ── dashboard_shares 복합 PK / 컬럼 타입 검증 ──────────────────────────────

    @Test
    fun `V405 dashboard_shares 복합 PK 는 dashboard_id, user_id`() {
        assertThat(primaryKeyColumns("dashboard_shares"))
            .containsExactly("dashboard_id", "user_id")
    }

    @Test
    fun `V405 dashboard_shares dashboard_id 는 uuid NOT NULL`() {
        assertThat(columnDataType("dashboard_shares", "dashboard_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("dashboard_shares", "dashboard_id")).isEqualTo("NO")
    }

    @Test
    fun `V405 dashboard_shares user_id 는 uuid NOT NULL`() {
        assertThat(columnDataType("dashboard_shares", "user_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("dashboard_shares", "user_id")).isEqualTo("NO")
    }

    // ── FK ON DELETE CASCADE 검증 ──────────────────────────────────────────────

    @Test
    fun `V405 dashboard_shares dashboard_id FK 는 dashboards 를 ON DELETE CASCADE 참조`() {
        // pg_constraint.confdeltype 값: 'c' = CASCADE, 'a' = NO ACTION, 'r' = RESTRICT 등.
        assertThat(foreignKeyDeleteAction("dashboard_shares", "%dashboard_id%"))
            .isEqualTo("c")
    }

    // ── 인덱스 존재 / 부분 인덱스 술어 검증 ────────────────────────────────────

    @Test
    fun `V405 idx_dashboards_owner 인덱스 존재 (부분 인덱스 WHERE deleted_at IS NULL)`() {
        assertThat(indexExists("idx_dashboards_owner")).isTrue()
        val def = indexDef("idx_dashboards_owner")
        assertThat(def).isNotNull()
        assertThat(def!!.lowercase()).contains("where").contains("deleted_at is null")
    }

    @Test
    fun `V405 idx_dashboards_visibility 인덱스 존재 (부분 인덱스 WHERE deleted_at IS NULL)`() {
        assertThat(indexExists("idx_dashboards_visibility")).isTrue()
        val def = indexDef("idx_dashboards_visibility")
        assertThat(def).isNotNull()
        assertThat(def!!.lowercase()).contains("where").contains("deleted_at is null")
    }

    @Test
    fun `V405 idx_dashboard_shares_user 인덱스 존재`() {
        assertThat(indexExists("idx_dashboard_shares_user")).isTrue()
    }
}

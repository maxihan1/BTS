// V407 마이그레이션 검증 — notifications.archived_at/actor_user_id 컬럼 + 미읽음 부분 인덱스 존재 (FR-UX-03)

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
 * Flyway V400~V407 마이그레이션 적용 후 notifications 테이블의 FR-UX-03 Inbox 확장을 검증한다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을 직접 사용하며
 * Spring 컨텍스트 없이 실행한다. notification BC 마이그레이션 체인(V400~V407)은 pgmq 확장을
 * 요구하지 않으므로 postgres:16-alpine 이미지로 충분하다 (V405DashboardsSchemaTest 동일 패턴).
 *
 * 검증 범위 (FR-UX-03 plan Task 1 / spec §데이터 모델 변경 / DATA.md §4 TIMESTAMPTZ).
 * - notifications.archived_at  컬럼 존재 + timestamptz + NULL 허용 (보관 시각, NULL=미보관)
 * - notifications.actor_user_id 컬럼 존재 + uuid + NULL 허용 (발신자 userId, NULL=시스템/없음)
 * - partial index ix_notifications_recipient_unread 존재 +
 *   술어(WHERE read_at IS NULL AND archived_at IS NULL AND channel = 'IN_APP') 포함 (CONCERN-4)
 *
 * 정보 스키마(information_schema / pg_indexes) 조회로 단언한다. SQL 문자열 결합 없이 prepared statement 사용.
 *
 * 참조. FR-UX-03 plan Task 1 / spec §데이터 모델 변경 / DATA.md §4 TIMESTAMPTZ 강제 / §7 BC 격리(actor_user_id FK 없음).
 */
@Testcontainers
class NotificationsInboxSchemaMigrationTest {
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

    // partial index 의 술어(WHERE 절)를 pg_indexes.indexdef 에서 조회 — 미읽음 카운트 부분 인덱스 검증용.
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

    // ── archived_at 컬럼 검증 ──────────────────────────────────────────────────

    @Test
    fun `V407 notifications archived_at 컬럼 존재`() {
        assertThat(columnExists("notifications", "archived_at"))
            .`as`("notifications.archived_at 컬럼 존재 (FR-UX-03 보관 시각)")
            .isTrue()
    }

    @Test
    fun `V407 notifications archived_at 은 timestamptz NULL 허용`() {
        assertThat(columnDataType("notifications", "archived_at"))
            .isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("notifications", "archived_at")).isEqualTo("YES")
    }

    // ── actor_user_id 컬럼 검증 ────────────────────────────────────────────────

    @Test
    fun `V407 notifications actor_user_id 컬럼 존재`() {
        assertThat(columnExists("notifications", "actor_user_id"))
            .`as`("notifications.actor_user_id 컬럼 존재 (FR-UX-03 발신자 검색)")
            .isTrue()
    }

    @Test
    fun `V407 notifications actor_user_id 는 uuid NULL 허용`() {
        assertThat(columnDataType("notifications", "actor_user_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("notifications", "actor_user_id")).isEqualTo("YES")
    }

    // ── partial index 검증 (CONCERN-4: channel='IN_APP' 조건 포함) ─────────────

    @Test
    fun `V407 ix_notifications_recipient_unread 부분 인덱스 존재`() {
        assertThat(indexExists("ix_notifications_recipient_unread")).isTrue()
    }

    @Test
    fun `V407 ix_notifications_recipient_unread 는 read_at archived_at IN_APP 술어를 가진다`() {
        val def = indexDef("ix_notifications_recipient_unread")
        assertThat(def).isNotNull()
        val lower = def!!.lowercase()
        assertThat(lower).contains("where")
        assertThat(lower).contains("read_at is null")
        assertThat(lower).contains("archived_at is null")
        assertThat(lower).contains("in_app")
    }
}

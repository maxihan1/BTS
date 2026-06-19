// notification V404 마이그레이션 검증 — user_notification_subs 테이블 + 컬럼 타입 + UNIQUE + partial index(WHERE enabled=false) 존재 확인

package com.bts.notification.migration

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
 * Flyway V400~V404 마이그레이션 적용 후 user_notification_subs 테이블을 검증한다.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) 의 PostgreSQL 을 직접 사용하며
 * Spring 컨텍스트 없이 실행한다. notification BC 마이그레이션 체인(V400~V404)은 pgmq 확장을
 * 요구하지 않으므로 postgres:16-alpine 이미지로 충분하다 (NotificationTestcontainersBase 동일).
 *
 * 검증 범위 (FR-NT-04 §데이터 모델).
 * - user_notification_subs 테이블 존재
 * - id(uuid PK) / user_id(uuid NOT NULL) / event_type(text NOT NULL) / channel(text NOT NULL)
 *   / enabled(boolean NOT NULL) / created_at(timestamptz NOT NULL) / updated_at(timestamptz NOT NULL)
 * - UNIQUE(user_id, event_type, channel) 제약 — 같은 조합 중복 INSERT 시 위반
 * - partial index idx_user_notif_subs_disabled — (event_type, channel, user_id) WHERE enabled = false
 *
 * 정보 스키마(information_schema / pg_indexes) 조회로 단언한다. SQL 문자열 결합 없이 prepared statement 사용.
 *
 * 참조. FR-NT-04 plan Task 1 / DATA.md §4 TIMESTAMPTZ / §7 BC 격리(user_id FK 없음, UUID 직접 저장).
 */
@Testcontainers
class UserNotificationSubsSchemaTest {
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

    // partial index 의 술어(WHERE 절)를 pg_indexes.indexdef 에서 조회 — WHERE enabled = false 검증용.
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

    // enabled 를 지정해 user_notification_subs 한 행 INSERT — UNIQUE 위반 유도용.
    private fun insertSub(
        userId: UUID,
        eventType: String,
        channel: String,
        enabled: Boolean,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO user_notification_subs (user_id, event_type, channel, enabled)" +
                    " VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, userId)
                stmt.setString(2, eventType)
                stmt.setString(3, channel)
                stmt.setBoolean(4, enabled)
                stmt.executeUpdate()
            }
        }
    }

    // ── 테이블/인덱스 존재 검증 ────────────────────────────────────────────────

    @Test
    fun `V404 user_notification_subs 테이블 존재`() {
        assertThat(tableExists("user_notification_subs")).isTrue()
    }

    @Test
    fun `V404 partial index idx_user_notif_subs_disabled 존재`() {
        assertThat(indexExists("idx_user_notif_subs_disabled")).isTrue()
    }

    @Test
    fun `V404 partial index 는 WHERE enabled = false 술어를 가진다`() {
        val def = indexDef("idx_user_notif_subs_disabled")
        // pg_indexes.indexdef 는 술어를 "WHERE (enabled = false)" 형태로 정규화한다.
        assertThat(def).isNotNull()
        assertThat(def!!.lowercase()).contains("where").contains("enabled = false")
    }

    // ── 컬럼 타입 / NOT NULL 검증 (DATA.md §4) ────────────────────────────────

    @Test
    fun `V404 id 는 uuid PK`() {
        assertThat(columnDataType("user_notification_subs", "id")).isEqualTo("uuid")
        assertThat(columnIsNullable("user_notification_subs", "id")).isEqualTo("NO")
    }

    @Test
    fun `V404 user_id 는 uuid NOT NULL`() {
        assertThat(columnDataType("user_notification_subs", "user_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("user_notification_subs", "user_id")).isEqualTo("NO")
    }

    @Test
    fun `V404 event_type 은 text NOT NULL`() {
        assertThat(columnDataType("user_notification_subs", "event_type")).isEqualTo("text")
        assertThat(columnIsNullable("user_notification_subs", "event_type")).isEqualTo("NO")
    }

    @Test
    fun `V404 channel 은 text NOT NULL`() {
        assertThat(columnDataType("user_notification_subs", "channel")).isEqualTo("text")
        assertThat(columnIsNullable("user_notification_subs", "channel")).isEqualTo("NO")
    }

    @Test
    fun `V404 enabled 는 boolean NOT NULL`() {
        assertThat(columnDataType("user_notification_subs", "enabled")).isEqualTo("boolean")
        assertThat(columnIsNullable("user_notification_subs", "enabled")).isEqualTo("NO")
    }

    @Test
    fun `V404 created_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("user_notification_subs", "created_at"))
            .isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("user_notification_subs", "created_at")).isEqualTo("NO")
    }

    @Test
    fun `V404 updated_at 은 timestamptz NOT NULL`() {
        assertThat(columnDataType("user_notification_subs", "updated_at"))
            .isEqualTo("timestamp with time zone")
        assertThat(columnIsNullable("user_notification_subs", "updated_at")).isEqualTo("NO")
    }

    // ── UNIQUE(user_id, event_type, channel) 동작 검증 ────────────────────────

    @Test
    fun `V404 같은 user_id+event_type+channel 조합 중복 INSERT 는 유니크 위반`() {
        val userId = UUID.randomUUID()
        insertSub(userId, "issue.created", "EMAIL", enabled = false)
        // enabled 값이 달라도 (user_id, event_type, channel) 조합이 같으면 위반이어야 한다.
        assertThatThrownBy { insertSub(userId, "issue.created", "EMAIL", enabled = true) }
            .hasMessageContaining("user_notification_subs")
    }
}

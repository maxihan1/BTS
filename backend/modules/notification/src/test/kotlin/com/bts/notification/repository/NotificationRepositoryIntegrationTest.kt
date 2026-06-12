// NotificationRepository 스키마 통합 테스트 — Testcontainers PG16 + V402 notifications 마이그레이션 검증

package com.bts.notification.repository

import com.bts.notification.support.NotificationTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `notifications` 테이블(V402) 스키마 통합 테스트.
 *
 * Testcontainers PG16-alpine 위에서 V400~V402 마이그레이션 체인 적용 후
 * 테이블 존재 · 필수 컬럼 · 컬럼 타입(TIMESTAMPTZ 강제) · UNIQUE(dedup_key) ·
 * 수신자 조회 인덱스를 information_schema / pg_catalog 로 검증한다.
 *
 * 스키마 단위 검증이므로 Repository 클래스에 의존하지 않고 순수 jOOQ DSLContext 로
 * 시스템 카탈로그를 조회한다(V402 DDL 자체가 검증 대상).
 */
class NotificationRepositoryIntegrationTest : NotificationTestcontainersBase() {
    // ── 테이블 존재 ───────────────────────────────────────────────────────────────

    @Test
    fun `notifications 테이블이 존재한다`() {
        val exists =
            dsl.fetchOne(
                """
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_name = 'notifications'
                ) AS present
                """.trimIndent(),
            )?.get("present", Boolean::class.java)

        assertThat(exists).isTrue()
    }

    // ── 필수 컬럼 + nullable 여부 ─────────────────────────────────────────────────

    @Test
    fun `notifications 테이블이 스펙의 모든 컬럼을 가진다`() {
        val columns =
            dsl.fetch(
                """
                SELECT column_name, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'notifications'
                """.trimIndent(),
            ).associate {
                it.get("column_name", String::class.java) to it.get("is_nullable", String::class.java)
            }

        // 모든 컬럼 존재 확인
        assertThat(columns.keys).containsExactlyInAnyOrder(
            "id",
            "recipient_user_id",
            "event_type",
            "channel",
            "issue_key",
            "title",
            "body",
            "payload",
            "status",
            "dedup_key",
            "read_at",
            "created_at",
        )

        // NOT NULL 컬럼
        assertThat(columns["id"]).isEqualTo("NO")
        assertThat(columns["recipient_user_id"]).isEqualTo("NO")
        assertThat(columns["event_type"]).isEqualTo("NO")
        assertThat(columns["channel"]).isEqualTo("NO")
        assertThat(columns["title"]).isEqualTo("NO")
        assertThat(columns["status"]).isEqualTo("NO")
        assertThat(columns["dedup_key"]).isEqualTo("NO")
        assertThat(columns["created_at"]).isEqualTo("NO")

        // NULL 허용 컬럼
        assertThat(columns["issue_key"]).isEqualTo("YES")
        assertThat(columns["body"]).isEqualTo("YES")
        assertThat(columns["payload"]).isEqualTo("YES")
        assertThat(columns["read_at"]).isEqualTo("YES")
    }

    // ── 컬럼 타입: TIMESTAMPTZ 강제 + UUID + JSONB ───────────────────────────────

    @Test
    fun `시각 컬럼은 TIMESTAMPTZ이고 id는 UUID, payload는 JSONB이다`() {
        val types =
            dsl.fetch(
                """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'notifications'
                """.trimIndent(),
            ).associate {
                it.get("column_name", String::class.java) to it.get("data_type", String::class.java)
            }

        // TIMESTAMPTZ (with time zone) 강제 — DATA.md / DEVELOPMENT.md
        assertThat(types["created_at"]).isEqualTo("timestamp with time zone")
        assertThat(types["read_at"]).isEqualTo("timestamp with time zone")

        // id / recipient_user_id = UUID
        assertThat(types["id"]).isEqualTo("uuid")
        assertThat(types["recipient_user_id"]).isEqualTo("uuid")

        // payload = JSONB
        assertThat(types["payload"]).isEqualTo("jsonb")
    }

    // ── UNIQUE(dedup_key) 제약 ───────────────────────────────────────────────────

    @Test
    fun `dedup_key에 UNIQUE 제약이 걸려 있다`() {
        // 동일 dedup_key 두 번 삽입 시 두 번째가 실패해야 한다.
        val recipient = "11111111-1111-1111-1111-111111111111"
        dsl.execute(
            """
            INSERT INTO notifications
                (id, recipient_user_id, event_type, channel, title, status, dedup_key)
            VALUES
                (gen_random_uuid(), ?::uuid, 'issue.created', 'IN_APP', '제목1', 'PENDING', 'dedup-unique-1')
            """.trimIndent(),
            recipient,
        )

        val secondInsertFailed =
            runCatching {
                dsl.execute(
                    """
                    INSERT INTO notifications
                        (id, recipient_user_id, event_type, channel, title, status, dedup_key)
                    VALUES
                        (gen_random_uuid(), ?::uuid, 'issue.created', 'IN_APP', '제목2', 'PENDING', 'dedup-unique-1')
                    """.trimIndent(),
                    recipient,
                )
            }.isFailure

        assertThat(secondInsertFailed).isTrue()
    }

    // ── 수신자 조회 인덱스 ───────────────────────────────────────────────────────

    @Test
    fun `수신자별 최신순 조회 인덱스 ix_notifications_recipient가 존재한다`() {
        val indexExists =
            dsl.fetchOne(
                """
                SELECT EXISTS (
                    SELECT 1 FROM pg_indexes
                    WHERE schemaname = 'public'
                      AND tablename = 'notifications'
                      AND indexname = 'ix_notifications_recipient'
                ) AS present
                """.trimIndent(),
            )?.get("present", Boolean::class.java)

        assertThat(indexExists).isTrue()
    }
}

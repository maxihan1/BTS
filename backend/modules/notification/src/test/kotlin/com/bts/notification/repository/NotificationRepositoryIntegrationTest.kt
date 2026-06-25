// NotificationRepository 스키마 통합 테스트 — Testcontainers PG16 + V402 notifications 마이그레이션 검증

package com.bts.notification.repository

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import com.bts.notification.support.NotificationTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * notifications 테이블(V402) 스키마 통합 테스트 + NotificationRepository 동작 검증.
 *
 * Testcontainers PG16-alpine 위에서 V400~V402 마이그레이션 체인 적용 후
 * 테이블 존재, 필수 컬럼, 컬럼 타입(TIMESTAMPTZ 강제), UNIQUE(dedup_key),
 * 수신자 조회 인덱스를 information_schema / pg_catalog 로 검증한다.
 *
 * 추가로 NotificationRepository.insertIfAbsent 멱등성과
 * findByRecipient 조회를 통합 검증한다.
 */
class NotificationRepositoryIntegrationTest : NotificationTestcontainersBase() {
    // ── NotificationRepository 헬퍼 ─────────────────────────────────────────────

    /** 테스트마다 새로 생성 — DSLContext 는 bootstrap() 이후 확정된다. */
    private val repo get() = NotificationRepository(dsl)

    private fun buildNotification(
        recipientUserId: UUID = UUID.randomUUID(),
        dedupKey: String = "dedup-${UUID.randomUUID()}",
    ): Notification {
        val now = Instant.parse("2026-06-12T09:00:00Z")
        return Notification(
            id = UUID.randomUUID(),
            recipientUserId = recipientUserId,
            eventType = NotificationEventType.ISSUE_CREATED,
            channel = Channel.IN_APP,
            issueKey = "ATLAS-1",
            title = "새 이슈가 생성되었습니다",
            body = null,
            payload = null,
            status = NotificationStatus.PENDING,
            dedupKey = dedupKey,
            readAt = null,
            createdAt = now,
        )
    }

    // ── insertIfAbsent 멱등 테스트 ───────────────────────────────────────────────

    @Test
    fun `동일 dedup_key 첫 번째 삽입은 true를 반환하고 행이 1개 생성된다`() {
        val notification = buildNotification(dedupKey = "dedup-idempotent-first")
        val inserted = repo.insertIfAbsent(notification)

        assertThat(inserted).isTrue()
        val count =
            dsl.fetchOne(
                "SELECT COUNT(*) AS cnt FROM notifications WHERE dedup_key = ?",
                "dedup-idempotent-first",
            )?.get("cnt", Long::class.java)
        assertThat(count).isEqualTo(1L)
    }

    @Test
    fun `동일 dedup_key 두 번째 삽입은 false를 반환하고 행이 여전히 1개다`() {
        val notification = buildNotification(dedupKey = "dedup-idempotent-second")
        repo.insertIfAbsent(notification)
        val secondResult = repo.insertIfAbsent(notification.copy(id = UUID.randomUUID()))

        assertThat(secondResult).isFalse()
        val count =
            dsl.fetchOne(
                "SELECT COUNT(*) AS cnt FROM notifications WHERE dedup_key = ?",
                "dedup-idempotent-second",
            )?.get("cnt", Long::class.java)
        assertThat(count).isEqualTo(1L)
    }

    // ── markSent 상태 전이 테스트 ─────────────────────────────────────────────────

    @Test
    fun `markSent는 PENDING 행을 SENT로 갱신한다`() {
        val notification = buildNotification(dedupKey = "dedup-mark-sent")
        repo.insertIfAbsent(notification)

        repo.markSent(notification.id)

        val status =
            dsl.fetchOne(
                "SELECT status FROM notifications WHERE id = ?",
                notification.id,
            )?.get("status", String::class.java)
        assertThat(status).isEqualTo("SENT")
    }

    // ── findByRecipient 조회 테스트 ───────────────────────────────────────────────

    @Test
    fun `recipient_user_id로 조회하면 해당 수신자의 알림만 반환된다`() {
        val aliceId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val bobId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

        repo.insertIfAbsent(buildNotification(recipientUserId = aliceId, dedupKey = "dedup-alice-1"))
        repo.insertIfAbsent(buildNotification(recipientUserId = aliceId, dedupKey = "dedup-alice-2"))
        repo.insertIfAbsent(buildNotification(recipientUserId = bobId, dedupKey = "dedup-bob-1"))

        val aliceNotifications = repo.findByRecipient(aliceId)

        assertThat(aliceNotifications).hasSize(2)
        assertThat(aliceNotifications).allMatch { it.recipientUserId == aliceId }
    }

    @Test
    fun `findByRecipient는 created_at 내림차순으로 정렬한다`() {
        val recipientId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val t1 = Instant.parse("2026-06-12T08:00:00Z")
        val t2 = Instant.parse("2026-06-12T09:00:00Z")

        val older =
            buildNotification(recipientUserId = recipientId, dedupKey = "dedup-sort-old")
                .copy(createdAt = t1)
        val newer =
            buildNotification(recipientUserId = recipientId, dedupKey = "dedup-sort-new")
                .copy(createdAt = t2)

        repo.insertIfAbsent(older)
        repo.insertIfAbsent(newer)

        val results = repo.findByRecipient(recipientId)

        assertThat(results).hasSize(2)
        assertThat(results[0].dedupKey).isEqualTo("dedup-sort-new")
        assertThat(results[1].dedupKey).isEqualTo("dedup-sort-old")
    }
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

        // 모든 컬럼 존재 확인 (V407 으로 archived_at / actor_user_id 추가 — FR-UX-03 Inbox)
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
            "archived_at",
            "actor_user_id",
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

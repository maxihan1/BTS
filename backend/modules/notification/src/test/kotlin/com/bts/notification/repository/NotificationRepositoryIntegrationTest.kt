// NotificationRepository 스키마 통합 테스트 — Testcontainers PG16 + V402/V407 notifications 마이그레이션 검증

package com.bts.notification.repository

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import com.bts.notification.support.NotificationTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.UUID

/**
 * notifications 테이블(V402/V407) 스키마 통합 테스트 + NotificationRepository 동작 검증.
 *
 * Testcontainers PG16-alpine 위에서 V400~V407 마이그레이션 체인 적용 후
 * 테이블 존재, 필수 컬럼, 컬럼 타입(TIMESTAMPTZ 강제), UNIQUE(dedup_key),
 * 수신자 조회 인덱스를 information_schema / pg_catalog 로 검증한다.
 *
 * 추가로 NotificationRepository.insertIfAbsent 멱등성과
 * findByRecipient 조회, Inbox 기능(findInbox/countUnread/update) 을 통합 검증한다.
 */
class NotificationRepositoryIntegrationTest : NotificationTestcontainersBase() {
    // ── NotificationRepository 헬퍼 ─────────────────────────────────────────────

    /** 테스트마다 새로 생성 — DSLContext 는 bootstrap() 이후 확정된다. */
    private val repo get() = NotificationRepository(dsl)

    @AfterEach
    fun cleanNotifications() {
        dsl.execute("DELETE FROM notifications")
    }

    @Suppress("LongParameterList")
    private fun buildNotification(
        recipientUserId: UUID = UUID.randomUUID(),
        dedupKey: String = "dedup-${UUID.randomUUID()}",
        channel: Channel = Channel.IN_APP,
        title: String = "새 이슈가 생성되었습니다",
        issueKey: String? = "ATLAS-1",
        readAt: Instant? = null,
        archivedAt: Instant? = null,
        actorUserId: UUID? = null,
        createdAt: Instant = Instant.parse("2026-06-12T09:00:00Z"),
    ): Notification =
        Notification(
            id = UUID.randomUUID(),
            recipientUserId = recipientUserId,
            eventType = NotificationEventType.ISSUE_CREATED,
            channel = channel,
            issueKey = issueKey,
            title = title,
            body = null,
            payload = null,
            status = NotificationStatus.PENDING,
            dedupKey = dedupKey,
            readAt = readAt,
            createdAt = createdAt,
            archivedAt = archivedAt,
            actorUserId = actorUserId,
        )

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

    // ── markSent 상태 전환 테스트 ─────────────────────────────────────────────────

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

    // ── Inbox 기능: toDomain 왕복 (archived_at / actor_user_id 보존) ───────────

    @Test
    fun `insertIfAbsent 후 findByRecipient에서 archivedAt과 actorUserId가 보존된다`() {
        val actorId = UUID.randomUUID()
        val archivedTime = Instant.parse("2026-06-20T10:00:00Z")
        val n =
            buildNotification(
                actorUserId = actorId,
                archivedAt = archivedTime,
            )
        repo.insertIfAbsent(n)

        val found = repo.findByRecipient(n.recipientUserId)

        assertThat(found).hasSize(1)
        assertThat(found[0].actorUserId).isEqualTo(actorId)
        assertThat(found[0].archivedAt).isEqualTo(archivedTime)
    }

    // ── Inbox 기능: findInbox 탭 필터 ────────────────────────────────────────────

    @Test
    fun `findInbox ALL탭은 archived_at IS NULL인 알림만 반환한다`() {
        val alice = UUID.randomUUID()
        val unread = buildNotification(recipientUserId = alice, title = "미읽음")
        val read =
            buildNotification(
                recipientUserId = alice,
                title = "읽음",
                readAt = Instant.parse("2026-06-12T10:00:00Z"),
            )
        val archived =
            buildNotification(
                recipientUserId = alice,
                title = "보관",
                archivedAt = Instant.parse("2026-06-12T11:00:00Z"),
            )
        repo.insertIfAbsent(unread)
        repo.insertIfAbsent(read)
        repo.insertIfAbsent(archived)

        val page = repo.findInbox(alice, InboxQuery(tab = InboxTab.ALL), PageRequest.of(0, 20))

        assertThat(page.totalElements).isEqualTo(2)
        assertThat(page.content.map { it.title }).containsExactlyInAnyOrder("미읽음", "읽음")
    }

    @Test
    fun `findInbox UNREAD탭은 read_at IS NULL AND archived_at IS NULL인 알림만 반환한다`() {
        val alice = UUID.randomUUID()
        val unread = buildNotification(recipientUserId = alice, title = "미읽음")
        val read =
            buildNotification(
                recipientUserId = alice,
                title = "읽음",
                readAt = Instant.parse("2026-06-12T10:00:00Z"),
            )
        val archivedUnread =
            buildNotification(
                recipientUserId = alice,
                title = "보관된_미읽음",
                archivedAt = Instant.parse("2026-06-12T11:00:00Z"),
            )
        repo.insertIfAbsent(unread)
        repo.insertIfAbsent(read)
        repo.insertIfAbsent(archivedUnread)

        val page = repo.findInbox(alice, InboxQuery(tab = InboxTab.UNREAD), PageRequest.of(0, 20))

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content[0].title).isEqualTo("미읽음")
    }

    @Test
    fun `findInbox ARCHIVED탭은 archived_at IS NOT NULL인 알림만 반환한다`() {
        val alice = UUID.randomUUID()
        val unread = buildNotification(recipientUserId = alice, title = "미읽음")
        val archived =
            buildNotification(
                recipientUserId = alice,
                title = "보관됨",
                archivedAt = Instant.parse("2026-06-12T11:00:00Z"),
            )
        repo.insertIfAbsent(unread)
        repo.insertIfAbsent(archived)

        val page = repo.findInbox(alice, InboxQuery(tab = InboxTab.ARCHIVED), PageRequest.of(0, 20))

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content[0].title).isEqualTo("보관됨")
    }

    // ── Inbox 기능: findInbox 검색 AND 결합 ──────────────────────────────────────

    @Test
    fun `findInbox q 파라미터는 title ILIKE 부분일치를 적용한다`() {
        val alice = UUID.randomUUID()
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, title = "ATLAS-12 이슈 생성"))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, title = "전혀 관계없는 알림"))

        val page = repo.findInbox(alice, InboxQuery(q = "atlas-12"), PageRequest.of(0, 20))

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content[0].title).isEqualTo("ATLAS-12 이슈 생성")
    }

    @Test
    fun `findInbox senderId는 actor_user_id 일치 필터를 적용한다`() {
        val alice = UUID.randomUUID()
        val actor1 = UUID.randomUUID()
        val actor2 = UUID.randomUUID()
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, actorUserId = actor1))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, actorUserId = actor2))

        val page = repo.findInbox(alice, InboxQuery(senderId = actor1), PageRequest.of(0, 20))

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content[0].actorUserId).isEqualTo(actor1)
    }

    @Test
    fun `findInbox issueKey 필터는 해당 issue_key 알림만 반환한다`() {
        val alice = UUID.randomUUID()
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, issueKey = "ATLAS-12"))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, issueKey = "ATLAS-99"))

        val page = repo.findInbox(alice, InboxQuery(issueKey = "ATLAS-12"), PageRequest.of(0, 20))

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content[0].issueKey).isEqualTo("ATLAS-12")
    }

    @Test
    fun `findInbox from과 to는 created_at 기간 필터를 AND 결합한다`() {
        val alice = UUID.randomUUID()
        val t1 = Instant.parse("2026-06-10T00:00:00Z")
        val t2 = Instant.parse("2026-06-15T00:00:00Z")
        val t3 = Instant.parse("2026-06-20T00:00:00Z")
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, createdAt = t1))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, createdAt = t2))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, createdAt = t3))

        val from = Instant.parse("2026-06-11T00:00:00Z")
        val to = Instant.parse("2026-06-18T00:00:00Z")
        val page =
            repo.findInbox(alice, InboxQuery(from = from, to = to), PageRequest.of(0, 20))

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content[0].createdAt).isEqualTo(t2)
    }

    @Test
    fun `findInbox 복합 AND 결합 — q와 issueKey를 동시 적용하면 교집합만 반환한다`() {
        val alice = UUID.randomUUID()
        repo.insertIfAbsent(
            buildNotification(recipientUserId = alice, title = "ATLAS-12 할당됨", issueKey = "ATLAS-12"),
        )
        repo.insertIfAbsent(
            buildNotification(recipientUserId = alice, title = "ATLAS-12 전환", issueKey = "ATLAS-99"),
        )
        repo.insertIfAbsent(
            buildNotification(recipientUserId = alice, title = "다른 제목", issueKey = "ATLAS-12"),
        )

        val page =
            repo.findInbox(
                alice,
                InboxQuery(q = "ATLAS-12", issueKey = "ATLAS-12"),
                PageRequest.of(0, 20),
            )

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content[0].title).isEqualTo("ATLAS-12 할당됨")
    }

    // ── Inbox 기능: findInbox 페이지네이션 ───────────────────────────────────────

    @Test
    fun `findInbox 25건 시드에서 size=20 첫 페이지는 content=20 totalElements=25이다`() {
        val alice = UUID.randomUUID()
        val baseTime = Instant.parse("2026-06-01T00:00:00Z")
        repeat(25) { i ->
            repo.insertIfAbsent(
                buildNotification(
                    recipientUserId = alice,
                    createdAt = baseTime.plusSeconds(i.toLong()),
                ),
            )
        }

        val page = repo.findInbox(alice, InboxQuery(tab = InboxTab.ALL), PageRequest.of(0, 20))

        assertThat(page.content).hasSize(20)
        assertThat(page.totalElements).isEqualTo(25)
    }

    @Test
    fun `findInbox 결과는 created_at 내림차순으로 정렬된다`() {
        val alice = UUID.randomUUID()
        val t1 = Instant.parse("2026-06-01T00:00:00Z")
        val t2 = Instant.parse("2026-06-02T00:00:00Z")
        val t3 = Instant.parse("2026-06-03T00:00:00Z")
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, createdAt = t2, title = "중간"))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, createdAt = t1, title = "오래됨"))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, createdAt = t3, title = "최신"))

        val page = repo.findInbox(alice, InboxQuery(), PageRequest.of(0, 20))

        assertThat(page.content.map { it.title }).containsExactly("최신", "중간", "오래됨")
    }

    // ── Inbox 기능: findInbox EC10 IN_APP 한정 ────────────────────────────────────

    @Test
    fun `findInbox는 IN_APP 채널만 반환하고 EMAIL과 WEBHOOK은 제외한다`() {
        val alice = UUID.randomUUID()
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, channel = Channel.IN_APP, title = "인앱"))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, channel = Channel.EMAIL, title = "이메일"))

        val page = repo.findInbox(alice, InboxQuery(), PageRequest.of(0, 20))

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content[0].title).isEqualTo("인앱")
    }

    // ── Inbox 기능: findInbox 타인 격리 ──────────────────────────────────────────

    @Test
    fun `findInbox는 본인 recipient_user_id에 해당하는 알림만 반환한다`() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, title = "앨리스 알림"))
        repo.insertIfAbsent(buildNotification(recipientUserId = bob, title = "밥 알림"))

        val page = repo.findInbox(alice, InboxQuery(), PageRequest.of(0, 20))

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content[0].title).isEqualTo("앨리스 알림")
    }

    // ── Inbox 기능: countUnread ───────────────────────────────────────────────────

    @Test
    fun `countUnread는 read_at IS NULL AND archived_at IS NULL AND IN_APP인 알림 수를 반환한다`() {
        val alice = UUID.randomUUID()
        // 미읽음 미보관 IN_APP — 카운트 대상
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, title = "미읽음1"))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, title = "미읽음2"))
        // 읽음 — 제외
        repo.insertIfAbsent(
            buildNotification(
                recipientUserId = alice,
                readAt = Instant.parse("2026-06-12T10:00:00Z"),
            ),
        )
        // 보관된 미읽음 — EC11: 제외
        repo.insertIfAbsent(
            buildNotification(
                recipientUserId = alice,
                archivedAt = Instant.parse("2026-06-12T11:00:00Z"),
            ),
        )
        // EMAIL 채널 — EC10: 제외
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, channel = Channel.EMAIL))

        val count = repo.countUnread(alice)

        assertThat(count).isEqualTo(2)
    }

    @Test
    fun `countUnread는 타인의 미읽음 알림을 포함하지 않는다`() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        repo.insertIfAbsent(buildNotification(recipientUserId = alice))
        repo.insertIfAbsent(buildNotification(recipientUserId = bob))

        assertThat(repo.countUnread(alice)).isEqualTo(1)
    }

    // ── Inbox 기능: updateReadAt ──────────────────────────────────────────────────

    @Test
    fun `updateReadAt은 본인 알림의 read_at만 갱신하고 영향 행수 1을 반환한다`() {
        val alice = UUID.randomUUID()
        val n = buildNotification(recipientUserId = alice)
        repo.insertIfAbsent(n)

        val readTime = Instant.parse("2026-06-25T10:00:00Z")
        val affected = repo.updateReadAt(n.id, alice, readTime)

        assertThat(affected).isEqualTo(1)
        val found = repo.findByRecipient(alice)
        assertThat(found[0].readAt).isEqualTo(readTime)
        // no-bump: title 등 다른 컬럼 불변 확인
        assertThat(found[0].title).isEqualTo(n.title)
        assertThat(found[0].archivedAt).isNull()
    }

    @Test
    fun `updateReadAt에 타인 id를 전달하면 영향 행수 0을 반환한다`() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val n = buildNotification(recipientUserId = bob)
        repo.insertIfAbsent(n)

        val affected = repo.updateReadAt(n.id, alice, Instant.now())

        assertThat(affected).isEqualTo(0)
    }

    @Test
    fun `updateReadAt에 null을 전달하면 read_at을 null로 초기화한다`() {
        val alice = UUID.randomUUID()
        val readTime = Instant.parse("2026-06-25T10:00:00Z")
        val n = buildNotification(recipientUserId = alice, readAt = readTime)
        repo.insertIfAbsent(n)

        val affected = repo.updateReadAt(n.id, alice, null)

        assertThat(affected).isEqualTo(1)
        assertThat(repo.findByRecipient(alice)[0].readAt).isNull()
    }

    // ── Inbox 기능: updateArchivedAt ─────────────────────────────────────────────

    @Test
    fun `updateArchivedAt은 본인 알림의 archived_at만 갱신하고 영향 행수 1을 반환한다`() {
        val alice = UUID.randomUUID()
        val n = buildNotification(recipientUserId = alice, readAt = Instant.parse("2026-06-12T08:00:00Z"))
        repo.insertIfAbsent(n)

        val archiveTime = Instant.parse("2026-06-25T11:00:00Z")
        val affected = repo.updateArchivedAt(n.id, alice, archiveTime)

        assertThat(affected).isEqualTo(1)
        val found = repo.findByRecipient(alice)
        assertThat(found[0].archivedAt).isEqualTo(archiveTime)
        // no-bump: read_at 불변 확인
        assertThat(found[0].readAt).isEqualTo(n.readAt)
    }

    @Test
    fun `updateArchivedAt에 타인 id를 전달하면 영향 행수 0을 반환한다`() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val n = buildNotification(recipientUserId = bob)
        repo.insertIfAbsent(n)

        val affected = repo.updateArchivedAt(n.id, alice, Instant.now())

        assertThat(affected).isEqualTo(0)
    }

    // ── Inbox 기능: markAllRead ───────────────────────────────────────────────────

    @Test
    fun `markAllRead ids=null이면 본인의 미읽음 전체를 읽음 처리하고 변경 건수를 반환한다`() {
        val alice = UUID.randomUUID()
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, title = "미읽음1"))
        repo.insertIfAbsent(buildNotification(recipientUserId = alice, title = "미읽음2"))
        repo.insertIfAbsent(
            buildNotification(
                recipientUserId = alice,
                title = "이미읽음",
                readAt = Instant.parse("2026-06-12T10:00:00Z"),
            ),
        )

        val readTime = Instant.parse("2026-06-25T12:00:00Z")
        val updated = repo.markAllRead(alice, null, readTime)

        assertThat(updated).isEqualTo(2)
        val found = repo.findByRecipient(alice)
        assertThat(found).allMatch { it.readAt != null }
    }

    @Test
    fun `markAllRead ids 지정이면 교집합(본인 소유 AND 지정 id)만 읽음 처리한다`() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val n1 = buildNotification(recipientUserId = alice, title = "앨리스1")
        val n2 = buildNotification(recipientUserId = alice, title = "앨리스2")
        val bobN = buildNotification(recipientUserId = bob, title = "밥")
        repo.insertIfAbsent(n1)
        repo.insertIfAbsent(n2)
        repo.insertIfAbsent(bobN)

        val readTime = Instant.parse("2026-06-25T12:00:00Z")
        // n1 + 밥의 알림 id — 밥 id는 무시되어야 한다
        val updated = repo.markAllRead(alice, listOf(n1.id, bobN.id), readTime)

        assertThat(updated).isEqualTo(1)
        val aliceNotifs = repo.findByRecipient(alice)
        assertThat(aliceNotifs.first { it.id == n1.id }.readAt).isEqualTo(readTime)
        assertThat(aliceNotifs.first { it.id == n2.id }.readAt).isNull()
    }

    @Test
    fun `markAllRead 미읽음이 0건이면 updated=0을 반환한다`() {
        val alice = UUID.randomUUID()
        repo.insertIfAbsent(
            buildNotification(
                recipientUserId = alice,
                readAt = Instant.parse("2026-06-12T10:00:00Z"),
            ),
        )

        val updated = repo.markAllRead(alice, null, Instant.now())

        assertThat(updated).isEqualTo(0)
    }

    @Test
    fun `markAllRead ids=빈 리스트이면 미읽음 전체를 읽음 처리한다`() {
        val alice = UUID.randomUUID()
        repo.insertIfAbsent(buildNotification(recipientUserId = alice))

        val updated = repo.markAllRead(alice, emptyList(), Instant.now())

        assertThat(updated).isEqualTo(1)
    }

    // ── Inbox 기능: 재읽음/재보관 시각 보존 (C1) ─────────────────────────────────

    /**
     * 이미 읽은 알림에 read=true(새 시각)를 다시 요청하면 최초 read_at이 보존돼야 한다.
     *
     * spec S3/FR4/EC2: 이미 읽음이면 첫 시각 보존(덮어쓰지 않음), 204.
     * COALESCE 구현 전이라면 새 시각으로 덮어써서 이 테스트가 실패해야 한다(RED).
     */
    @Test
    fun `updateReadAt - 이미 읽음인 항목에 새 시각으로 재호출하면 최초 read_at이 보존된다`() {
        val alice = UUID.randomUUID()
        val t1 = Instant.parse("2026-06-25T10:00:00Z")
        val t2 = Instant.parse("2026-06-25T11:00:00Z")

        // t1 시각에 미읽음 알림을 읽음 처리
        val n = buildNotification(recipientUserId = alice)
        repo.insertIfAbsent(n)
        val firstAffected = repo.updateReadAt(n.id, alice, t1)
        assertThat(firstAffected).isEqualTo(1)
        assertThat(repo.findByRecipient(alice)[0].readAt).isEqualTo(t1)

        // t2 시각으로 재호출 → read_at이 t1으로 보존돼야 한다(t2로 덮어쓰면 실패)
        val secondAffected = repo.updateReadAt(n.id, alice, t2)
        assertThat(secondAffected).isEqualTo(1) // 행 매칭은 1행 — 404 안 남
        val preserved = repo.findByRecipient(alice)[0].readAt
        assertThat(preserved).isEqualTo(t1) // 최초 시각 보존
        assertThat(preserved).isNotEqualTo(t2)
    }

    /**
     * 이미 보관된 알림에 archived=true(새 시각)를 다시 요청하면 최초 archived_at이 보존돼야 한다.
     *
     * spec EC2: 이미 보관이면 첫 시각 보존, 204.
     */
    @Test
    fun `updateArchivedAt - 이미 보관된 항목에 새 시각으로 재호출하면 최초 archived_at이 보존된다`() {
        val alice = UUID.randomUUID()
        val t1 = Instant.parse("2026-06-25T10:00:00Z")
        val t2 = Instant.parse("2026-06-25T12:00:00Z")

        // t1 시각에 미보관 알림을 보관 처리
        val n = buildNotification(recipientUserId = alice)
        repo.insertIfAbsent(n)
        val firstAffected = repo.updateArchivedAt(n.id, alice, t1)
        assertThat(firstAffected).isEqualTo(1)
        assertThat(repo.findByRecipient(alice)[0].archivedAt).isEqualTo(t1)

        // t2 시각으로 재호출 → archived_at이 t1으로 보존돼야 한다
        val secondAffected = repo.updateArchivedAt(n.id, alice, t2)
        assertThat(secondAffected).isEqualTo(1)
        val preserved = repo.findByRecipient(alice)[0].archivedAt
        assertThat(preserved).isEqualTo(t1)
        assertThat(preserved).isNotEqualTo(t2)
    }

    /**
     * markUnread(readAt=null)는 이미 null이든 값이 있든 무조건 null로 초기화해야 한다.
     * 기존 동작 회귀 방지.
     */
    @Test
    fun `updateReadAt null - 이미 읽음인 항목에 null 재호출하면 read_at이 null로 초기화된다`() {
        val alice = UUID.randomUUID()
        val t1 = Instant.parse("2026-06-25T10:00:00Z")

        val n = buildNotification(recipientUserId = alice, readAt = t1)
        repo.insertIfAbsent(n)

        val affected = repo.updateReadAt(n.id, alice, null)
        assertThat(affected).isEqualTo(1)
        assertThat(repo.findByRecipient(alice)[0].readAt).isNull()
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

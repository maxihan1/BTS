// InboxService 단위 테스트 — NotificationRepository 를 mockk 로 대체해 비즈니스 로직만 검증

package com.bts.notification.inbox.application

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import com.bts.notification.repository.InboxQuery
import com.bts.notification.repository.InboxTab
import com.bts.notification.repository.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@DisplayName("InboxService 단위 테스트")
class InboxServiceTest {
    private val repository: NotificationRepository = mockk()

    /** 테스트 재현성을 위한 고정 시각 — authcontroller-revokesession-timebomb 교훈 */
    private val fixedNow: Instant = Instant.parse("2026-06-25T10:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private lateinit var service: InboxService

    @BeforeEach
    fun setUp() {
        service = InboxService(repository, fixedClock)
    }

    // ── 테스트용 헬퍼 ────────────────────────────────────────────────────────────

    private fun stubNotification(
        recipientUserId: UUID = UUID.randomUUID(),
        readAt: Instant? = null,
        archivedAt: Instant? = null,
    ) = Notification(
        id = UUID.randomUUID(),
        recipientUserId = recipientUserId,
        eventType = NotificationEventType.ISSUE_ASSIGNED,
        channel = Channel.IN_APP,
        issueKey = "PROJ-1",
        title = "테스트 알림",
        body = null,
        payload = null,
        status = NotificationStatus.SENT,
        dedupKey = UUID.randomUUID().toString(),
        readAt = readAt,
        createdAt = Instant.parse("2026-06-25T09:00:00Z"),
        archivedAt = archivedAt,
        actorUserId = null,
    )

    // ── listInbox ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listInbox")
    inner class ListInbox {
        @Test
        @DisplayName("repository.findInbox 결과를 그대로 반환한다")
        fun `listInbox - delegates to repository and returns result`() {
            val actorId = UUID.randomUUID()
            val query = InboxQuery(tab = InboxTab.ALL)
            val pageable = PageRequest.of(0, 20)
            val notification = stubNotification(recipientUserId = actorId)
            val page = PageImpl(listOf(notification), pageable, 1L)

            every { repository.findInbox(actorId, query, pageable) } returns page

            val result = service.listInbox(actorId, query, pageable)

            assertThat(result.totalElements).isEqualTo(1L)
            assertThat(result.content).containsExactly(notification)
            verify(exactly = 1) { repository.findInbox(actorId, query, pageable) }
        }

        @Test
        @DisplayName("빈 결과도 그대로 반환한다")
        fun `listInbox - empty result - returns empty page`() {
            val actorId = UUID.randomUUID()
            val query = InboxQuery(tab = InboxTab.UNREAD)
            val pageable = PageRequest.of(0, 20)
            val page = PageImpl(emptyList<Notification>(), pageable, 0L)

            every { repository.findInbox(actorId, query, pageable) } returns page

            val result = service.listInbox(actorId, query, pageable)

            assertThat(result.totalElements).isEqualTo(0L)
            assertThat(result.content).isEmpty()
        }
    }

    // ── unreadCount ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unreadCount")
    inner class UnreadCount {
        @Test
        @DisplayName("repository.countUnread 결과를 그대로 반환한다")
        fun `unreadCount - delegates to repository and returns count`() {
            val actorId = UUID.randomUUID()

            every { repository.countUnread(actorId) } returns 5L

            val result = service.unreadCount(actorId)

            assertThat(result).isEqualTo(5L)
            verify(exactly = 1) { repository.countUnread(actorId) }
        }

        @Test
        @DisplayName("미읽음 0건이면 0을 반환한다")
        fun `unreadCount - zero - returns zero`() {
            val actorId = UUID.randomUUID()

            every { repository.countUnread(actorId) } returns 0L

            assertThat(service.unreadCount(actorId)).isEqualTo(0L)
        }
    }

    // ── markRead ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markRead")
    inner class MarkRead {
        @Test
        @DisplayName("read=true 이면 고정 Clock 의 Instant 를 readAt 으로 전달하고 정상 반환한다")
        fun `markRead - read=true - calls updateReadAt with fixedNow`() {
            val actorId = UUID.randomUUID()
            val id = UUID.randomUUID()

            every { repository.updateReadAt(id, actorId, fixedNow) } returns 1

            service.markRead(actorId, id, read = true)

            verify(exactly = 1) { repository.updateReadAt(id, actorId, fixedNow) }
        }

        @Test
        @DisplayName("read=false 이면 readAt 에 null 을 전달한다")
        fun `markRead - read=false - calls updateReadAt with null`() {
            val actorId = UUID.randomUUID()
            val id = UUID.randomUUID()

            every { repository.updateReadAt(id, actorId, null) } returns 1

            service.markRead(actorId, id, read = false)

            verify(exactly = 1) { repository.updateReadAt(id, actorId, null) }
        }

        @Test
        @DisplayName("updateReadAt 이 0 을 반환하면 InboxItemNotFoundException 을 던진다")
        fun `markRead - affected 0 - throws InboxItemNotFoundException`() {
            val actorId = UUID.randomUUID()
            val id = UUID.randomUUID()

            every { repository.updateReadAt(id, actorId, fixedNow) } returns 0

            assertThatThrownBy { service.markRead(actorId, id, read = true) }
                .isInstanceOf(InboxItemNotFoundException::class.java)
        }

        @Test
        @DisplayName("예외 메시지에 리소스 id 가 노출되지 않는다")
        fun `markRead - not found - exception message does not expose id`() {
            val actorId = UUID.randomUUID()
            val id = UUID.randomUUID()

            every { repository.updateReadAt(id, actorId, fixedNow) } returns 0

            assertThatThrownBy { service.markRead(actorId, id, read = true) }
                .isInstanceOf(InboxItemNotFoundException::class.java)
                .hasMessageNotContaining(id.toString())
        }
    }

    // ── markArchive ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("markArchive")
    inner class MarkArchive {
        @Test
        @DisplayName("archived=true 이면 고정 Clock 의 Instant 를 archivedAt 으로 전달하고 정상 반환한다")
        fun `markArchive - archived=true - calls updateArchivedAt with fixedNow`() {
            val actorId = UUID.randomUUID()
            val id = UUID.randomUUID()

            every { repository.updateArchivedAt(id, actorId, fixedNow) } returns 1

            service.markArchive(actorId, id, archived = true)

            verify(exactly = 1) { repository.updateArchivedAt(id, actorId, fixedNow) }
        }

        @Test
        @DisplayName("archived=false 이면 archivedAt 에 null 을 전달한다")
        fun `markArchive - archived=false - calls updateArchivedAt with null`() {
            val actorId = UUID.randomUUID()
            val id = UUID.randomUUID()

            every { repository.updateArchivedAt(id, actorId, null) } returns 1

            service.markArchive(actorId, id, archived = false)

            verify(exactly = 1) { repository.updateArchivedAt(id, actorId, null) }
        }

        @Test
        @DisplayName("updateArchivedAt 이 0 을 반환하면 InboxItemNotFoundException 을 던진다")
        fun `markArchive - affected 0 - throws InboxItemNotFoundException`() {
            val actorId = UUID.randomUUID()
            val id = UUID.randomUUID()

            every { repository.updateArchivedAt(id, actorId, fixedNow) } returns 0

            assertThatThrownBy { service.markArchive(actorId, id, archived = true) }
                .isInstanceOf(InboxItemNotFoundException::class.java)
        }
    }

    // ── readAll ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("readAll")
    inner class ReadAll {
        @Test
        @DisplayName("ids=null 이면 markAllRead(actorId, null, fixedNow) 를 호출하고 변경 건수를 반환한다")
        fun `readAll - ids=null - calls markAllRead with null ids and returns count`() {
            val actorId = UUID.randomUUID()

            every { repository.markAllRead(actorId, null, fixedNow) } returns 3

            val result = service.readAll(actorId, ids = null)

            assertThat(result).isEqualTo(3)
            verify(exactly = 1) { repository.markAllRead(actorId, null, fixedNow) }
        }

        @Test
        @DisplayName("ids 를 지정하면 해당 ids 를 그대로 전달한다")
        fun `readAll - with ids - passes ids to markAllRead`() {
            val actorId = UUID.randomUUID()
            val ids = listOf(UUID.randomUUID(), UUID.randomUUID())

            every { repository.markAllRead(actorId, ids, fixedNow) } returns 2

            val result = service.readAll(actorId, ids = ids)

            assertThat(result).isEqualTo(2)
            verify(exactly = 1) { repository.markAllRead(actorId, ids, fixedNow) }
        }

        @Test
        @DisplayName("변경 건수가 0 이어도 InboxItemNotFoundException 을 던지지 않는다")
        fun `readAll - zero affected - does not throw`() {
            val actorId = UUID.randomUUID()

            every { repository.markAllRead(actorId, null, fixedNow) } returns 0

            val result = service.readAll(actorId, ids = null)

            assertThat(result).isEqualTo(0)
        }
    }
}

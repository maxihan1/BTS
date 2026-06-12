// Notification 도메인 단위 테스트 — 불변 필드, NotificationStatus, dedupKey 결정성 검증

package com.bts.notification.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID

class NotificationTest : DescribeSpec({

    val recipientId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val otherId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val fixedNow: Instant = Instant.parse("2026-06-12T00:00:00Z")
    val laterNow: Instant = Instant.parse("2026-06-12T01:00:00Z")

    fun buildNotification(
        id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010"),
        recipientUserId: UUID = recipientId,
        eventType: NotificationEventType = NotificationEventType.ISSUE_ASSIGNED,
        channel: Channel = Channel.IN_APP,
        issueKey: String? = "ATLAS-42",
        title: String = "이슈가 할당되었습니다",
        body: String? = "담당자로 지정되었습니다.",
        payload: String? = """{"issueId":"abc"}""",
        status: NotificationStatus = NotificationStatus.PENDING,
        dedupKey: String = "fixedkey",
        readAt: Instant? = null,
        createdAt: Instant = fixedNow,
    ): Notification =
        Notification(
            id = id,
            recipientUserId = recipientUserId,
            eventType = eventType,
            channel = channel,
            issueKey = issueKey,
            title = title,
            body = body,
            payload = payload,
            status = status,
            dedupKey = dedupKey,
            readAt = readAt,
            createdAt = createdAt,
        )

    // -----------------------------------------------------------------------
    // NotificationStatus
    // -----------------------------------------------------------------------
    describe("NotificationStatus — 3종 존재") {
        it("PENDING, SENT, FAILED 상수가 모두 존재한다") {
            val names = NotificationStatus.entries.map { it.name }.toSet()
            names shouldBe setOf("PENDING", "SENT", "FAILED")
        }

        it("enum 상수가 정확히 3종이어야 한다") {
            NotificationStatus.entries.size shouldBe 3
        }
    }

    // -----------------------------------------------------------------------
    // Notification 생성
    // -----------------------------------------------------------------------
    describe("Notification 생성 — 모든 필드 초기화 검증") {
        it("주어진 값으로 모든 필드가 초기화된다") {
            val n = buildNotification()
            n.recipientUserId shouldBe recipientId
            n.eventType shouldBe NotificationEventType.ISSUE_ASSIGNED
            n.channel shouldBe Channel.IN_APP
            n.issueKey shouldBe "ATLAS-42"
            n.title shouldBe "이슈가 할당되었습니다"
            n.body shouldBe "담당자로 지정되었습니다."
            n.status shouldBe NotificationStatus.PENDING
            n.readAt shouldBe null
            n.createdAt shouldBe fixedNow
        }

        it("issueKey 가 null 일 수 있다") {
            val n = buildNotification(issueKey = null)
            n.issueKey shouldBe null
        }

        it("body 가 null 일 수 있다") {
            val n = buildNotification(body = null)
            n.body shouldBe null
        }

        it("payload 가 null 일 수 있다") {
            val n = buildNotification(payload = null)
            n.payload shouldBe null
        }

        it("readAt 이 non-null 일 때 값이 보존된다") {
            val n = buildNotification(readAt = fixedNow)
            n.readAt shouldBe fixedNow
        }
    }

    // -----------------------------------------------------------------------
    // dedupKey 결정성
    // -----------------------------------------------------------------------
    describe("dedupKey 결정성 — 동일 입력 동일 키, 다른 입력 다른 키") {

        context("computeDedupKey — 동일 입력이면 항상 동일한 키를 반환한다") {
            it("같은 eventType/issueKey/occurredAt/recipientUserId/channel → 같은 dedupKey") {
                val key1 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                val key2 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                key1 shouldBe key2
            }

            it("issueKey 가 null 이어도 결정적으로 계산된다") {
                val key1 = Notification.computeDedupKey(
                    eventType = NotificationEventType.SPRINT_STARTED,
                    issueKey = null,
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                val key2 = Notification.computeDedupKey(
                    eventType = NotificationEventType.SPRINT_STARTED,
                    issueKey = null,
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                key1 shouldBe key2
            }
        }

        context("computeDedupKey — 입력이 다르면 키가 달라야 한다") {
            it("eventType 이 다르면 키가 다르다") {
                val key1 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                val key2 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_CREATED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                key1 shouldNotBe key2
            }

            it("issueKey 가 다르면 키가 다르다") {
                val key1 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                val key2 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-99",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                key1 shouldNotBe key2
            }

            it("occurredAt 이 다르면 키가 다르다") {
                val key1 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                val key2 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = laterNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                key1 shouldNotBe key2
            }

            it("recipientUserId 가 다르면 키가 다르다") {
                val key1 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                val key2 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = otherId,
                    channel = Channel.IN_APP,
                )
                key1 shouldNotBe key2
            }

            it("channel 이 다르면 키가 다르다") {
                val key1 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                val key2 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.EMAIL,
                )
                key1 shouldNotBe key2
            }

            it("issueKey null 과 non-null 은 키가 다르다") {
                val key1 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = null,
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                val key2 = Notification.computeDedupKey(
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    issueKey = "ATLAS-42",
                    occurredAt = fixedNow,
                    recipientUserId = recipientId,
                    channel = Channel.IN_APP,
                )
                key1 shouldNotBe key2
            }
        }

        it("computeDedupKey 결과는 SHA-256 hex — 64자 소문자 hex 문자열이어야 한다") {
            val key = Notification.computeDedupKey(
                eventType = NotificationEventType.ISSUE_ASSIGNED,
                issueKey = "ATLAS-1",
                occurredAt = fixedNow,
                recipientUserId = recipientId,
                channel = Channel.IN_APP,
            )
            key.length shouldBe 64
            key shouldBe key.lowercase()
        }
    }
})

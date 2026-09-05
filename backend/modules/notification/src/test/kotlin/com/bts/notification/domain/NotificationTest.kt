// Notification 도메인 단위 테스트 — 불변 필드, NotificationStatus, dedupKey 결정성, 상태 전환(read/archive) 검증

package com.bts.notification.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID

class NotificationTest : DescribeSpec({

    val recipientId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val otherId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val commentIdA: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
    val commentIdB: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
    val fixedNow: Instant = Instant.parse("2026-06-12T00:00:00Z")
    val laterNow: Instant = Instant.parse("2026-06-12T01:00:00Z")

    @Suppress("LongParameterList")
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
    // 상태 전환 — markRead / markUnread / archive / unarchive
    // -----------------------------------------------------------------------
    describe("상태 전환 — 기본 필드 기본값") {
        it("기본 생성 시 archivedAt 은 null 이다") {
            val n = buildNotification()
            n.archivedAt shouldBe null
        }

        it("기본 생성 시 actorUserId 는 null 이다") {
            val n = buildNotification()
            n.actorUserId shouldBe null
        }
    }

    describe("markRead — 읽음 처리") {
        it("미읽음 알림에 markRead(t) 를 호출하면 readAt 이 t 로 설정된다") {
            val n = buildNotification(readAt = null)
            val result = n.markRead(fixedNow)
            result.readAt shouldBe fixedNow
        }

        it("이미 읽은 알림에 markRead(t2) 를 호출해도 readAt 이 원래 시각(t)으로 유지된다(멱등)") {
            val n = buildNotification(readAt = fixedNow)
            val result = n.markRead(laterNow)
            result.readAt shouldBe fixedNow
        }

        it("markRead 는 archivedAt 을 변경하지 않는다") {
            val n = buildNotification(readAt = null)
            val withArchive = n.archive(fixedNow)
            val result = withArchive.markRead(laterNow)
            result.archivedAt shouldBe fixedNow
        }
    }

    describe("markUnread — 미읽음 처리") {
        it("읽은 알림에 markUnread() 를 호출하면 readAt 이 null 이 된다") {
            val n = buildNotification(readAt = fixedNow)
            val result = n.markUnread()
            result.readAt shouldBe null
        }

        it("markUnread 는 archivedAt 을 변경하지 않는다") {
            val n = buildNotification(readAt = fixedNow)
            val withArchive = n.archive(fixedNow)
            val result = withArchive.markUnread()
            result.archivedAt shouldBe fixedNow
        }
    }

    describe("archive — 보관 처리") {
        it("미보관 알림에 archive(t) 를 호출하면 archivedAt 이 t 로 설정된다") {
            val n = buildNotification()
            val result = n.archive(fixedNow)
            result.archivedAt shouldBe fixedNow
        }

        it("이미 보관된 알림에 archive(t2) 를 호출해도 archivedAt 이 원래 시각(t)으로 유지된다(멱등)") {
            val n = buildNotification().archive(fixedNow)
            val result = n.archive(laterNow)
            result.archivedAt shouldBe fixedNow
        }

        it("archive 는 readAt 을 변경하지 않는다") {
            val n = buildNotification(readAt = fixedNow)
            val result = n.archive(laterNow)
            result.readAt shouldBe fixedNow
        }
    }

    describe("unarchive — 보관 해제") {
        it("보관된 알림에 unarchive() 를 호출하면 archivedAt 이 null 이 된다") {
            val n = buildNotification().archive(fixedNow)
            val result = n.unarchive()
            result.archivedAt shouldBe null
        }

        it("unarchive 는 readAt 을 변경하지 않는다") {
            val n = buildNotification(readAt = fixedNow).archive(laterNow)
            val result = n.unarchive()
            result.readAt shouldBe fixedNow
        }
    }

    describe("read / archive 2축 독립") {
        it("archive 후에도 readAt 이 불변이다") {
            val n = buildNotification(readAt = fixedNow)
            val result = n.archive(laterNow)
            result.readAt shouldBe fixedNow
            result.archivedAt shouldBe laterNow
        }

        it("markRead 후에도 archivedAt 이 불변이다") {
            val n = buildNotification().archive(fixedNow)
            val result = n.markRead(laterNow)
            result.archivedAt shouldBe fixedNow
            result.readAt shouldBe laterNow
        }
    }

    // -----------------------------------------------------------------------
    // dedupKey 결정성
    // -----------------------------------------------------------------------
    describe("dedupKey 결정성 — 동일 입력 동일 키, 다른 입력 다른 키") {

        context("computeDedupKey — 동일 입력이면 항상 동일한 키를 반환한다") {
            it("같은 eventType/issueKey/occurredAt/recipientUserId/channel → 같은 dedupKey") {
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                val key2 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                key1 shouldBe key2
            }

            it("issueKey 가 null 이어도 결정적으로 계산된다") {
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.SPRINT_STARTED,
                        issueKey = null,
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                val key2 =
                    Notification.computeDedupKey(
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
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                val key2 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_CREATED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                key1 shouldNotBe key2
            }

            it("issueKey 가 다르면 키가 다르다") {
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                val key2 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-99",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                key1 shouldNotBe key2
            }

            it("occurredAt 이 다르면 키가 다르다") {
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                val key2 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = laterNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                key1 shouldNotBe key2
            }

            it("recipientUserId 가 다르면 키가 다르다") {
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                val key2 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = otherId,
                        channel = Channel.IN_APP,
                    )
                key1 shouldNotBe key2
            }

            it("channel 이 다르면 키가 다르다") {
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                val key2 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.EMAIL,
                    )
                key1 shouldNotBe key2
            }

            it("issueKey null 과 non-null 은 키가 다르다") {
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = null,
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                val key2 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                key1 shouldNotBe key2
            }
        }

        context("computeDedupKey — 댓글은 어느 댓글인지까지 키에 들어간다") {
            it("occurredAt 까지 전부 같아도 commentId 가 다르면 키가 다르다") {
                // import 로 들어온 댓글은 원본 시스템의 초 단위 시각을 그대로 쓴다. 같은 이슈에
                // 같은 초에 달린 댓글 2건이면 나머지 원소가 전부 같아지고, 키가 같으면 두 번째
                // 알림이 UNIQUE(dedup_key) 에 걸려 「멱등이 동작했다」는 얼굴로 사라진다.
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_COMMENTED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                        commentId = commentIdA,
                    )
                val key2 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_COMMENTED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                        commentId = commentIdB,
                    )
                key1 shouldNotBe key2
            }

            it("같은 commentId 면 여전히 같은 키다 — 재전달 멱등은 그대로다") {
                val key1 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_COMMENTED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                        commentId = commentIdA,
                    )
                val key2 =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_COMMENTED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                        commentId = commentIdA,
                    )
                key1 shouldBe key2
            }

            it("commentId 가 없는 알림의 키는 옛 5원소 키와 바이트 단위로 같다") {
                // ★이 golden 값이 이 변경의 폭발 반경을 고정한다. commentId 를 항상 붙이면
                // (`?: ""`) 구분자가 하나 더 생겨 담당자 지정·상태 전환·스프린트 등 **댓글과
                // 무관한 모든 알림의 키까지** 바뀌고, 배포 경계에서 재전달되는 모든 이벤트가
                // 중복 알림이 된다. 값은 SHA-256("issue.assigned|ATLAS-42|2026-06-12T00:00:00Z|
                // 00000000-0000-0000-0000-000000000001|IN_APP") — 변경 이전 코드의 산출물이다.
                val key =
                    Notification.computeDedupKey(
                        eventType = NotificationEventType.ISSUE_ASSIGNED,
                        issueKey = "ATLAS-42",
                        occurredAt = fixedNow,
                        recipientUserId = recipientId,
                        channel = Channel.IN_APP,
                    )
                key shouldBe "4f83935a6237744e294bbb107acf849827b10805d7e6451c262b7480344e0799"
            }
        }

        it("computeDedupKey 결과는 SHA-256 hex — 64자 소문자 hex 문자열이어야 한다") {
            val key =
                Notification.computeDedupKey(
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

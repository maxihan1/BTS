// Inbox 응답 DTO 의 payload→commentId 추출 규약 테스트 (인박스 댓글 딥링크)

package com.bts.notification.inbox.web.dto

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import io.kotest.core.spec.style.DescribeSpec
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.util.UUID

/**
 * [InboxItemResponse.from] 의 딥링크 필드 규약.
 *
 * 댓글 딥링크는 `notifications.payload` JSONB 에 실린 `commentId` 하나로 성립한다.
 * payload 는 워커가 넣는 자유 형식 JSON 이므로 **깨진 값에 대해 절대 던지지 않는다** —
 * 알림 목록 전체가 500 이 되는 것보다 딥링크 하나가 없는 편이 낫다.
 */
class InboxItemResponseTest : DescribeSpec({

    val commentId: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

    fun notification(payload: String?) =
        Notification(
            id = UUID.randomUUID(),
            recipientUserId = UUID.randomUUID(),
            eventType = NotificationEventType.ISSUE_MENTIONED,
            channel = Channel.IN_APP,
            issueKey = "ATLAS-1",
            title = "테스트 알림",
            body = null,
            payload = payload,
            status = NotificationStatus.SENT,
            dedupKey = UUID.randomUUID().toString(),
            readAt = null,
            createdAt = Instant.parse("2026-06-25T09:00:00Z"),
            archivedAt = null,
            actorUserId = null,
        )

    describe("payload 의 commentId 노출") {
        it("payload 에 commentId 가 있으면 응답 필드로 노출한다") {
            val response = InboxItemResponse.from(notification("""{"commentId":"$commentId"}"""))

            assertThat(response.commentId).isEqualTo(commentId)
        }

        it("payload 에 다른 키가 섞여 있어도 commentId 를 찾는다") {
            val payload = """{"issueKey":"ATLAS-1","commentId":"$commentId"}"""

            assertThat(InboxItemResponse.from(notification(payload)).commentId).isEqualTo(commentId)
        }
    }

    describe("딥링크가 없는 알림") {
        it("payload 가 null 이면 commentId 는 null 이다") {
            assertThat(InboxItemResponse.from(notification(null)).commentId).isNull()
        }

        it("payload 에 commentId 키가 없으면 null 이다") {
            assertThat(InboxItemResponse.from(notification("""{"issueKey":"ATLAS-1"}""")).commentId).isNull()
        }
    }

    describe("깨진 payload 는 던지지 않는다") {
        it("JSON 이 아니면 null 을 낸다 — 목록 전체를 500 으로 만들지 않는다") {
            assertThat(InboxItemResponse.from(notification("not json at all")).commentId).isNull()
        }

        it("commentId 가 UUID 가 아니면 null 을 낸다") {
            assertThat(InboxItemResponse.from(notification("""{"commentId":"삼겹살"}""")).commentId).isNull()
        }

        it("commentId 가 문자열이 아닌 타입이어도 null 을 낸다") {
            assertThat(InboxItemResponse.from(notification("""{"commentId":42}""")).commentId).isNull()
        }
    }
})

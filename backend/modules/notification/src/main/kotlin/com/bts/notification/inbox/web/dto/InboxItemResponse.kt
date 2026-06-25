// Inbox 알림 단건 응답 DTO — Notification 도메인 객체를 HTTP 응답 포맷으로 변환

package com.bts.notification.inbox.web.dto

import com.bts.notification.domain.Notification
import java.time.Instant
import java.util.UUID

/**
 * Inbox 목록/단건 조회 응답 DTO.
 *
 * [Notification] 도메인 객체를 REST API 응답 포맷으로 변환한다.
 * 도메인 내부 구조를 HTTP 레이어에 직접 노출하지 않는다.
 *
 * @param id          알림 고유 식별자
 * @param eventType   이벤트 유형 wire 값 (예: "issue.created")
 * @param issueKey    연관 이슈 키 (없으면 null)
 * @param title       알림 제목
 * @param body        알림 본문 (없으면 null)
 * @param actorUserId 알림을 발생시킨 행위자 UUID (없으면 null)
 * @param readAt      읽은 시각 (미읽음이면 null)
 * @param archivedAt  보관한 시각 (미보관이면 null)
 * @param createdAt   알림 생성 시각
 */
data class InboxItemResponse(
    val id: UUID,
    val eventType: String,
    val issueKey: String?,
    val title: String,
    val body: String?,
    val actorUserId: UUID?,
    val readAt: Instant?,
    val archivedAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        /**
         * [Notification] 도메인 객체로부터 [InboxItemResponse] 를 생성한다.
         *
         * @param notification 변환 대상 도메인 객체
         * @return 변환된 응답 DTO
         */
        fun from(notification: Notification): InboxItemResponse =
            InboxItemResponse(
                id = notification.id,
                eventType = notification.eventType.wireValue,
                issueKey = notification.issueKey,
                title = notification.title,
                body = notification.body,
                actorUserId = notification.actorUserId,
                readAt = notification.readAt,
                archivedAt = notification.archivedAt,
                createdAt = notification.createdAt,
            )
    }
}

// Inbox 알림 단건 응답 DTO — Notification 도메인 객체를 HTTP 응답 포맷으로 변환

package com.bts.notification.inbox.web.dto

import com.bts.notification.domain.Notification
import com.fasterxml.jackson.databind.ObjectMapper
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
 * @param commentId   딥링크 대상 댓글 UUID (댓글 알림이 아니면 null)
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
    val commentId: UUID?,
    val readAt: Instant?,
    val archivedAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        /** payload 파싱 전용 mapper — 설정이 필요 없는 순수 읽기라 이 자리에 상수로 둔다. */
        private val MAPPER = ObjectMapper()

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
                commentId = parseCommentId(notification.payload),
                readAt = notification.readAt,
                archivedAt = notification.archivedAt,
                createdAt = notification.createdAt,
            )

        /**
         * `notifications.payload` JSONB 에서 딥링크 대상 댓글 UUID 를 꺼낸다.
         *
         * payload 는 워커가 쓰는 자유 형식 JSON 이고 과거 행은 아예 NULL 이다(V402 이후 계속
         * `payload = null` 로만 기록돼 왔다). 그래서 **어떤 입력에도 던지지 않는다** — 깨진 payload
         * 한 건이 알림 목록 전체를 500 으로 만드는 것보다 그 항목의 딥링크가 없는 편이 낫다.
         *
         * @param payload 원본 payload JSON 문자열 (null 허용)
         * @return 파싱된 댓글 UUID. 없거나 형식이 깨졌으면 null
         */
        private fun parseCommentId(payload: String?): UUID? {
            if (payload.isNullOrBlank()) return null
            return runCatching {
                MAPPER.readTree(payload)
                    .path("commentId")
                    .takeIf { it.isTextual }
                    ?.let { UUID.fromString(it.asText()) }
            }.getOrNull()
        }
    }
}

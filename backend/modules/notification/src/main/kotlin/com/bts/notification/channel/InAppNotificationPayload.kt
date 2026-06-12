// STOMP WebSocket 으로 클라이언트에 전송하는 인앱 알림 payload DTO

package com.bts.notification.channel

import java.time.Instant
import java.util.UUID

/**
 * 인앱 알림 실시간 푸시 시 STOMP 메시지로 직렬화되는 payload.
 *
 * 클라이언트는 `/user/queue/notifications` 구독으로 이 객체를 JSON 으로 수신한다.
 * Inbox 상세 필드(readAt 등)는 FR-UX-03 에서 확장 예정이다.
 *
 * @param id 알림 고유 식별자 — 클라이언트 중복 dedup 에 사용 가능
 * @param eventType 이벤트 유형 wireValue 문자열 (예: "issue.assigned")
 * @param issueKey 연관 이슈 키 (이슈와 무관한 이벤트면 null)
 * @param title 알림 제목
 * @param body 알림 본문 (null 허용)
 * @param occurredAt 알림 생성 시각
 */
data class InAppNotificationPayload(
    val id: UUID,
    val eventType: String,
    val issueKey: String?,
    val title: String,
    val body: String?,
    val occurredAt: Instant,
)

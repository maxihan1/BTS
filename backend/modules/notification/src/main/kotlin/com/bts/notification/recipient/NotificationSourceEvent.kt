// pgmq JSON을 notification BC 내부로 역직렬화하는 이벤트 표현 (BC 격리 — IssueDomainEvent 미직접import)

package com.bts.notification.recipient

import com.bts.notification.domain.NotificationEventType
import java.time.Instant
import java.util.UUID

/**
 * notification BC 가 pgmq 메시지를 역직렬화할 때 사용하는 내부 이벤트 표현.
 *
 * issue-tracking BC 의 IssueDomainEvent 를 직접 import 하지 않고,
 * T10 worker 가 pgmq JSON → 이 타입으로 역직렬화해 [EventRecipientResolver] 에 전달한다.
 *
 * ## 필드 채움 규칙
 * - 멘션 이벤트: [mentionedUserIds] 에 @멘션된 사용자 ID 목록을 채운다.
 * - 생성 이벤트: [reporterId] 에 이슈 리포터 ID 를 채운다.
 * - 담당자/상태 이벤트: [issueKey] 로 포트를 통해 담당자를 조회한다.
 * - 명시적으로 알 수 없는 필드는 기본값(null/emptyList)으로 둔다.
 *
 * @param eventType 이벤트 유형
 * @param issueKey "PROJ-1" 형태의 이슈 키. 이슈 수신자 포트 조회에 사용한다.
 * @param projectKey 프로젝트 키. 정책 평가에 사용한다.
 * @param mentionedUserIds @멘션된 사용자 UUID 목록. 멘션 이벤트가 아니면 빈 목록.
 * @param reporterId 이슈 리포터 UUID. 미리 알 수 없으면 null — 포트로 보완한다.
 * @param actorId 이벤트를 유발한 사용자 UUID. 본인 알림 제외에 사용한다.
 * @param occurredAt 이벤트 발생 시각.
 */
data class NotificationSourceEvent(
    val eventType: NotificationEventType,
    val issueKey: String?,
    val projectKey: String?,
    val mentionedUserIds: List<UUID> = emptyList(),
    val reporterId: UUID?,
    val actorId: UUID?,
    val occurredAt: Instant,
)

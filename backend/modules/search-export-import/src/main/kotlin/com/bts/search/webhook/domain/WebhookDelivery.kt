// 웹훅 발송 이력 레코드 — webhook_deliveries 테이블에 매핑되는 append-only 감사 로그 도메인 모델 (FR-API-03 PR3)
package com.bts.search.webhook.domain

import java.time.Instant
import java.util.UUID

/**
 * 웹훅 발송 시도 1건의 이력을 나타내는 append-only 레코드.
 *
 * [OutboundWebhook] 구독 1건에 대해 이벤트가 발생할 때마다 발송을 시도하며, 성공/실패 여부와
 * 무관하게 시도마다 1건씩 기록된다. 수정/삭제 메서드가 없다 — 발송 시도는 사실 기록이므로
 * 새 레코드만 추가된다(`webhook_deliveries` 테이블, PR2 V603 — audit_logs 동류).
 *
 * @property id 이 발송 시도 레코드의 식별자. 영속 계층이 채운다.
 * @property webhookId 이 발송이 속한 [OutboundWebhook] 구독의 식별자.
 * @property eventType 발송을 트리거한 이벤트 wireValue(예: `issue.created`).
 * @property status 이 시도의 결과. [DeliveryStatus] 참조.
 * @property responseCode 수신자 서버가 응답한 HTTP 상태 코드. 발송 자체가 불가능했으면 null.
 * @property attemptCount 이 이벤트에 대한 몇 번째 시도인지(1부터 시작).
 * @property errorDetail 실패 원인 요약. 성공 시 null.
 * @property createdAt 이 레코드가 기록된 시각(=시도 시각). 영속 계층이 채운다.
 * @property deliveredAt 실제로 발송에 성공한 시각. [status]가 [DeliveryStatus.FAILED]면 null.
 */
data class WebhookDelivery(
    val id: UUID?,
    val webhookId: UUID,
    val eventType: String,
    val status: DeliveryStatus,
    val responseCode: Int?,
    val attemptCount: Int,
    val errorDetail: String?,
    val createdAt: Instant?,
    val deliveredAt: Instant?,
)

/**
 * 웹훅 발송 시도의 결과 상태.
 */
enum class DeliveryStatus {
    /** 수신자 서버가 2xx 응답을 반환해 발송에 성공함. */
    SUCCEEDED,

    /** 발송 실패(비 2xx 응답, 예외, SSRF 차단 등). */
    FAILED,
}

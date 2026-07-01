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
 * ## id는 외부 발송 멱등키가 아니다
 * [id]는 이 레코드(per-attempt 내부 감사 PK) 1건만을 식별한다. 외부로 노출하는 발송 멱등키
 * (`X-BTS-Delivery` 헤더, pgmq `msg_id` 기반 결정적 안정 키)와는 별개다. pgmq VT(Visibility
 * Timeout) 만료로 같은 이벤트가 재전달되면 새 [id]를 가진 새 레코드가 추가되지만, 외부
 * 멱등키는 동일하게 유지되어 수신자가 중복을 걸러낼 수 있다(발송 계층의 책임 — 이 클래스는
 * 이력 기록만 담당한다).
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
 *
 * 발송은 워커가 동기적으로 처리한 직후 즉시 이력을 기록하므로 두 종단 상태만 존재한다 —
 * 비동기 대기를 나타내는 PENDING은 쓰지 않는다. `webhook_deliveries` 테이블(PR2 V603)의
 * `status` 컬럼 주석이 `PENDING/SUCCEEDED/FAILED`로 표기돼 있으나, 이는 초기 계획 문구이며
 * 실제로는 이 두 값만 저장된다.
 */
enum class DeliveryStatus {
    /** 수신자 서버가 2xx 응답을 반환해 발송에 성공함. */
    SUCCEEDED,

    /** 발송 실패(비 2xx 응답, 예외, SSRF 차단 등). */
    FAILED,
}

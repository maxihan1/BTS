// 웹훅 발송 이력 영속성 포트 인터페이스 — application 계층 DIP 경계 (FR-API-03 PR3)

package com.bts.search.webhook.application

import com.bts.search.webhook.domain.DeliveryStatus
import com.bts.search.webhook.domain.WebhookDelivery
import java.util.UUID

/**
 * 웹훅 발송 이력(append-only) 영속성 포트 인터페이스 (Port Interface — 의존성 역전 원칙 경계).
 *
 * application 계층이 정의하고 persistence 계층이 구현한다. [record]는 발송 워커(dispatch 계층)가
 * 매 발송 시도 직후 동기 호출해 [WebhookDelivery] 1건을 기록한다. 수정/삭제 메서드는 없다.
 */
interface WebhookDeliveryRepository {
    /**
     * 발송 시도 1건을 기록한다.
     *
     * @param webhookId 발송 대상 구독의 식별자.
     * @param eventType 발송을 트리거한 이벤트 wireValue.
     * @param status 이 시도의 결과.
     * @param responseCode 수신자 서버 HTTP 상태 코드. 발송 자체가 불가능했으면 null.
     * @param attemptCount 이 이벤트에 대한 시도 순번(1부터 시작).
     * @param errorDetail 실패 원인 요약. 성공 시 null.
     * @return id/createdAt이 채워진 기록 결과. [status]가 [DeliveryStatus.SUCCEEDED]면 deliveredAt도 채워진다.
     */
    @Suppress("LongParameterList") // 발송 이력 레코드 필드 6개 — VO 분리보다 명시적 시그니처가 명료(OutboundWebhook.create 동일 사유)
    fun record(
        webhookId: UUID,
        eventType: String,
        status: DeliveryStatus,
        responseCode: Int?,
        attemptCount: Int,
        errorDetail: String?,
    ): WebhookDelivery

    /**
     * 특정 구독의 발송 이력을 최신순(created_at DESC)으로 페이지네이션 조회한다.
     *
     * @param webhookId 조회할 구독의 식별자.
     * @param page 0-based 페이지 번호.
     * @param size 페이지당 최대 항목 수.
     * @return 최신순으로 정렬된 발송 이력 목록.
     */
    fun listByWebhook(
        webhookId: UUID,
        page: Int,
        size: Int,
    ): List<WebhookDelivery>
}

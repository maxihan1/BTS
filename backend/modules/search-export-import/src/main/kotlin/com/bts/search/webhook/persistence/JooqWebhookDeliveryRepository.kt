// 웹훅 발송 이력 jOOQ Repository 구현 — webhook_deliveries append-only INSERT/조회 (FR-API-03 PR3)

package com.bts.search.webhook.persistence

import com.bts.search.jooq.tables.records.WebhookDeliveriesRecord
import com.bts.search.jooq.tables.references.WEBHOOK_DELIVERIES
import com.bts.search.webhook.application.WebhookDeliveryRepository
import com.bts.search.webhook.domain.DeliveryStatus
import com.bts.search.webhook.domain.WebhookDelivery
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/**
 * [WebhookDeliveryRepository]의 jOOQ 구현체.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지(DATA.md §5). 모든 public 메서드에
 * [Transactional]을 명시한다(DATA.md §6). `webhook_deliveries`는 append-only 테이블이라
 * UPDATE/DELETE 메서드는 제공하지 않는다.
 *
 * **Clock 주입** — 기본값 [Clock.systemUTC]. search 모듈에 Clock 빈이 없으므로 default 파라미터로
 * 처리한다(교훈 fr-ux-03-inbox-backend-done, ExportJobRepository와 동일 패턴).
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 * @param clock 시각 결정을 위한 시계. 테스트는 고정/가변 Clock 을 주입한다.
 */
@Repository
class JooqWebhookDeliveryRepository(
    private val dsl: DSLContext,
    private val clock: Clock = Clock.systemUTC(),
) : WebhookDeliveryRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 발송 시도 1건을 INSERT 한다.
     *
     * `delivered_at`은 [status]가 [DeliveryStatus.SUCCEEDED]일 때만 기록 시각으로 채워지고,
     * [DeliveryStatus.FAILED]면 null로 남는다 — 발송 자체가 이뤄지지 않았기 때문이다.
     */
    @Transactional
    override fun record(
        webhookId: UUID,
        eventType: String,
        status: DeliveryStatus,
        responseCode: Int?,
        attemptCount: Int,
        errorDetail: String?,
    ): WebhookDelivery {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now(clock)
        val deliveredAt = if (status == DeliveryStatus.SUCCEEDED) now else null
        log.debug("웹훅 발송 이력 기록 — webhookId={}, eventType={}, status={}", webhookId, eventType, status)

        val record =
            dsl.insertInto(WEBHOOK_DELIVERIES)
                .set(WEBHOOK_DELIVERIES.ID, id)
                .set(WEBHOOK_DELIVERIES.WEBHOOK_ID, webhookId)
                .set(WEBHOOK_DELIVERIES.EVENT_TYPE, eventType)
                .set(WEBHOOK_DELIVERIES.STATUS, status.name)
                .set(WEBHOOK_DELIVERIES.RESPONSE_CODE, responseCode)
                .set(WEBHOOK_DELIVERIES.ATTEMPT_COUNT, attemptCount)
                .set(WEBHOOK_DELIVERIES.ERROR_DETAIL, errorDetail)
                .set(WEBHOOK_DELIVERIES.CREATED_AT, now)
                .set(WEBHOOK_DELIVERIES.DELIVERED_AT, deliveredAt)
                .returning()
                .fetchOne()
                ?: error("INSERT 후 RETURNING 실패 — webhookId=$webhookId, eventType=$eventType")

        return record.toDomain()
    }

    /**
     * 특정 구독의 발송 이력을 `created_at DESC, id ASC` 안정 정렬로 페이지네이션 반환한다.
     */
    @Transactional(readOnly = true)
    override fun listByWebhook(
        webhookId: UUID,
        page: Int,
        size: Int,
    ): List<WebhookDelivery> =
        dsl.selectFrom(WEBHOOK_DELIVERIES)
            .where(WEBHOOK_DELIVERIES.WEBHOOK_ID.eq(webhookId))
            .orderBy(WEBHOOK_DELIVERIES.CREATED_AT.desc(), WEBHOOK_DELIVERIES.ID.asc())
            .limit(size)
            .offset(page.toLong() * size.toLong())
            .fetch()
            .map { it.toDomain() }

    // ── private mapper ─────────────────────────────────────────────────────────

    /**
     * jOOQ [WebhookDeliveriesRecord]를 도메인 [WebhookDelivery]로 변환한다.
     *
     * `webhook_id`/`event_type`/`status`는 DB DEFAULT 가 없는 NOT NULL 컬럼이라 jOOQ 가 이미
     * non-null Kotlin 타입으로 생성한다. `attempt_count`는 `DEFAULT 0`이 있어 nullable 타입으로
     * 생성되지만 [record]가 항상 명시적으로 값을 채우므로 여기서는 방어적으로만 확인한다.
     */
    private fun WebhookDeliveriesRecord.toDomain(): WebhookDelivery =
        WebhookDelivery(
            id = id,
            webhookId = webhookId,
            eventType = eventType,
            status = DeliveryStatus.valueOf(status),
            responseCode = responseCode,
            attemptCount = attemptCount ?: error("webhook_deliveries.attempt_count 가 null — id=$id"),
            errorDetail = errorDetail,
            createdAt = createdAt?.toInstant(),
            deliveredAt = deliveredAt?.toInstant(),
        )
}

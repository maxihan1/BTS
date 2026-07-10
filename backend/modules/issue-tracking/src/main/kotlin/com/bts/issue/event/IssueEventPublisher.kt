// 이슈 도메인 이벤트를 pgmq q_issue_events 큐에 enqueue 하는 아웃바운드 어댑터

package com.bts.issue.event

import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 이슈 도메인 이벤트를 pgmq 큐에 발행하는 아웃바운드 어댑터.
 *
 * [Propagation.MANDATORY] — 반드시 호출자의 트랜잭션 안에서 실행되어야 한다.
 * 이슈 상태 변경과 이벤트 enqueue 가 같은 트랜잭션에 묶여야 outbox 패턴이 보장된다 (DATA.md §7.2).
 * 트랜잭션 없이 호출하면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
 *
 * @param dsl jOOQ [DSLContext] — pgmq.send raw SQL 실행에 사용.
 * @param objectMapper Jackson [ObjectMapper] — [IssueDomainEvent] → JSON 직렬화.
 */
@Component
class IssueEventPublisher(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [event] 를 JSON 으로 직렬화해 [QUEUE_NAME] 큐에 enqueue 한다.
     *
     * [isWebhookPublishable] 이 `true` 인 이벤트는 [WEBHOOK_QUEUE_NAME] 큐로도 동일 payload 를
     * dual-send 한다 (FR-API-03 PR3 — 구독형 아웃바운드 Webhook 발송 트리거).
     *
     * 호출 시 활성 트랜잭션이 없으면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
     *
     * @param event 발행할 이슈 도메인 이벤트.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(event: IssueDomainEvent) {
        val payload = objectMapper.writeValueAsString(event)
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
        log.info("event_published queue={} type={}", QUEUE_NAME, event::class.simpleName)

        if (isWebhookPublishable(event)) {
            dsl.execute("SELECT pgmq.send(?, ?::jsonb)", WEBHOOK_QUEUE_NAME, payload)
            log.info("event_published queue={} type={}", WEBHOOK_QUEUE_NAME, event::class.simpleName)
        }
    }

    /**
     * [event] 가 구독형 아웃바운드 Webhook 발송 대상인지 판정한다.
     *
     * search-export-import 모듈의 `WebhookEventCatalog.PUBLISHABLE`(`issue.created`,
     * `issue.transitioned`)과 값이 정합해야 한다. 다만 **BC 격리** 상 해당 타입을 직접 import 해
     * 코드를 공유할 수 없으므로 (다른 바운디드 컨텍스트의 클래스 직접 참조 금지) issue-tracking 이
     * 자체적으로 동일한 판정을 유지한다.
     *
     * `else` 분기 없는 exhaustive `when` — [IssueDomainEvent] 에 새 구현체가 추가되면 이 함수가
     * 컴파일에 실패해, 분류를 누락한 채 조용히 미발행(under-send)되는 사고를 원천 차단한다.
     * 두 판정 간 값 정합은 이 exhaustive when(under-send 컴파일 차단) + 통합 테스트의
     * "PUBLISHABLE 각 이벤트 → 발송 도달" 단언(over-send 는 search `findMatching` 이 무해화)으로
     * 이중 가드된다.
     *
     * @param event 판정 대상 이슈 도메인 이벤트.
     * @return `q_webhook_events` 로도 발행해야 하면 `true`.
     */
    private fun isWebhookPublishable(event: IssueDomainEvent): Boolean =
        when (event) {
            is IssueCreated -> true
            is IssueTransitioned -> true
            is IssueUpdated -> false
            is IssueSoftDeleted -> false
            is IssueMentioned -> false
            is IssueDueSoon -> false
            is IssueOverdue -> false
            is IssueAssigned -> false
        }

    companion object {
        /** pgmq 큐 이름 — V002__pgmq_queue_issue_events.sql 에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_issue_events"

        /** pgmq 큐 이름 — V034__pgmq_queue_webhook_events.sql 에서 생성된 큐와 일치해야 한다. */
        const val WEBHOOK_QUEUE_NAME = "q_webhook_events"
    }
}

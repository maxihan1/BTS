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
     * [isAutomationPublishable] 이 `true` 인 이벤트는 [AUTOMATION_QUEUE_NAME] 큐로도 동일 payload 를
     * fan-out 한다 (FR-AT-01 Task 10 — automation BC 트리거 감지). `q_issue_events` 발행은 특수분기
     * 없이 uniform 하게 유지한다 — 신규 [IssueCommented] 도 `q_issue_events` 로 실려 NotificationWorker 가
     * **의도적으로 소비**한다. `NotificationEventType.ISSUE_COMMENTED("issue.commented")` 와 V401 시드
     * (`issue.commented` → REPORTER/ASSIGNEE/WATCHER/MENTIONED, IN_APP)가 FR-NT-01 §9.1.2 매트릭스에
     * 이미 존재하므로, 이 producer 배선이 **사전 설계된 댓글 인앱 알림 경로를 완성**한다(게이트2 옵션A 확정,
     * ADR 2026-07-10-fr-at-01-automation-triggers D3). 작성자(actor) 본인은
     * `EventRecipientResolver` 가 제외하고, 가시성 필터로 권한 없는 수신자는 걸러진다.
     *
     * 호출 시 활성 트랜잭션이 없으면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
     *
     * @param event 발행할 이슈 도메인 이벤트.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(event: IssueDomainEvent) {
        val payload = objectMapper.writeValueAsString(event)
        sendToQueue(QUEUE_NAME, payload, event)

        if (isWebhookPublishable(event)) {
            sendToQueue(WEBHOOK_QUEUE_NAME, payload, event)
        }

        if (isAutomationPublishable(event)) {
            sendToQueue(AUTOMATION_QUEUE_NAME, payload, event)
        }
    }

    /**
     * [payload] 를 [queueName] pgmq 큐에 enqueue 하고 발행 로그를 남긴다.
     *
     * [publish] 의 3개 큐 fan-out(q_issue_events/q_webhook_events/q_automation_events)이 공유하는
     * "SELECT pgmq.send" 실행 + 로깅 반복을 제거한 헬퍼 (REFACTOR — FR-AT-01 Task 10).
     *
     * @param queueName 대상 pgmq 큐 이름.
     * @param payload 직렬화된 이벤트 JSON.
     * @param event 로그 기록용 원본 이벤트 (타입명 추출).
     */
    private fun sendToQueue(
        queueName: String,
        payload: String,
        event: IssueDomainEvent,
    ) {
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", queueName, payload)
        log.info("event_published queue={} type={}", queueName, event::class.simpleName)
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
            is IssueCommented -> false
            is IssueAssigned -> false
            // 외부 웹훅 미발행 — 삭제된 댓글의 존재 자체가 외부로 새면 모더레이션 목적에 반한다.
            is IssueCommentDeleted -> false
        }

    /**
     * [event] 가 automation BC 트리거 감지 대상인지 판정한다 (FR-AT-01 Task 10).
     *
     * automation 대상 = `issue.created` / `issue.updated` / `issue.commented`
     * (ADR 2026-07-10-fr-at-01-automation-triggers D2 — `ISSUE_CREATED`/`ISSUE_UPDATED`/
     * `ISSUE_COMMENTED` 트리거 타입과 정합).
     *
     * `else` 분기 없는 exhaustive `when` — [isWebhookPublishable] 과 동일한 이유로, [IssueDomainEvent]
     * 에 새 구현체가 추가되면 이 함수가 컴파일에 실패해 분류 누락(under-send)을 원천 차단한다.
     *
     * @param event 판정 대상 이슈 도메인 이벤트.
     * @return `q_automation_events` 로도 발행해야 하면 `true`.
     */
    private fun isAutomationPublishable(event: IssueDomainEvent): Boolean =
        when (event) {
            is IssueCreated -> true
            is IssueUpdated -> true
            is IssueCommented -> true
            // automation 미대상 — 대응 TriggerType 이 아직 없다(ADR D2 의 6종에 comment_deleted 없음).
            // 트리거를 추가하려면 TriggerType enum · 룰 스키마 · 조건 평가까지 함께 가야 하며,
            // 그 수요는 아직 확인되지 않았다(TODOS §댓글 이벤트 항목 — 알림 슬라이스만 먼저 구현).
            is IssueCommentDeleted -> false
            is IssueTransitioned -> false
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

        /** pgmq 큐 이름 — V036__pgmq_queue_automation_events.sql 에서 생성된 큐와 일치해야 한다. */
        const val AUTOMATION_QUEUE_NAME = "q_automation_events"
    }
}

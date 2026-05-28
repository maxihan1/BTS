// 워크플로우 스킴 도메인 이벤트를 pgmq q_workflow_scheme_events 큐에 enqueue 하는 아웃바운드 어댑터

package com.bts.workflow.scheme.adapter.outbound

import com.bts.workflow.scheme.event.WorkflowSchemeDomainEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 워크플로우 스킴 도메인 이벤트를 pgmq 큐에 발행하는 아웃바운드 어댑터.
 *
 * **transaction outbox 패턴.** 호출자 트랜잭션 필수. PR #17 IssueEventPublisher 패턴 일치.
 *
 * 스킴 Application Service 가 DB 변경(UPSERT/DELETE) 과 이벤트 enqueue 를
 * 동일 트랜잭션 안에서 수행한다. 트랜잭션 롤백 시 이벤트도 자동으로 폐기되어
 * DB 상태와 이벤트 큐가 항상 일치한다 (DATA.md §7.2).
 *
 * [Propagation.MANDATORY] — 반드시 호출자의 트랜잭션 안에서 실행되어야 한다.
 * 트랜잭션 없이 호출하면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
 *
 * 발행 가능 이벤트 3종.
 * - [com.bts.workflow.scheme.event.WorkflowSchemeAssignedEvent] — 스킴 프로젝트 배정
 * - [com.bts.workflow.scheme.event.WorkflowSchemeUpdatedEvent] — 스킴 필드 변경
 * - [com.bts.workflow.scheme.event.WorkflowSchemeDeletedEvent] — 스킴 삭제
 *
 * @param dsl jOOQ [DSLContext] — pgmq.send raw SQL 실행에 사용.
 * @param objectMapper Jackson [ObjectMapper] — [WorkflowSchemeDomainEvent] → JSON 직렬화.
 *   [@JsonTypeInfo] 로 `type` 필드가 포함된 다형성 JSON 을 생성한다.
 */
@Component
class WorkflowSchemeEventPublisher(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [event] 를 JSON 으로 직렬화해 [QUEUE_NAME] 큐에 enqueue 한다.
     *
     * 호출 시 활성 트랜잭션이 없으면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
     *
     * @param event 발행할 워크플로우 스킴 도메인 이벤트 (3 종 중 하나).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(event: WorkflowSchemeDomainEvent) {
        val payload = objectMapper.writeValueAsString(event)
        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, payload)
        log.info("event_published queue={} type={}", QUEUE_NAME, event::class.simpleName)
    }

    companion object {
        /** pgmq 큐 이름 — V004__workflow_schemes.sql 에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_workflow_scheme_events"
    }
}

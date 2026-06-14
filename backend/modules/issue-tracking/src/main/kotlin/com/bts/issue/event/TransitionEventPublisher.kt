// 워크플로우 전이 도메인 이벤트를 pgmq q_transition_events 큐에 enqueue 하는 아웃바운드 어댑터

package com.bts.issue.event

import com.bts.shared.workflow.DomainEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 워크플로우 전이 post-action 도메인 이벤트를 pgmq 큐에 발행하는 아웃바운드 어댑터.
 *
 * [Propagation.MANDATORY] — 반드시 호출자의 트랜잭션 안에서 실행되어야 한다.
 * 전이 상태 변경과 이벤트 enqueue 가 같은 트랜잭션에 묶여야 outbox 패턴이 보장된다 (DATA.md §7.2).
 * 트랜잭션 없이 호출하면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
 *
 * @param dsl jOOQ [DSLContext] — pgmq.send raw SQL 실행에 사용.
 * @param objectMapper Jackson [ObjectMapper] — [DomainEvent] → JSON 직렬화.
 */
@Component
class TransitionEventPublisher(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [event] 를 JSON 으로 직렬화해 [QUEUE_NAME] 큐에 enqueue 한다.
     *
     * 호출 시 활성 트랜잭션이 없으면 [org.springframework.transaction.IllegalTransactionStateException] 이 발생한다.
     *
     * @param event 발행할 전이 도메인 이벤트 ([DomainEvent]).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(event: DomainEvent) {
        TODO("RED — GREEN 단계에서 구현")
    }

    companion object {
        /** pgmq 큐 이름 — V022__pgmq_queue_transition_events.sql 에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_transition_events"
    }
}

// 매칭된 트리거를 q_automation_execution 큐에 적재하는 아웃바운드 어댑터 (FR-AT-01 Task 4, ADR D4)

package com.bts.automation.adapter

import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 매칭된 트리거의 발화 결과를 `q_automation_execution` pgmq 큐에 적재하는 아웃바운드 어댑터
 * (FR-AT-01 Task 4, ADR D4 발화 이음선).
 *
 * FR-AT-01 은 액션 부재 상태이므로 발화 결과를 실행 큐에 이음선으로만 남긴다. FR-AT-02 액션
 * executor 가 이 큐를 소비한다(현 FR 범위 밖).
 *
 * ## 트랜잭션 경계
 * 기본 전파(REQUIRED)를 쓴다 — 호출자(트리거 감지 워커 T7/T8, 웹훅 컨트롤러 T9)에 트랜잭션이
 * 있으면 그 안에서 enqueue 되어 "메시지 처리 → enqueue" 가 원자적으로 커밋되고(pgmq 메시지
 * 생명주기, [[pgmq-consumer-message-lifecycle-p0]]), 없으면 독립 트랜잭션으로 발행한다. 워커의
 * 최종 트랜잭션 정책은 각 워커(T7/T8/T9)가 결정하며, 이 어댑터는 발행만 담당한다.
 *
 * ## SQL 인젝션 방어
 * pgmq.send 의 큐 이름·payload 를 `?` positional 바인딩으로 전달한다(문자열 결합 없음). jsonb 캐스팅은
 * `?::jsonb` — IssueEventPublisher 선례 동형(DATA.md §5 pgmq 예외 규칙).
 *
 * @param jdbcTemplate positional 바인딩 [JdbcTemplate].
 * @param objectMapper 실행 payload 직렬화용 Jackson [ObjectMapper].
 */
@Component
class AutomationExecutionEnqueuer(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `{ ruleId, triggerType, triggerEvent }` 를 [QUEUE_NAME] 큐에 적재한다.
     *
     * @param ruleId 발화한 룰의 id.
     * @param triggerType 발화 트리거 타입.
     * @param triggerEvent 발화를 유발한 이벤트 payload(이슈 이벤트/웹훅 본문 등). SCHEDULED 처럼
     *   원천 이벤트가 없으면 호출자가 빈 객체 노드를 전달한다.
     */
    @Transactional
    fun enqueue(
        ruleId: UUID,
        triggerType: TriggerType,
        triggerEvent: JsonNode,
    ) {
        val payload =
            objectMapper.createObjectNode().apply {
                put("ruleId", ruleId.toString())
                put("triggerType", triggerType.name)
                set<JsonNode>("triggerEvent", triggerEvent)
            }
        val json = objectMapper.writeValueAsString(payload)
        jdbcTemplate.queryForObject(SQL_SEND, Long::class.java, QUEUE_NAME, json)
        log.info("automation_execution_enqueued queue={} ruleId={} triggerType={}", QUEUE_NAME, ruleId, triggerType)
    }

    private companion object {
        /** pgmq 큐 이름 — V301__pgmq_queue_automation_execution.sql 에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_automation_execution"

        /** pgmq.send 호출 — msg_id(bigint) 1행 반환. positional `?` 바인딩 + `?::jsonb` 캐스팅. */
        const val SQL_SEND = "SELECT pgmq.send(?, ?::jsonb)"
    }
}

// q_automation_events pgmq 큐를 폴링해 이슈 이벤트를 트리거 타입으로 매칭하고 실행 큐로 넘기는 워커 (FR-AT-01 Task 7)

package com.bts.automation.worker

import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.application.MatchedIssueEvent
import com.bts.automation.application.TriggerMatcher
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.ResultSet

/**
 * `q_automation_events` pgmq 큐를 폴링해 이슈 이벤트(created/updated/commented)를 [TriggerMatcher] 로
 * 매칭하고, 매칭된 enabled 룰마다 [AutomationExecutionEnqueuer] 로 `q_automation_execution` 에
 * enqueue 하는 pgmq consumer 워커 (FR-AT-01 Task 7, ADR D2·D4).
 *
 * ## 처리 흐름
 * 1. `pgmq.read(queue, vt, qty)` 로 메시지 읽기.
 * 2. JSON 파싱 실패 → skip + delete(재시도해도 절대 성공 못하는 상태, under-processing 방지).
 * 3. [TriggerMatcher.match] 로 트리거 타입 매핑. 매핑 없음(미관심 타입/malformed) → skip + delete.
 * 4. [AutomationRuleRepository.findEnabledByProjectAndTriggerType] 로 매칭 대상 룰 조회
 *    (disabled/soft-deleted 룰은 이 조회가 이미 제외 — 스펙 S8/EC7).
 * 5. [TriggerType.ISSUE_UPDATED] 는 룰의 triggerConfig `fields` 와 이벤트 필드 교집합 필터를 추가
 *    적용한다(스펙 S4).
 * 6. 필터 통과 룰마다 [AutomationExecutionEnqueuer.enqueue].
 * 7. 처리 성공 → `pgmq.delete`. 예외 → delete 안 함(vt 만료 후 재전달, at-least-once).
 *    read_ct 가 [MAX_RECEIVE_COUNT] 초과면 `pgmq.archive`(dead-letter).
 *
 * ## `@Transactional` 없음 — 의도적 설계
 * pgmq.read/delete/archive 는 트랜잭션 범위 밖에서 호출해도 pgmq 내부 vt 가 atomic 하게 갱신된다.
 * [AutomationExecutionEnqueuer.enqueue] 는 자체 `@Transactional` 경계를 갖는다
 * (NotificationWorker 선례, self-invocation 트랜잭션 오염 회피).
 *
 * ## `@Scheduled` 결선 (모듈 첫 스케줄 워커)
 * `@EnableScheduling` 결선은 FR-AT-01 Task 11 이 담당한다(automation 모듈에는 아직 결선 안 됨). 이
 * Task 의 테스트는 [pollAndProcess] 를 직접 호출한다(스케줄 대기 없음, plan-eng-review E5).
 *
 * ## BC 격리
 * issue-tracking 의 `IssueKey`/`IssueDomainEvent` 를 직접 import 하지 않는다. pgmq JSON 을 [JsonNode]
 * 로 파싱해 [TriggerMatcher] 로 넘긴다.
 *
 * @param jdbcTemplate positional `?` 바인딩 [JdbcTemplate]. pgmq read/delete/archive 실행.
 * @param ruleRepository 프로젝트+트리거타입 매칭 활성 룰 조회.
 * @param enqueuer 매칭된 룰의 발화 결과를 `q_automation_execution` 에 적재.
 * @param objectMapper pgmq 메시지 JSON 파싱용 Jackson [ObjectMapper].
 */
@Component
class AutomationEventWorker(
    private val jdbcTemplate: JdbcTemplate,
    private val ruleRepository: AutomationRuleRepository,
    private val enqueuer: AutomationExecutionEnqueuer,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `q_automation_events` 큐를 폴링하여 대기 중인 이슈 이벤트 메시지를 처리한다.
     *
     * 메시지가 없으면 즉시 반환한다. `@Transactional` 없음(클래스 KDoc 참조).
     */
    @Scheduled(fixedDelayString = "\${bts.automation.event-worker.poll-interval-ms:500}")
    fun pollAndProcess() {
        val messages = jdbcTemplate.query(SQL_READ, PgmqMessageRowMapper, QUEUE_NAME, VT_SECONDS, BATCH_SIZE)
        for (message in messages) {
            processMessage(message)
        }
    }

    /**
     * 단일 메시지를 처리한다. 역직렬화 실패/미관심 타입은 즉시 skip+delete, 매칭 처리 중 예외는
     * delete 를 건너뛰어 재전달을 허용한다.
     *
     * @param message pgmq 에서 읽은 원본 메시지.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun processMessage(message: PgmqMessage) {
        val node = parseJson(message.messageJson, message.msgId)
        if (node == null) {
            skipAndDelete(message.msgId, reason = "deserialize_failed")
            return
        }

        val matched = TriggerMatcher.match(node)
        if (matched == null) {
            skipAndDelete(message.msgId, reason = "unmatched_event")
            return
        }

        try {
            matchRulesAndEnqueue(matched, node)
            deleteMessage(message.msgId)
        } catch (e: Exception) {
            log.error(
                "automation_event_worker_processing_failed msgId={} triggerType={} error={}",
                message.msgId,
                matched.triggerType,
                e.message,
                e,
            )
            if (message.readCt > MAX_RECEIVE_COUNT) {
                archiveMessage(message.msgId, message.readCt)
            }
            // delete 하지 않음 — vt 만료 후 재전달(at-least-once)
        }
    }

    /**
     * 역직렬화 실패/미관심 타입(unmatched) 메시지를 [reason] 로그와 함께 즉시 삭제한다. 두 상황 모두
     * 재시도해도 절대 성공하지 못하므로 즉시 skip+delete 한다(under-processing 방지).
     *
     * @param msgId 대상 메시지 id.
     * @param reason 로그에 남길 skip 사유.
     */
    private fun skipAndDelete(
        msgId: Long,
        reason: String,
    ) {
        log.info("automation_event_worker_skipped msgId={} reason={} action=delete", msgId, reason)
        deleteMessage(msgId)
    }

    /**
     * [matched] 의 프로젝트+트리거타입에 매칭되는 enabled 룰을 조회해, ISSUE_UPDATED 필드 필터를
     * 통과한 룰마다 실행 큐에 적재한다.
     *
     * @param matched 매칭된 이벤트 컨텍스트.
     * @param triggerEvent enqueue payload 에 포함될 원본 이벤트 JSON.
     */
    private fun matchRulesAndEnqueue(
        matched: MatchedIssueEvent,
        triggerEvent: JsonNode,
    ) {
        val rules = ruleRepository.findEnabledByProjectAndTriggerType(matched.projectKey, matched.triggerType)
        for (rule in rules) {
            if (matched.triggerType == TriggerType.ISSUE_UPDATED &&
                !TriggerMatcher.matchesFieldFilter(rule.triggerConfig, matched.updatedFields)
            ) {
                continue
            }
            enqueuer.enqueue(rule.id, matched.triggerType, triggerEvent)
        }
    }

    /** messageJson 을 Jackson 으로 파싱한다. 실패 시 null 반환. */
    @Suppress("TooGenericExceptionCaught")
    private fun parseJson(
        messageJson: String,
        msgId: Long,
    ): JsonNode? =
        try {
            objectMapper.readTree(messageJson)
        } catch (e: Exception) {
            log.error("automation_event_worker_json_parse_failed msgId={} error={}", msgId, e.message)
            null
        }

    /** pgmq.archive 로 dead-letter 처리한다. */
    private fun archiveMessage(
        msgId: Long,
        readCt: Int,
    ) {
        log.error("automation_event_worker_dead_letter msgId={} readCt={} action=archive", msgId, readCt)
        jdbcTemplate.queryForObject(SQL_ARCHIVE, Boolean::class.java, QUEUE_NAME, msgId)
    }

    /** 처리 성공(또는 skip) 후 pgmq 에서 메시지를 삭제한다. */
    private fun deleteMessage(msgId: Long) {
        jdbcTemplate.queryForObject(SQL_DELETE, Boolean::class.java, QUEUE_NAME, msgId)
        log.debug("automation_event_worker_deleted msgId={}", msgId)
    }

    private companion object {
        /** pgmq 큐 이름 — issue-tracking(Task 10)이 소유·생성한 fan-out 큐. */
        const val QUEUE_NAME = "q_automation_events"

        /** pgmq visibility timeout(초) — NotificationWorker 동형 예산(단건 처리 수십ms, 배치 10건). */
        const val VT_SECONDS = 30

        /** pgmq.read 1회 폴링 최대 메시지 수. */
        const val BATCH_SIZE = 10

        /** poison/실패 메시지 최대 수신 허용 횟수. 초과 시 dead-letter(archive). */
        const val MAX_RECEIVE_COUNT = 5

        /** pgmq.read — msg_id/read_ct/message(jsonb→text 캐스팅) 조회. `?` positional 바인딩. */
        const val SQL_READ = "SELECT msg_id, read_ct, message::text AS message FROM pgmq.read(?, ?, ?)"

        /** pgmq.delete — 처리 완료/skip 메시지 제거. */
        const val SQL_DELETE = "SELECT pgmq.delete(?, ?)"

        /** pgmq.archive — dead-letter 처리. */
        const val SQL_ARCHIVE = "SELECT pgmq.archive(?, ?)"
    }
}

/** pgmq.read 한 행 — 메시지 처리에 필요한 필드만 보유. */
private data class PgmqMessage(
    val msgId: Long,
    val readCt: Int,
    val messageJson: String,
)

/** pgmq.read 결과 행 → [PgmqMessage] 매핑. */
private object PgmqMessageRowMapper : RowMapper<PgmqMessage> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): PgmqMessage =
        PgmqMessage(
            msgId = rs.getLong("msg_id"),
            readCt = rs.getInt("read_ct"),
            messageJson = rs.getString("message"),
        )
}

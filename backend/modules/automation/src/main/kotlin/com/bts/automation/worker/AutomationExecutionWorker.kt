// q_automation_execution pgmq 큐를 폴링해 발화된 룰의 액션을 실행하는 워커 — 루프 가드 2단 (FR-AT-02 Task 10)

package com.bts.automation.worker

import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.application.ActionExecutor
import com.bts.automation.domain.AutomationRule
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `q_automation_execution` pgmq 큐를 폴링해 발화된 룰의 액션을 [ActionExecutor] 로 실행하는 워커
 * (FR-AT-02 Task 10, [[pgmq-consumer-message-lifecycle-p0]]).
 *
 * `AutomationExecutionEnqueuer`(FR-AT-01 Task 4/7/8)가 적재한
 * `{ ruleId, triggerType, triggerEvent, executionDepth? }` 를 소비하는 이 큐의 첫 소비자다.
 * [AutomationEventWorker] 의 pgmq read/폴링 골격을 그대로 따르되, 처리 완료 신호로 **delete 대신
 * archive** 를 쓴다 — 실행 이력을 pgmq 아카이브 테이블에 남겨 사후 감사(어떤 룰이 언제 무엇을
 * 실행했는지)를 가능하게 한다(EventWorker 의 이슈-이벤트 원본은 감사 가치가 낮아 delete 를 택한
 * 것과 다른 선택).
 *
 * ## 처리 흐름
 * 1. `pgmq.read(queue, vt, qty)` 로 메시지 읽기.
 * 2. JSON 파싱 실패(malformed) → 재시도해도 절대 성공 못하므로 즉시 archive.
 * 3. [AutomationRuleRepository.findById] 로 룰 로드. 없거나(soft-delete 포함, `findById` 가 이미
 *    제외) disabled 면 스킵 + archive(EC7 — 발화 이후 상태가 바뀐 경우 대비).
 * 4. 루프 가드 2단(클래스 KDoc "루프 가드 2단" 참조) 통과 못하면 스킵 + archive.
 * 5. [ActionExecutor.execute] 로 액션 실행 후 archive. (ruleId, issueKey) 실행 시각을 기록한다.
 * 6. 그 외 처리 중 예외(DB 순단 등 일시 장애) → archive 하지 않고 vt 만료 후 재전달(at-least-once).
 *    `read_ct` 가 [MAX_RECEIVE_COUNT] 초과면 dead-letter 로 archive(포이즌 메시지 회피).
 *
 * ## 루프 가드 2단
 * - (a) 직접 체인 깊이 — `executionDepth`(payload 에 없으면 0) 가 [MAX_EXECUTION_DEPTH] 를 초과하면
 *   차단한다(자동화가 자동화를 연쇄 발화하는 깊이 상한, automation 직접 체인 한정).
 * - (b) (ruleId, issueKey) 최근 실행 억제 — 같은 룰이 같은 이슈에 [SUPPRESSION_WINDOW] 이내 재실행되면
 *   스킵한다(액션 → issue-tracking 이벤트 → automation 재발화의 짧은 왕복 루프 차단). 인메모리
 *   [ConcurrentHashMap] 에 `"ruleId:issueKey"` → 마지막 실행 시각을 기록하고, 접근 시 창을 지난
 *   엔트리를 제거해 무한 성장을 막는다(새 캐시 라이브러리 도입 없이 단순 자료구조로 구현). 워커
 *   인스턴스 로컬 캐시라 다중 인스턴스 배포에서는 인스턴스별로만 억제된다(단일 호스트 배포 전제).
 *   triggerEvent 에서 이슈 축을 찾지 못하면(SCHEDULED/WEBHOOK 빈 payload) 이 가드는 적용하지 않는다.
 *
 * ## `@Transactional` 없음 — 의도적 설계([AutomationEventWorker] 동형)
 * pgmq read/archive 는 트랜잭션 범위 밖에서 호출해도 pgmq 내부에서 atomic 하게 처리된다.
 * [AutomationRuleRepository.findById]/[ActionExecutor.execute] 가 위임하는 각 포트 호출은 자체
 * 트랜잭션 경계를 갖는다(self-invocation 트랜잭션 오염 회피,
 * [[transaction-self-invocation-requires-new]]).
 *
 * ## `@Scheduled` 결선
 * `@EnableScheduling` 결선은 이 Task 범위 밖(FR-AT-01 Task 11)이다. 이 Task 의 테스트는
 * [pollAndProcess] 를 직접 호출한다(스케줄 대기 없음, plan-eng-review E5 동형).
 *
 * ## BC 격리
 * issue-tracking 의 `IssueKey`/`IssueDomainEvent` 를 직접 import 하지 않는다. triggerEvent 는
 * [JsonNode] 로만 다루고 [ActionExecutor] 에 그대로 위임한다.
 *
 * @param jdbcTemplate positional `?` 바인딩 [JdbcTemplate]. pgmq read/archive 실행.
 * @param objectMapper pgmq 메시지 JSON 파싱용 Jackson [ObjectMapper].
 * @param ruleRepository 발화한 룰 조회(활성 여부 판정).
 * @param actionExecutor 룰 액션 리스트 실행 디스패처.
 * @param clock 억제 캐시 시각 소스. 기본값 UTC(테스트에서 결정적 시각으로 교체).
 */
@Component
class AutomationExecutionWorker(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val ruleRepository: AutomationRuleRepository,
    private val actionExecutor: ActionExecutor,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** (ruleId, issueKey) → 마지막 실행 시각. 루프 가드 (b) 용 인스턴스 로컬 캐시(클래스 KDoc 참조). */
    private val recentExecutions = ConcurrentHashMap<String, Instant>()

    /**
     * `q_automation_execution` 큐를 폴링하여 대기 중인 발화 메시지를 처리한다.
     *
     * 메시지가 없으면 즉시 반환한다. `@Transactional` 없음(클래스 KDoc 참조).
     */
    @Scheduled(fixedDelayString = "\${bts.automation.execution-worker.poll-interval-ms:$DEFAULT_POLL_INTERVAL_MS}")
    fun pollAndProcess() {
        val messages = jdbcTemplate.query(SQL_READ, ExecutionQueueMessageRowMapper, QUEUE_NAME, VT_SECONDS, BATCH_SIZE)
        for (message in messages) {
            processMessage(message)
        }
    }

    /** 단일 메시지를 파싱→룰 로드/루프 가드→실행 순으로 처리한다(클래스 KDoc "처리 흐름" 참조). */
    private fun processMessage(message: ExecutionQueueMessage) {
        val payload = parsePayload(message.messageJson, message.msgId)
        if (payload == null) {
            log.error("automation_execution_worker_deserialize_failed msgId={} action=archive", message.msgId)
            archiveMessage(message.msgId)
            return
        }

        val rule = resolveExecutableRule(payload, message.msgId) ?: return
        runExecution(message, payload, rule)
    }

    /**
     * 룰을 로드하고 루프 가드 2단(클래스 KDoc "루프 가드 2단" 참조)을 순서대로 통과했는지 확인한다.
     * 어느 단계든 실패하면 그 단계에서 이미 archive 를 수행하고 `null` 을 반환한다(EC7 + 루프 가드 a/b).
     */
    private fun resolveExecutableRule(
        payload: ExecutionPayload,
        msgId: Long,
    ): AutomationRule? =
        loadActiveRule(payload, msgId)
            ?.takeIf { !isDepthExceeded(payload, msgId) }
            ?.takeIf { !isRecentlyExecuted(payload, msgId) }

    /** 룰을 로드한다. 없거나 disabled 면 스킵+archive 후 `null` 을 반환한다(EC7). */
    private fun loadActiveRule(
        payload: ExecutionPayload,
        msgId: Long,
    ): AutomationRule? {
        val rule = ruleRepository.findById(payload.ruleId)
        if (rule == null || !rule.enabled) {
            log.info(
                "automation_execution_worker_rule_unavailable msgId={} ruleId={} action=archive",
                msgId,
                payload.ruleId,
            )
            archiveMessage(msgId)
            return null
        }
        return rule
    }

    /** 루프 가드 (a) — 직접 체인 깊이가 [MAX_EXECUTION_DEPTH] 를 초과하면 archive 후 `true` 를 반환한다. */
    private fun isDepthExceeded(
        payload: ExecutionPayload,
        msgId: Long,
    ): Boolean {
        val exceeded = payload.executionDepth > MAX_EXECUTION_DEPTH
        if (exceeded) {
            log.warn(
                "automation_execution_worker_depth_exceeded msgId={} ruleId={} depth={} action=archive",
                msgId,
                payload.ruleId,
                payload.executionDepth,
            )
            archiveMessage(msgId)
        }
        return exceeded
    }

    /** 루프 가드 (b) — (ruleId, issueKey) 조합이 [SUPPRESSION_WINDOW] 이내 재실행이면 archive 후 `true` 를 반환한다. */
    private fun isRecentlyExecuted(
        payload: ExecutionPayload,
        msgId: Long,
    ): Boolean {
        val suppressed = payload.issueKey != null && isSuppressed(payload.ruleId, payload.issueKey)
        if (suppressed) {
            log.info(
                "automation_execution_worker_suppressed msgId={} ruleId={} issueKey={} action=archive",
                msgId,
                payload.ruleId,
                payload.issueKey,
            )
            archiveMessage(msgId)
        }
        return suppressed
    }

    /** [ActionExecutor.execute] 를 호출한다. 성공하면 억제 캐시를 갱신하고 archive, 실패는 재시도를 허용한다. */
    @Suppress("TooGenericExceptionCaught")
    private fun runExecution(
        message: ExecutionQueueMessage,
        payload: ExecutionPayload,
        rule: AutomationRule,
    ) {
        try {
            actionExecutor.execute(rule, payload.triggerEvent, dryRun = false)
            if (payload.issueKey != null) {
                recentExecutions[suppressionKey(payload.ruleId, payload.issueKey)] = clock.instant()
            }
            log.info(
                "automation_execution_worker_executed msgId={} ruleId={} issueKey={}",
                message.msgId,
                payload.ruleId,
                payload.issueKey,
            )
            archiveMessage(message.msgId)
        } catch (e: Exception) {
            log.error(
                "automation_execution_worker_processing_failed msgId={} ruleId={} error={}",
                message.msgId,
                payload.ruleId,
                e.message,
                e,
            )
            if (message.readCt > MAX_RECEIVE_COUNT) {
                archiveMessage(message.msgId, message.readCt)
            }
            // archive 하지 않음 — vt 만료 후 재전달(at-least-once)
        }
    }

    /** (b) 루프 가드 — [ruleId]+[issueKey] 조합이 [SUPPRESSION_WINDOW] 이내 실행 이력이 있으면 `true`. */
    private fun isSuppressed(
        ruleId: UUID,
        issueKey: String,
    ): Boolean {
        val now = clock.instant()
        cleanupExpired(recentExecutions, now)
        val last = recentExecutions[suppressionKey(ruleId, issueKey)] ?: return false
        return Duration.between(last, now) < SUPPRESSION_WINDOW
    }

    /** messageJson 을 파싱해 [ExecutionPayload] 로 변환한다. 실패 시 `null`(호출자가 archive 처리). */
    @Suppress("TooGenericExceptionCaught")
    private fun parsePayload(
        messageJson: String,
        msgId: Long,
    ): ExecutionPayload? =
        try {
            val node = objectMapper.readTree(messageJson)
            val triggerEvent = node.path(FIELD_TRIGGER_EVENT)
            ExecutionPayload(
                ruleId = UUID.fromString(node.path(FIELD_RULE_ID).asText()),
                triggerEvent = triggerEvent,
                executionDepth = node.path(FIELD_EXECUTION_DEPTH).takeIf { it.isNumber }?.asInt() ?: 0,
                issueKey = extractIssueKey(triggerEvent),
            )
        } catch (e: Exception) {
            log.error("automation_execution_worker_payload_parse_failed msgId={} error={}", msgId, e.message)
            null
        }

    /** pgmq.archive 로 메시지를 종결 처리한다(성공/스킵/dead-letter 공통 종결 신호, 클래스 KDoc 참조). */
    private fun archiveMessage(
        msgId: Long,
        readCt: Int? = null,
    ) {
        if (readCt != null) {
            log.error("automation_execution_worker_dead_letter msgId={} readCt={} action=archive", msgId, readCt)
        }
        jdbcTemplate.queryForObject(SQL_ARCHIVE, Boolean::class.java, QUEUE_NAME, msgId)
    }

    private companion object {
        /** pgmq 큐 이름 — `AutomationExecutionEnqueuer` 가 적재하는 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_automation_execution"

        /** pgmq visibility timeout(초) — [AutomationEventWorker] 동형 예산. */
        const val VT_SECONDS = 30

        /** pgmq.read 1회 폴링 최대 메시지 수. */
        const val BATCH_SIZE = 10

        /** poison/실패 메시지 최대 수신 허용 횟수. 초과 시 dead-letter(archive). */
        const val MAX_RECEIVE_COUNT = 5

        /** 루프 가드 (a) — automation 직접 체인 깊이 상한. */
        const val MAX_EXECUTION_DEPTH = 10

        /** 폴링 주기 기본값(ms) — 프로퍼티 미설정 시 사용. */
        const val DEFAULT_POLL_INTERVAL_MS = 500

        const val FIELD_RULE_ID = "ruleId"
        const val FIELD_TRIGGER_EVENT = "triggerEvent"
        const val FIELD_EXECUTION_DEPTH = "executionDepth"

        /** pgmq.read — msg_id/read_ct/message(jsonb→text 캐스팅) 조회. `?` positional 바인딩. */
        const val SQL_READ = "SELECT msg_id, read_ct, message::text AS message FROM pgmq.read(?, ?, ?)"

        /** pgmq.archive — 처리 종결(성공/스킵/dead-letter) 메시지를 아카이브 테이블로 이동. */
        const val SQL_ARCHIVE = "SELECT pgmq.archive(?, ?)"
    }
}

/**
 * pgmq.read 한 행 — 메시지 처리에 필요한 필드만 보유. [AutomationEventWorker] 의 동명 클래스와 이름이
 * 충돌하지 않도록 접두사를 구분한다(Kotlin 최상위 `private` 클래스도 패키지 단위 클래스명 네임스페이스를
 * 공유하므로, 같은 패키지 다른 파일과 이름이 겹치면 컴파일 시점 Redeclaration 오류가 난다).
 */
private data class ExecutionQueueMessage(
    val msgId: Long,
    val readCt: Int,
    val messageJson: String,
)

/** pgmq.read 결과 행 → [ExecutionQueueMessage] 매핑. */
private object ExecutionQueueMessageRowMapper : RowMapper<ExecutionQueueMessage> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): ExecutionQueueMessage =
        ExecutionQueueMessage(
            msgId = rs.getLong("msg_id"),
            readCt = rs.getInt("read_ct"),
            messageJson = rs.getString("message"),
        )
}

/**
 * `q_automation_execution` 메시지 payload 파싱 결과.
 *
 * @property ruleId 발화한 룰 id.
 * @property triggerEvent 발화를 유발한 원본 이벤트(이슈 이벤트/웹훅 본문/빈 객체).
 * @property executionDepth automation 직접 체인 깊이. payload 에 없으면 0(FR-AT-01 enqueuer 는 depth
 *   미설정 = 0).
 * @property issueKey [triggerEvent] 에서 추출한 대상 이슈 키. 없으면 `null`(SCHEDULED/WEBHOOK 등).
 */
private data class ExecutionPayload(
    val ruleId: UUID,
    val triggerEvent: JsonNode,
    val executionDepth: Int,
    val issueKey: String?,
)

/**
 * 루프 가드 (b) — (ruleId, issueKey) 재실행 억제 창.
 *
 * 최상위(top-level) 상수/함수로 둔 이유는 [AutomationExecutionWorker] 클래스의 detekt `TooManyFunctions`
 * 임계값을 지키기 위함이다 — 인스턴스 상태(`log`/`clock`/`recentExecutions`)에 의존하지 않는 순수
 * 헬퍼([cleanupExpired]/[suppressionKey]/[extractIssueKey])를 클래스 밖으로 분리했다.
 */
private const val SUPPRESSION_WINDOW_SECONDS = 60L
private val SUPPRESSION_WINDOW: Duration = Duration.ofSeconds(SUPPRESSION_WINDOW_SECONDS)

private const val FIELD_ISSUE_KEY = "issueKey"
private const val FIELD_ISSUE = "issue"
private const val FIELD_KEY = "key"

/** 억제 캐시에서 [SUPPRESSION_WINDOW] 를 넘긴 엔트리를 제거한다(무한 성장 방지, 접근 시 정리). */
private fun cleanupExpired(
    cache: ConcurrentHashMap<String, Instant>,
    now: Instant,
) {
    cache.entries.removeIf { (_, lastExecutedAt) -> Duration.between(lastExecutedAt, now) >= SUPPRESSION_WINDOW }
}

/** (ruleId, issueKey) 조합의 억제 캐시 키. */
private fun suppressionKey(
    ruleId: UUID,
    issueKey: String,
): String = "$ruleId:$issueKey"

/** [triggerEvent] 최상위 `issueKey` 또는 중첩 `issue.key` 에서 대상 이슈 키를 추출한다([ActionExecutor] 동형 규칙). */
private fun extractIssueKey(triggerEvent: JsonNode): String? {
    val direct = triggerEvent.path(FIELD_ISSUE_KEY).asText(null)
    if (!direct.isNullOrBlank()) return direct
    return triggerEvent.path(FIELD_ISSUE).path(FIELD_KEY).asText(null)?.takeIf { it.isNotBlank() }
}

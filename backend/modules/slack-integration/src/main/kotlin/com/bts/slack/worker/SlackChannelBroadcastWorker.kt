// q_slack_channel_broadcasts 를 폴링해 프로젝트 활동 이벤트를 매핑된 Slack 채널로 게시하는 워커 (FR-SL-06 PR-B Task 6)

package com.bts.slack.worker

import com.bts.shared.issue.IssueSecurityClassificationPort
import com.bts.slack.application.SlackChannelBroadcastDedupRepository
import com.bts.slack.application.SlackChannelMappingRepository
import com.bts.slack.domain.ChannelProjectMapping
import com.bts.slack.message.SlackBlockKitRenderer
import com.bts.slack.message.SlackMessageClient
import com.bts.slack.message.SlackSendResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * pgmq `q_slack_channel_broadcasts` 큐를 폴링하여 프로젝트 활동 이벤트를 매핑된 Slack 채널에 게시하는
 * 스케줄 워커 (FR-SL-06 PR-B Task 6).
 *
 * ## 처리 흐름
 * 1. `pgmq.read(queue, vt, qty)` 로 메시지 읽기 ([SlackDeliveryWorker] 골격 그대로 미러)
 * 2. JSON 파싱 실패 → poison 처리(read_ct > [MAX_RECEIVE_COUNT] 면 archive)
 * 3. **보안게이트(fan-out/render 이전)** — `issueKey`가 있으면 [IssueSecurityClassificationPort] 로
 *    보안등급 제한 여부를 확인한다. 제한 대상이거나 판정 자체가 실패하면(fail-closed) **전 채널 게시를
 *    통째로 건너뛴다**(유출 차단 P0). `issueKey`가 없으면(예: 스프린트 이벤트) 게이트를 우회한다.
 * 4. [SlackChannelMappingRepository.findByProjectKey] 로 프로젝트에 매핑된 채널을 조회하고
 *    `eventTypes`에 이벤트 유형이 없는 채널은 skip 한다.
 * 5. 남은 채널마다 **독립적으로** per-channel dedup(`dedupKey:channelId`) → 봇 토큰 해석 →
 *    `chat.postMessage`를 시도한다. 한 채널의 실패가 다른 채널 시도를 막지 않는다.
 * 6. 채널 순회가 끝난 뒤, 하나라도 [SlackSendResult.RetryableFailure] 였으면
 *    [RetryableDeliveryException] 을 던져 메시지를 보존(재전달)한다. 그 외(성공/영구실패/skip)만
 *    있었으면 정상 완료로 보아 메시지를 삭제한다.
 *
 * ## per-channel dedup — effectively-once (S8)
 * pgmq는 at-least-once 배달이라 같은 이벤트 메시지가 재전달될 수 있다. dedup key 는 이벤트레벨
 * `dedupKey`에 `channelId`를 결합해 만든다 — 한 이벤트가 여러 채널에 매핑되어도 채널별로 독립적으로
 * 게시 여부를 추적하고, 재전달된 메시지는 이미 게시된 채널을 다시 게시하지 않는다
 * ([SlackChannelBroadcastDedupRepository] KDoc 참고).
 *
 * ## @Transactional 없음 — 의도적 설계
 * [SlackDeliveryWorker] 와 동일: `pgmq.read` 의 vt 갱신은 트랜잭션 범위 밖에서 atomic 하다. 워커에
 * 트랜잭션을 걸면 self-invocation/rollback 오염 위험이 있다
 * (learnings: transaction-self-invocation-REQUIRES_NEW).
 *
 * ## BC 격리
 * notification BC 도메인 타입을 직접 import 하지 않는다. pgmq JSON 을 wire 포맷 그대로 파싱해 내부
 * [SlackChannelBroadcastEvent] 로 변환한다.
 *
 * ## 봇 토큰 비노출 (§1.1.2)
 * 봇 토큰은 [SlackBotTokenResolver] → [SlackMessageClient] 로만 흐르고 이 워커의 로그에는 dedupKey/
 * projectKey/channelId 등 비밀 아닌 진단 정보만 남긴다.
 *
 * @param jdbcTemplate pgmq raw SQL 실행. 문자열 결합 금지 — `?` 바인딩 사용(DATA.md §5).
 * @param objectMapper pgmq JSON 파싱용 Jackson.
 * @param mappingRepository 프로젝트→채널 매핑 조회.
 * @param dedupRepository per-channel dedup 존재확인/기록.
 * @param securityClassificationPort 보안등급 제한 판정 cross-BC 포트(fail-closed).
 * @param botTokenResolver teamId → 복호화된 봇 토큰.
 * @param renderer 제목/이슈 링크 Block Kit 렌더.
 * @param messageClient `chat.postMessage` 클라이언트.
 */
@Suppress("LongParameterList") // 발송 오케스트레이터 협력자 주입, 분리 불필요(SlackDeliveryWorker 동형)
@Component
class SlackChannelBroadcastWorker(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val mappingRepository: SlackChannelMappingRepository,
    private val dedupRepository: SlackChannelBroadcastDedupRepository,
    private val securityClassificationPort: IssueSecurityClassificationPort,
    private val botTokenResolver: SlackBotTokenResolver,
    private val renderer: SlackBlockKitRenderer,
    private val messageClient: SlackMessageClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * q_slack_channel_broadcasts 큐를 폴링하여 대기 중인 브로드캐스트를 게시한다. 메시지가 없으면
     * 즉시 반환한다.
     *
     * @Transactional 없음 — 의도적 설계 (KDoc 클래스 레벨 참조).
     */
    @Scheduled(fixedDelayString = "\${bts.slack.channel-broadcast.poll-interval-ms:1000}")
    fun pollAndProcess() {
        val messages =
            jdbcTemplate.queryForList(
                "SELECT msg_id, read_ct, message::text AS message FROM pgmq.read(?, ?, ?)",
                QUEUE_NAME,
                VT_SECONDS,
                BATCH_SIZE,
            )
        if (messages.isEmpty()) return

        for (row in messages) {
            val msgId = (row["msg_id"] as Number).toLong()
            val readCt = (row["read_ct"] as Number).toInt()
            val messageJson = row["message"] as String
            processMessage(msgId, messageJson, readCt)
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 단일 메시지를 처리한다. 파싱 실패는 poison 으로 다룬다.
     *
     * ## 배치 격리 ([SlackDeliveryWorker] 동형)
     * 발송(dispatch)과 성공 시 [deleteMessage] 를 **한 try 안**에 둔다. dispatch 재시도 실패(throw)나
     * delete 의 일시 오류가 for 루프 밖으로 전파돼 같은 배치의 나머지 메시지를 굶기지 않도록 per-message 로
     * 흡수한다(NFR4). 흡수된 메시지는 delete 되지 않으므로 vt 만료 후 재전달되고, read_ct > [MAX_RECEIVE_COUNT]
     * 면 archive(dead-letter) 한다.
     *
     * @param msgId pgmq 메시지 ID.
     * @param messageJson pgmq 메시지 JSON 문자열.
     * @param readCt pgmq 메시지 수신 횟수.
     */
    @Suppress("TooGenericExceptionCaught") // 재시도 실패·delete 일시오류를 per-message 흡수(배치 격리)
    private fun processMessage(
        msgId: Long,
        messageJson: String,
        readCt: Int,
    ) {
        val event =
            parse(messageJson, msgId) ?: run {
                handlePoison(msgId, readCt)
                return
            }

        try {
            dispatch(event)
            // dispatch 가 정상 반환하면 처리 완료(게시/skip/보안차단/영구실패) → 삭제.
            deleteMessage(msgId)
        } catch (e: Exception) {
            log.error("slack_channel_broadcast_processing_failed msgId={} error={}", msgId, e.message, e)
            // 재시도 가능(throw) → delete 안 함(vt 만료 재전달). 반복 실패는 dead-letter 로 수렴.
            if (readCt > MAX_RECEIVE_COUNT) archiveMessage(msgId, readCt)
        }
    }

    /**
     * 보안게이트 → 매핑/이벤트필터 → per-channel 게시를 오케스트레이션한다.
     *
     * 정상 반환 = 큐에서 삭제해도 되는 종료 상태(게시 완료/skip/보안차단/영구실패). 채널 중 하나라도
     * 재시도 가능한 실패였다면 [RetryableDeliveryException] 을 던져 [processMessage] 의 재전달 경로로
     * 넘긴다.
     *
     * @throws RetryableDeliveryException 채널 중 하나 이상이 429/5xx/네트워크 등 재시도 가능한 전송
     *   실패를 겪었을 때.
     */
    private fun dispatch(event: SlackChannelBroadcastEvent) {
        if (isSecurityRestricted(event)) return

        val mappings =
            mappingRepository.findByProjectKey(event.projectKey)
                .filter { it.eventTypes.contains(event.eventType) }
        if (mappings.isEmpty()) {
            log.info(
                "slack_channel_broadcast_skip_unmapped projectKey={} eventType={} dedupKey={}",
                event.projectKey,
                event.eventType,
                event.dedupKey,
            )
            return
        }

        // 채널마다 독립 시도 — 한 채널의 실패가 다른 채널 시도를 막지 않는다(map 은 eager 이므로 전 채널을
        // 순회한 뒤에야 any 로 재시도 필요 여부를 판단한다).
        val hasRetryableFailure = mappings.map { postToChannel(event, it) }.any { it }
        if (hasRetryableFailure) {
            throw RetryableDeliveryException("channel_broadcast_retry dedupKey=${event.dedupKey}")
        }
    }

    // ── 채널 처리 (보안게이트 + per-channel dedup/토큰/게시) ─────────────────────

    /**
     * `issueKey`가 있으면 보안등급 제한 여부를 판정한다(fan-out/render 이전 — 유출 차단 P0).
     *
     * 제한 대상이거나 판정 자체가 예외로 실패하면(fail-closed) `true`(스킵)를 반환한다. `issueKey`가
     * 없으면(예: 스프린트 이벤트) 게이트를 우회해 `false`를 반환한다.
     *
     * @return 브로드캐스트를 스킵해야 하면 `true`.
     */
    @Suppress("TooGenericExceptionCaught") // 판정 실패도 fail-closed 로 스킵 처리해야 하는 보안 게이트
    private fun isSecurityRestricted(event: SlackChannelBroadcastEvent): Boolean {
        val issueKey = event.issueKey ?: return false
        return try {
            val restricted = securityClassificationPort.isSecurityRestricted(issueKey)
            if (restricted) {
                log.info(
                    "slack_channel_broadcast_skip_restricted issueKey={} dedupKey={}",
                    issueKey,
                    event.dedupKey,
                )
            }
            restricted
        } catch (e: Exception) {
            log.error(
                "slack_channel_broadcast_security_check_failed issueKey={} dedupKey={} error={}",
                issueKey,
                event.dedupKey,
                e.message,
                e,
            )
            true
        }
    }

    /**
     * 단일 채널에 대해 per-channel dedup → 봇 토큰 해석 → `chat.postMessage`를 시도한다.
     *
     * @return 이 채널의 결과가 재시도 가능한 실패였으면 `true`, 그 외(게시 성공/영구실패/skip)는 `false`.
     */
    @Suppress("ReturnCount") // 단계별 skip 을 early return 으로 표현 (가독성, SlackDeliveryWorker.dispatch 동형)
    private fun postToChannel(
        event: SlackChannelBroadcastEvent,
        mapping: ChannelProjectMapping,
    ): Boolean {
        val perChannelKey = "${event.dedupKey}:${mapping.channelId}"
        if (dedupRepository.existsPosted(perChannelKey)) {
            log.info("slack_channel_broadcast_skip_duplicate perChannelKey={}", perChannelKey)
            return false
        }
        val botToken = botTokenResolver.resolve(mapping.teamId)
        if (botToken == null) {
            log.warn(
                "slack_channel_broadcast_skip_no_install teamId={} channelId={} perChannelKey={}",
                mapping.teamId,
                mapping.channelId,
                perChannelKey,
            )
            return false
        }

        val rendered = renderer.render(event.title, event.issueKey)
        return when (val result = messageClient.postChannelMessage(botToken, mapping.channelId, rendered)) {
            is SlackSendResult.Sent -> {
                dedupRepository.recordPosted(perChannelKey)
                log.info("slack_channel_broadcast_posted perChannelKey={}", perChannelKey)
                false
            }
            is SlackSendResult.PermanentFailure -> {
                log.warn(
                    "slack_channel_broadcast_permanent_failure perChannelKey={} reason={}",
                    perChannelKey,
                    result.reason,
                )
                false
            }
            is SlackSendResult.RetryableFailure -> {
                log.warn(
                    "slack_channel_broadcast_retryable_failure perChannelKey={} reason={}",
                    perChannelKey,
                    result.reason,
                )
                true
            }
        }
    }

    // ── pgmq 생명주기 (파싱/poison/삭제/archive) ─────────────────────────────────

    /** pgmq JSON wire 포맷을 [SlackChannelBroadcastEvent] 로 파싱한다. 필수 필드 부재/형식 오류 시 null(poison). */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun parse(
        messageJson: String,
        msgId: Long,
    ): SlackChannelBroadcastEvent? {
        val node =
            try {
                objectMapper.readTree(messageJson)
            } catch (e: Exception) {
                log.error("slack_channel_broadcast_json_parse_failed msgId={} error={}", msgId, e.message)
                return null
            }

        val projectKey = node.path("projectKey").asText("")
        val eventType = node.path("eventType").asText("")
        val title = node.path("title").asText("")
        val dedupKey = node.path("dedupKey").asText("")
        val requiredFields =
            mapOf("projectKey" to projectKey, "eventType" to eventType, "title" to title, "dedupKey" to dedupKey)
        val blankFields = requiredFields.filterValues { it.isBlank() }.keys
        if (blankFields.isNotEmpty()) {
            log.error("slack_channel_broadcast_missing_fields msgId={} blankFields={}", msgId, blankFields)
            return null
        }

        val issueKey = node.path("issueKey").asText("").takeIf { it.isNotBlank() }
        return SlackChannelBroadcastEvent(projectKey, eventType, issueKey, title, dedupKey)
    }

    /** poison 메시지 처리 — read_ct > MAX 면 archive(dead-letter), 미만이면 재전달 대기. */
    private fun handlePoison(
        msgId: Long,
        readCt: Int,
    ) {
        if (readCt > MAX_RECEIVE_COUNT) {
            archiveMessage(msgId, readCt)
        } else {
            log.warn(
                "slack_channel_broadcast_poison_retry msgId={} readCt={} maxReceiveCount={}",
                msgId,
                readCt,
                MAX_RECEIVE_COUNT,
            )
        }
    }

    /** 처리 완료 후 pgmq 에서 메시지를 삭제한다. */
    private fun deleteMessage(msgId: Long) {
        jdbcTemplate.queryForObject("SELECT pgmq.delete(?, ?)", Boolean::class.java, QUEUE_NAME, msgId)
        log.debug("slack_channel_broadcast_deleted msgId={}", msgId)
    }

    /** pgmq.archive 로 dead-letter 처리한다. */
    private fun archiveMessage(
        msgId: Long,
        readCt: Int,
    ) {
        log.error("slack_channel_broadcast_dead_letter msgId={} readCt={} action=archive", msgId, readCt)
        jdbcTemplate.queryForObject("SELECT pgmq.archive(?, ?)", Boolean::class.java, QUEUE_NAME, msgId)
    }

    /** notification SlackChannelBroadcaster 가 발행한 pgmq wire JSON 의 내부 표현. */
    private data class SlackChannelBroadcastEvent(
        val projectKey: String,
        val eventType: String,
        val issueKey: String?,
        val title: String,
        val dedupKey: String,
    )

    /**
     * 재시도 가능한 전송 실패를 [processMessage] 의 재전달 경로로 넘기는 내부 신호 예외.
     *
     * [SlackDeliveryWorker.RetryableDeliveryException] 은 private 이라 재사용할 수 없어 이 워커 전용으로
     * 별도 정의한다. [message] 은 비밀값을 담지 않는 진단 문자열이다.
     */
    private class RetryableDeliveryException(message: String) : RuntimeException(message)

    companion object {
        /** notification SlackChannelBroadcaster 가 발행하는 큐 이름과 일치해야 한다(V705 에서 생성). */
        const val QUEUE_NAME = "q_slack_channel_broadcasts"

        /**
         * pgmq visibility timeout (초).
         *
         * [SlackDeliveryWorker] 와 동일 근거: read timeout × BATCH 처리 시간이 VT 를 넘으면 처리 중
         * 재전달로 중복 발송 위험. VT=60s 로 여유 확보.
         */
        const val VT_SECONDS = 60

        /** pgmq.read 1회 폴링 최대 메시지 수 ([SlackDeliveryWorker] 동형). */
        const val BATCH_SIZE = 5

        /** poison/재시도 메시지 최대 수신 허용 횟수 — 초과 시 archive(dead-letter). */
        const val MAX_RECEIVE_COUNT = 5
    }
}

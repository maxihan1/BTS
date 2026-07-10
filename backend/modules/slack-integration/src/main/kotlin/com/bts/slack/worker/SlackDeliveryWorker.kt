// q_slack_deliveries 를 폴링해 매핑/토큰/dedup 해석 후 Slack DM 을 발송하는 워커 (FR-SL-02 Task 7)

package com.bts.slack.worker

import com.bts.slack.application.SlackDeliveryLogRepository
import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.message.SlackBlockKitRenderer
import com.bts.slack.message.SlackMessageClient
import com.bts.slack.message.SlackSendResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * pgmq `q_slack_deliveries` 큐를 폴링하여 Slack DM 알림을 발송하는 스케줄 워커 (FR-SL-02 Task 7).
 *
 * ## 처리 흐름 (스펙 FR4~FR7)
 * 1. `pgmq.read(queue, vt, qty)` 로 메시지 읽기
 * 2. JSON 파싱 실패 → poison 처리(read_ct > [MAX_RECEIVE_COUNT] 면 archive)
 * 3. 수신자 매핑 조회 → 없으면 delete(skip, 발송 대상 아님)
 * 4. dedupKey 이미 전송됨 → delete(중복 발송 차단)
 * 5. 봇 토큰 해석 → 설치 없으면 delete(발송 불가)
 * 6. 렌더 + `chat.postMessage` 발송 → 결과별 큐 생명주기 결정
 *    - [SlackSendResult.Sent] → dedup 기록 **후** delete
 *    - [SlackSendResult.PermanentFailure] → delete(재시도 무의미)
 *    - [SlackSendResult.RetryableFailure] → delete 안 함(vt 만료 재전달). read_ct > MAX 면 archive
 *
 * ## dedup 기록 순서 (B4 회귀 방어)
 * dedup 은 **전송 성공 이후에만** 기록한다. 전송 전에 기록하면 전송 실패 시 재시도가 dedup 에 막혀
 * 알림이 영영 유실된다. exists→send→record 순서로 at-least-once 큐 위에서 effectively-once 를 달성한다.
 *
 * ## @Transactional 없음 — 의도적 설계
 * notification [com.bts.notification.worker.WebhookDispatchWorker] 와 동일: `pgmq.read` 의 vt 갱신은
 * 트랜잭션 범위 밖에서 atomic 하다. 워커에 트랜잭션을 걸면 self-invocation/rollback 오염 위험이 있다
 * (learnings: transaction-self-invocation-REQUIRES_NEW).
 *
 * ## BC 격리
 * notification/issue-tracking BC 도메인 타입을 직접 import 하지 않는다. pgmq JSON 을 wire 포맷 그대로
 * 파싱해 내부 [SlackDeliveryEvent] 로 변환한다.
 *
 * ## 봇 토큰 비노출 (§1.1.2)
 * 봇 토큰은 [SlackBotTokenResolver] → [SlackMessageClient] 로만 흐르고 이 워커의 로그에는 dedupKey/teamId
 * 등 비밀 아닌 진단 정보만 남긴다.
 *
 * @param jdbcTemplate pgmq raw SQL 실행. 문자열 결합 금지 — `?` 바인딩 사용(DATA.md §5).
 * @param objectMapper pgmq JSON 파싱용 Jackson.
 * @param mappingRepository 수신자 BTS→Slack 매핑 조회.
 * @param deliveryLogRepository dedup 존재확인/기록.
 * @param botTokenResolver teamId → 복호화된 봇 토큰.
 * @param renderer 제목/이슈 링크 Block Kit 렌더.
 * @param messageClient `chat.postMessage` 클라이언트.
 */
@Suppress("LongParameterList") // 발송 오케스트레이터 협력자 주입, 분리 불필요(WebhookDispatchWorker 동형)
@Component
class SlackDeliveryWorker(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val mappingRepository: SlackUserMappingRepository,
    private val deliveryLogRepository: SlackDeliveryLogRepository,
    private val botTokenResolver: SlackBotTokenResolver,
    private val renderer: SlackBlockKitRenderer,
    private val messageClient: SlackMessageClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * q_slack_deliveries 큐를 폴링하여 대기 중인 알림을 발송한다. 메시지가 없으면 즉시 반환한다.
     *
     * @Transactional 없음 — 의도적 설계 (KDoc 클래스 레벨 참조).
     */
    @Scheduled(fixedDelayString = "\${bts.slack.delivery.poll-interval-ms:1000}")
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
     * ## 배치 격리 (NotificationWorker 동형)
     * 발송(dispatch)과 성공 시 [deleteMessage] 를 **한 try 안**에 둔다. dispatch 재시도 실패(throw)나
     * delete 의 일시 오류가 for 루프 밖으로 전파돼 같은 배치의 나머지 메시지를 굶기지 않도록 per-message 로
     * 흡수한다. 흡수된 메시지는 delete 되지 않으므로 vt 만료 후 재전달되고, read_ct > [MAX_RECEIVE_COUNT] 면
     * archive(dead-letter) 한다.
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
            // dispatch 가 정상 반환하면 처리 완료(발송/skip/영구실패) → 삭제.
            deleteMessage(msgId)
        } catch (e: Exception) {
            log.error("slack_delivery_processing_failed msgId={} error={}", msgId, e.message, e)
            // 재시도 가능(throw) → delete 안 함(vt 만료 재전달). 반복 실패는 dead-letter 로 수렴.
            if (readCt > MAX_RECEIVE_COUNT) archiveMessage(msgId, readCt)
        }
    }

    /**
     * 매핑/dedup/토큰 해석 후 발송한다.
     *
     * 정상 반환 = 큐에서 삭제해도 되는 종료 상태(발송 완료/skip/영구실패). 재시도 가능한 실패는
     * [RetryableDeliveryException] 을 던져 [processMessage] 의 재전달 경로로 넘긴다(NotificationWorker 가
     * 재시도를 예외로 표현하는 것과 동형).
     *
     * @throws RetryableDeliveryException 429/5xx/네트워크 등 재시도 가능한 전송 실패 시.
     */
    @Suppress("ReturnCount") // 단계별 skip 을 early return 으로 표현 (가독성)
    private fun dispatch(event: SlackDeliveryEvent) {
        val mapping = mappingRepository.findByUserId(event.recipientUserId)
        if (mapping == null) {
            log.info(
                "slack_delivery_skip_unmapped recipientUserId={} dedupKey={}",
                event.recipientUserId,
                event.dedupKey,
            )
            return
        }
        if (deliveryLogRepository.exists(event.dedupKey)) {
            log.info("slack_delivery_skip_duplicate dedupKey={}", event.dedupKey)
            return
        }
        val botToken = botTokenResolver.resolve(mapping.teamId)
        if (botToken == null) {
            log.warn("slack_delivery_skip_no_install teamId={} dedupKey={}", mapping.teamId, event.dedupKey)
            return
        }

        val rendered = renderer.render(event.title, event.issueKey)
        when (val result = messageClient.postDirectMessage(botToken, mapping.slackUserId, rendered)) {
            is SlackSendResult.Sent -> {
                // B4: 전송 성공 이후에만 dedup 을 박제한다(전송 실패가 dedup 되어 유실되는 것 방지).
                deliveryLogRepository.record(event.dedupKey)
                log.info("slack_delivery_sent dedupKey={}", event.dedupKey)
            }
            is SlackSendResult.PermanentFailure ->
                log.warn("slack_delivery_permanent_failure dedupKey={} reason={}", event.dedupKey, result.reason)
            is SlackSendResult.RetryableFailure -> {
                log.warn("slack_delivery_retryable_failure dedupKey={} reason={}", event.dedupKey, result.reason)
                throw RetryableDeliveryException(result.reason)
            }
        }
    }

    /** pgmq JSON wire 포맷을 [SlackDeliveryEvent] 로 파싱한다. 필수 필드 부재/형식 오류 시 null(poison). */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun parse(
        messageJson: String,
        msgId: Long,
    ): SlackDeliveryEvent? {
        val node =
            try {
                objectMapper.readTree(messageJson)
            } catch (e: Exception) {
                log.error("slack_delivery_json_parse_failed msgId={} error={}", msgId, e.message)
                return null
            }

        val recipientRaw = node.path("recipientUserId").asText("")
        val recipientUserId =
            try {
                UUID.fromString(recipientRaw)
            } catch (e: IllegalArgumentException) {
                log.error("slack_delivery_invalid_recipient msgId={} error={}", msgId, e.message)
                return null
            }

        val dedupKey = node.path("dedupKey").asText("")
        val title = node.path("title").asText("")
        if (dedupKey.isBlank() || title.isBlank()) {
            log.error(
                "slack_delivery_missing_fields msgId={} dedupKeyBlank={} titleBlank={}",
                msgId,
                dedupKey.isBlank(),
                title.isBlank(),
            )
            return null
        }

        val issueKey = node.path("issueKey").asText("").takeIf { it.isNotBlank() }
        val eventType = node.path("eventType").asText("")
        return SlackDeliveryEvent(recipientUserId, eventType, issueKey, title, dedupKey)
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
                "slack_delivery_poison_retry msgId={} readCt={} maxReceiveCount={}",
                msgId,
                readCt,
                MAX_RECEIVE_COUNT,
            )
        }
    }

    /** 처리 완료 후 pgmq 에서 메시지를 삭제한다. */
    private fun deleteMessage(msgId: Long) {
        jdbcTemplate.queryForObject("SELECT pgmq.delete(?, ?)", Boolean::class.java, QUEUE_NAME, msgId)
        log.debug("slack_delivery_deleted msgId={}", msgId)
    }

    /** pgmq.archive 로 dead-letter 처리한다. */
    private fun archiveMessage(
        msgId: Long,
        readCt: Int,
    ) {
        log.error("slack_delivery_dead_letter msgId={} readCt={} action=archive", msgId, readCt)
        jdbcTemplate.queryForObject("SELECT pgmq.archive(?, ?)", Boolean::class.java, QUEUE_NAME, msgId)
    }

    /** notification SlackChannelSender 가 발행한 pgmq wire JSON 의 내부 표현. */
    private data class SlackDeliveryEvent(
        val recipientUserId: UUID,
        val eventType: String,
        val issueKey: String?,
        val title: String,
        val dedupKey: String,
    )

    /**
     * 재시도 가능한 전송 실패를 [processMessage] 의 재전달 경로로 넘기는 내부 신호 예외.
     *
     * [reason] 은 비밀값을 담지 않는 진단 문자열([SlackSendResult.RetryableFailure.reason]) 이다.
     */
    private class RetryableDeliveryException(reason: String) : RuntimeException(reason)

    companion object {
        /** notification SlackChannelSender 가 발행하는 큐 이름과 일치해야 한다(V701 에서 생성). */
        const val QUEUE_NAME = "q_slack_deliveries"

        /**
         * pgmq visibility timeout (초).
         *
         * WebhookDispatchWorker(60s) 동일 근거: read timeout × BATCH 처리 시간이 VT 를 넘으면 처리 중
         * 재전달로 중복 발송 위험. VT=60s 로 여유 확보.
         */
        const val VT_SECONDS = 60

        /** pgmq.read 1회 폴링 최대 메시지 수 (WebhookDispatchWorker 동형). */
        const val BATCH_SIZE = 5

        /** poison/재시도 메시지 최대 수신 허용 횟수 — 초과 시 archive(dead-letter). */
        const val MAX_RECEIVE_COUNT = 5
    }
}

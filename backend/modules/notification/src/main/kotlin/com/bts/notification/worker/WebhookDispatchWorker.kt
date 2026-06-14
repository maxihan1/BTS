// q_transition_events를 폴링해 WebhookRequested를 외부 URL로 디스패치하는 워커

package com.bts.notification.worker

import com.bts.notification.webhook.WebhookDispatchResult
import com.bts.notification.webhook.WebhookDispatcher
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * pgmq `q_transition_events` 큐를 폴링하여 `WebhookRequested` 메시지를 외부 URL로 전송하는 스케줄 워커.
 *
 * ## 처리 흐름
 * 1. `pgmq.read(queue, vt, qty)` 로 메시지 읽기
 * 2. JSON type 필드 확인 → `WebhookRequested` 가 아니면 delete(ack, 무한 재전달 차단)
 * 3. payload.url / method / issueKey 파싱 → [WebhookDispatcher.dispatch] 호출
 * 4. [WebhookDispatchResult.Sent] → `pgmq.delete`
 * 5. [WebhookDispatchResult.Rejected] → `pgmq.delete` (영구 거부, 재시도 무의미)
 * 6. [WebhookDispatchResult.Failed] → delete 안 함(vt 만료 재전달, at-least-once). read_ct > [MAX_RECEIVE_COUNT] 이면 `pgmq.archive`(dead-letter)
 *
 * ## dead-letter (poison 메시지)
 * JSON 파싱 실패 또는 처리 중 예외 → read_ct > [MAX_RECEIVE_COUNT] 면 archive.
 *
 * ## @Transactional 없음 — 의도적 설계
 * [NotificationWorker] 와 동일한 이유: pgmq.read 는 트랜잭션 범위 밖에서 호출해도 vt 가 atomic 하게 갱신된다.
 * (learnings: transaction-self-invocation-REQUIRES_NEW)
 *
 * ## BC 격리
 * issue-tracking BC 의 도메인 타입을 직접 import 하지 않는다.
 * pgmq JSON 을 wire 포맷 그대로 파싱해 내부 표현으로 변환한다.
 *
 * ## VT_SECONDS = 60 / BATCH_SIZE = 5 (E4 리뷰 반영)
 * [NotificationWorker](VT=30, BATCH=10) 값을 그대로 차용 금지.
 * read timeout(5s) × BATCH_SIZE(5) = 25s < VT(60s) 이므로 처리 중 VT 만료로 인한 중복 POST 방지.
 *
 * @param dsl jOOQ [DSLContext]. pgmq raw SQL 실행. 문자열 결합 금지 — ? 바인딩 사용.
 * @param dispatcher Webhook URL 로 HTTP 요청을 전송하는 디스패처
 * @param objectMapper JSON 파싱용 Jackson ObjectMapper
 */
@Suppress("TooManyFunctions") // pgmq 워커 처리 흐름 헬퍼(parse/dispatch/delete/archive 등) 포함, 역할 명확해 분리 불필요
@Component
class WebhookDispatchWorker(
    private val dsl: DSLContext,
    private val dispatcher: WebhookDispatcher,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * q_transition_events 큐를 폴링하여 대기 중인 WebhookRequested 메시지를 처리한다.
     *
     * 메시지가 없으면 즉시 반환한다.
     * @Transactional 없음 — 의도적 설계 (KDoc 클래스 레벨 참조).
     */
    @Scheduled(fixedDelayString = "\${bts.notification.webhook.poll-interval-ms:500}")
    fun pollAndProcess() {
        val messages =
            dsl.fetch(
                "SELECT * FROM pgmq.read(?, ?, ?)",
                QUEUE_NAME,
                VT_SECONDS,
                BATCH_SIZE,
            )

        if (messages.isEmpty()) return

        for (record in messages) {
            val msgId = record.get("msg_id", Long::class.java)
            val messageJson = record.get("message", String::class.java)
            val readCt = record.get("read_ct", Int::class.java) ?: 1
            processMessage(msgId, messageJson, readCt)
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 단일 메시지를 처리한다.
     *
     * JSON 파싱 실패 또는 처리 중 예외 시 로그를 남기고 delete 를 건너뛴다 (재전달 허용).
     * read_ct > [MAX_RECEIVE_COUNT] 면 archive (dead-letter).
     *
     * @param msgId pgmq 메시지 ID
     * @param messageJson pgmq 메시지 JSON 문자열
     * @param readCt pgmq 메시지 수신 횟수
     */
    @Suppress("TooGenericExceptionCaught")
    private fun processMessage(
        msgId: Long,
        messageJson: String,
        readCt: Int,
    ) {
        val node =
            parseJson(messageJson, msgId) ?: run {
                handlePoison(msgId, readCt)
                return
            }

        val typeStr = node.path("type").asText("")

        if (typeStr != SUPPORTED_TYPE) {
            log.info("webhook_worker_unsupported_type msgId={} type={} action=delete", msgId, typeStr)
            deleteMessage(msgId)
            return
        }

        val payload = node.path("payload")
        val url = payload.path("url").asText(null)
        val method = payload.path("method").asText("POST")
        val issueKey = payload.path("issueKey").asText("")

        if (url.isNullOrBlank()) {
            log.warn("webhook_worker_missing_url msgId={} action=delete", msgId)
            deleteMessage(msgId)
            return
        }

        log.info("webhook_worker_received msgId={} url_host={} issueKey={} readCt={}", msgId, extractHost(url), issueKey, readCt)

        try {
            val result = dispatcher.dispatch(url, method, issueKey)
            handleDispatchResult(result, msgId, readCt)
        } catch (e: Exception) {
            log.error(
                "webhook_worker_processing_failed msgId={} error={}",
                msgId,
                e.message,
                e,
            )
            if (readCt > MAX_RECEIVE_COUNT) {
                archiveMessage(msgId, readCt)
            }
        }
    }

    /**
     * [WebhookDispatchResult] 에 따라 pgmq 생명주기 동작을 결정한다.
     *
     * - [WebhookDispatchResult.Sent] → delete
     * - [WebhookDispatchResult.Rejected] → delete (영구 거부)
     * - [WebhookDispatchResult.Failed] → delete 안 함 (재전달). read_ct > MAX 이면 archive.
     *
     * @param result 디스패처 반환 결과
     * @param msgId pgmq 메시지 ID
     * @param readCt 수신 횟수
     */
    private fun handleDispatchResult(
        result: WebhookDispatchResult,
        msgId: Long,
        readCt: Int,
    ) {
        when (result) {
            is WebhookDispatchResult.Sent -> {
                log.info("webhook_worker_sent msgId={}", msgId)
                deleteMessage(msgId)
            }
            is WebhookDispatchResult.Rejected -> {
                log.warn("webhook_worker_rejected msgId={} reason={}", msgId, result.reason)
                deleteMessage(msgId)
            }
            is WebhookDispatchResult.Failed -> {
                log.warn("webhook_worker_failed msgId={} reason={} readCt={}", msgId, result.reason, readCt)
                if (readCt > MAX_RECEIVE_COUNT) {
                    archiveMessage(msgId, readCt)
                }
                // delete 하지 않음 — vt 만료 후 재전달 (at-least-once)
            }
        }
    }

    /** poison 메시지를 처리한다. read_ct > MAX 이면 archive, 미만이면 재전달 대기. */
    private fun handlePoison(
        msgId: Long,
        readCt: Int,
    ) {
        if (readCt > MAX_RECEIVE_COUNT) {
            archiveMessage(msgId, readCt)
        } else {
            log.warn(
                "webhook_worker_poison_retry msgId={} readCt={} maxReceiveCount={}",
                msgId,
                readCt,
                MAX_RECEIVE_COUNT,
            )
        }
    }

    /** pgmq.archive 로 dead-letter 처리한다. */
    private fun archiveMessage(
        msgId: Long,
        readCt: Int,
    ) {
        log.error("webhook_worker_dead_letter msgId={} readCt={} action=archive", msgId, readCt)
        dsl.execute("SELECT pgmq.archive(?, ?)", QUEUE_NAME, msgId)
    }

    /** 처리 완료 후 pgmq 에서 메시지를 삭제한다. */
    private fun deleteMessage(msgId: Long) {
        dsl.execute("SELECT pgmq.delete(?, ?)", QUEUE_NAME, msgId)
        log.debug("webhook_worker_deleted msgId={}", msgId)
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
            log.error("webhook_worker_json_parse_failed msgId={} error={}", msgId, e.message)
            null
        }

    /** URL 에서 호스트만 추출한다. 실패 시 "(unknown)" 반환. */
    private fun extractHost(url: String): String =
        runCatching {
            java.net.URI(url).host ?: "(unknown)"
        }.getOrElse { "(unknown)" }

    companion object {
        /**
         * pgmq 전이 이벤트 큐 이름 — PR1 V022 마이그레이션에서 생성된 큐와 일치해야 한다.
         *
         * ## 유일 소비자
         * [NotificationWorker] 의 `q_issue_events` 와 달리 이 큐는 [WebhookDispatchWorker] 만 소비한다.
         * 경쟁 소비 문제 없음.
         */
        const val QUEUE_NAME = "q_transition_events"

        /**
         * pgmq visibility timeout (초).
         *
         * ## NotificationWorker(30s)와 다른 이유 (E4 리뷰)
         * read timeout(5s) × [BATCH_SIZE](5) = 25s.
         * VT 가 25s 이하이면 처리 중 VT 만료 → 같은 메시지 재전달 → 중복 POST 위험.
         * VT=60s 로 여유 확보 (25s << 60s).
         */
        const val VT_SECONDS = 60

        /**
         * pgmq.read 1회 폴링에서 읽어올 최대 메시지 수.
         *
         * read timeout(5s) × BATCH_SIZE(5) = 25s < [VT_SECONDS](60s).
         * [NotificationWorker] 의 BATCH_SIZE(10)보다 작게 설정해 VT 초과를 방지한다.
         */
        const val BATCH_SIZE = 5

        /**
         * poison 메시지 최대 수신 허용 횟수.
         *
         * read_ct 가 이 값을 초과하면 `pgmq.archive` 로 dead-letter 처리한다.
         */
        const val MAX_RECEIVE_COUNT = 5

        /** 처리 대상 메시지 type 값. */
        private const val SUPPORTED_TYPE = "WebhookRequested"
    }
}

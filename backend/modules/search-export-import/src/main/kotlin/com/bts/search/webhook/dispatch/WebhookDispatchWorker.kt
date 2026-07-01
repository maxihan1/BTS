// q_webhook_events 큐를 폴링해 구독 매칭 fanout + HMAC 서명 발송 + 발송 이력 + circuit breaker 를 처리하는 워커
package com.bts.search.webhook.dispatch

import com.bts.search.webhook.application.OutboundWebhookRepository
import com.bts.search.webhook.application.WebhookDeliveryRepository
import com.bts.search.webhook.domain.DeliveryStatus
import com.bts.search.webhook.domain.OutboundWebhook
import com.bts.search.webhook.domain.WebhookEventCatalog
import com.bts.shared.crypto.SecretEncryptor
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * `q_webhook_events` pgmq 큐를 폴링해 구독형 아웃바운드 Webhook 을 fanout 발송하는 스케줄 워커
 * (FR-API-03 PR3 Task 8).
 *
 * ## 처리 흐름
 * 1. `pgmq.read(queue, vt, qty)` 로 메시지 읽기([BATCH_SIZE]=1 — fanout VT 초과 방지, spec §8 C1)
 * 2. wire JSON 파싱 — issue-tracking 도메인 타입을 직접 import 하지 않고 `"type"`/필드를 텍스트로만
 *    읽는다(BC 격리). `"type"` 이 [WebhookEventCatalog.PUBLISHABLE] 밖이면(방어, EC8) delete 후 종료.
 * 3. 이벤트별 화이트리스트 data 구성(spec §6 C3) — `issue.created`={issueKey,projectKey,summary},
 *    `issue.transitioned`={issueKey,projectKey,fromState,toState}(projectKey 는 issueKey 에서
 *    파싱, EC1). 내부 VO(`ActorId` 등)·사용자 UUID 는 절대 포함하지 않는다.
 * 4. [OutboundWebhookRepository.findMatching] 으로 매칭 구독 조회 → 구독마다 순차 발송(fanout).
 *    - circuit [WebhookCircuitBreaker.isOpen] 이면 스킵 — 발송/이력 기록 없음(EC5).
 *    - secret 이 있으면 [SecretEncryptor.decrypt] 로 평문 복원. 복호화 실패([IllegalStateException])는
 *      이 구독만 FAILED 로 기록하고 circuit 카운터에 반영한 뒤 다음 구독으로 계속한다(C7) — 한 구독의
 *      암호화 키/데이터 문제로 전체 fanout 배치가 죽지 않는다. 평문/예외 메시지는 로그에 남기지 않는다.
 *    - [SearchWebhookDispatcher.dispatch] 로 서명·발송 → 결과에 따라 [WebhookDeliveryRepository.record] +
 *      circuit [WebhookCircuitBreaker.recordSuccess]/[WebhookCircuitBreaker.recordFailure].
 * 5. 매칭된 모든 구독 처리(성공/실패 무관)가 끝나면 `pgmq.delete` — 부분 실패해도 메시지는 삭제한다
 *    (EC4, 성공분 중복 발송 방지). 처리 자체가 예외로 중단되면 삭제하지 않아 vt 만료 후 재전달된다
 *    (at-least-once). `read_ct > [MAX_RECEIVE_COUNT]` 면 `pgmq.archive`(dead-letter, poison 메시지).
 *
 * ## 안정 멱등키(B1)
 * [WebhookDeliveryRepository.record] 의 내부 PK 와 달리, 외부에 노출하는 발송 멱등키
 * (`X-BTS-Delivery` 헤더/body `deliveryId`)는 `UUID.nameUUIDFromBytes("webhookId:msgId")` 로 계산하는
 * 결정적 값이다. 같은 메시지가 pgmq vt 만료로 재전달되어도 `msgId` 가 같으므로 재전달된 fanout 은
 * 동일한 `deliveryId` 를 생성해, 수신자가 이 값으로 중복 수신을 걸러낼 수 있다(at-least-once + 수신자
 * 멱등, ADR). [attemptCount][dispatchToSubscription] 는 이 메시지의 pgmq `read_ct` 를 그대로 사용한다 —
 * 재전달마다 fanout 전체가 다시 시도되므로 "이 이벤트에 대한 몇 번째 시도인가"를 메시지 단위로 나타낸다.
 *
 * ## @Transactional 없음 — 의도적 설계
 * `com.bts.notification.worker.WebhookDispatchWorker` 와 동일한 이유: `pgmq.read` 는 트랜잭션 범위
 * 밖에서 호출해도 vt 가 atomic 하게 갱신된다. 이 클래스는 self-invocation 이 없어 프록시 우회 문제와도
 * 무관하다.
 *
 * ## BC 격리
 * issue-tracking BC 의 도메인 타입(`IssueDomainEvent` 등)을 직접 import 하지 않는다. pgmq JSON 을
 * wire 포맷 그대로 파싱해 내부 표현으로 변환한다.
 *
 * @param dsl jOOQ [DSLContext]. pgmq raw SQL 실행. 문자열 결합 금지 — ? 바인딩 사용.
 * @param objectMapper wire JSON 파싱 및 발송 payload 직렬화에 사용하는 Jackson [ObjectMapper].
 * @param outboundWebhookRepository 이벤트·프로젝트 매칭 구독 조회 포트.
 * @param webhookDeliveryRepository 발송 이력 기록 포트.
 * @param circuitBreaker 구독별 연속 실패 circuit breaker.
 * @param dispatcher SSRF 재검증 + HMAC 서명 + HTTP 발송을 담당하는 디스패처.
 * @param secretEncryptor webhook 전용 키로 구성된 secret 복호화 유틸(`webhookSecretEncryptor` 빈).
 */
@Suppress("TooManyFunctions", "LongParameterList") // pgmq 워커 처리 흐름 헬퍼(parse/payload/fanout/lifecycle) 포함, 역할 명확해 분리 불필요
@Component
class WebhookDispatchWorker(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val outboundWebhookRepository: OutboundWebhookRepository,
    private val webhookDeliveryRepository: WebhookDeliveryRepository,
    private val circuitBreaker: WebhookCircuitBreaker,
    private val dispatcher: SearchWebhookDispatcher,
    @Qualifier("webhookSecretEncryptor") private val secretEncryptor: SecretEncryptor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * q_webhook_events 큐를 폴링하여 대기 중인 이벤트 메시지를 fanout 발송한다.
     *
     * 메시지가 없으면 즉시 반환한다. @Transactional 없음 — 클래스 KDoc 참조.
     */
    @Scheduled(fixedDelayString = "\${bts.search.webhook.poll-interval-ms:500}")
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
     * 단일 메시지를 처리한다 — 파싱 → 화이트리스트 이벤트 판별 → fanout → 처리 완료 시 delete.
     *
     * 파싱 실패(poison)는 [handlePoison] 으로 위임한다. fanout 도중 예외가 발생하면 로그만 남기고
     * delete 를 생략한다(재전달 허용). `read_ct > [MAX_RECEIVE_COUNT]` 면 archive.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // early return 구조가 로직을 명확하게 함(notification WebhookDispatchWorker 동일 패턴)
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

        val parsed = extractPublishableEvent(node)
        if (parsed == null) {
            log.info(
                "webhook_dispatch_worker_non_publishable msgId={} type={} action=delete",
                msgId,
                node.path("type").asText(""),
            )
            deleteMessage(msgId)
            return
        }

        try {
            fanout(msgId, parsed, readCt)
            deleteMessage(msgId)
        } catch (e: Exception) {
            log.error("webhook_dispatch_worker_processing_failed msgId={} error={}", msgId, e.message, e)
            if (readCt > MAX_RECEIVE_COUNT) archiveMessage(msgId, readCt)
        }
    }

    /**
     * [parsed] 이벤트에 매칭되는 구독을 조회해 각 구독마다 순차 발송한다.
     *
     * 매칭 구독이 없으면(EC3) 아무 발송도 하지 않는다 — 호출자([processMessage])가 이어서 delete 한다.
     */
    private fun fanout(
        msgId: Long,
        parsed: ParsedEvent,
        attemptCount: Int,
    ) {
        val matches = outboundWebhookRepository.findMatching(parsed.eventType, parsed.projectKey)
        for (webhook in matches) {
            dispatchToSubscription(msgId, webhook, parsed, attemptCount)
        }
    }

    /**
     * 구독 1건에 발송을 시도한다 — circuit OPEN 스킵 → secret 복호화 → 서명·발송 → 이력 기록 + circuit 갱신.
     *
     * @param attemptCount 이 메시지의 pgmq `read_ct`(발송 이력의 시도 순번으로 그대로 사용한다).
     */
    @Suppress("ReturnCount") // early return 구조(방어적 스킵/circuit 스킵/복호화 실패 스킵)가 로직을 명확하게 함
    private fun dispatchToSubscription(
        msgId: Long,
        webhook: OutboundWebhook,
        parsed: ParsedEvent,
        attemptCount: Int,
    ) {
        val webhookId = webhook.id ?: return // 영속된 구독은 항상 id 有 — 방어적 스킵
        if (circuitBreaker.isOpen(webhookId)) {
            log.info("webhook_dispatch_worker_circuit_open webhookId={} event={} action=skip", webhookId, parsed.eventType)
            return
        }

        val secret =
            try {
                webhook.secretEncrypted?.let { secretEncryptor.decrypt(it) }
            } catch (e: IllegalStateException) {
                log.warn("webhook_dispatch_worker_decrypt_failed webhookId={} event={}", webhookId, parsed.eventType)
                recordFailure(webhookId, parsed.eventType, attemptCount, DECRYPT_FAILURE_DETAIL, responseCode = null)
                return
            }

        val deliveryId = stableDeliveryId(webhookId, msgId)
        val payloadBody = buildPayloadBody(parsed, deliveryId)

        when (val result = dispatcher.dispatch(webhook.url, secret, parsed.eventType, deliveryId, payloadBody)) {
            is WebhookDispatchResult.Sent -> {
                webhookDeliveryRepository.record(
                    webhookId,
                    parsed.eventType,
                    DeliveryStatus.SUCCEEDED,
                    result.code,
                    attemptCount,
                    null,
                )
                circuitBreaker.recordSuccess(webhookId)
            }
            is WebhookDispatchResult.Rejected -> {
                recordFailure(webhookId, parsed.eventType, attemptCount, REJECTED_ERROR_DETAIL, responseCode = null)
            }
            is WebhookDispatchResult.Failed -> {
                recordFailure(webhookId, parsed.eventType, attemptCount, result.reason, responseCode = result.code)
            }
        }
    }

    /** FAILED 이력을 기록하고 circuit 실패 카운터를 갱신한다(공통 실패 경로). */
    private fun recordFailure(
        webhookId: UUID,
        eventType: String,
        attemptCount: Int,
        errorDetail: String,
        responseCode: Int?,
    ) {
        webhookDeliveryRepository.record(webhookId, eventType, DeliveryStatus.FAILED, responseCode, attemptCount, errorDetail)
        circuitBreaker.recordFailure(webhookId)
    }

    /**
     * wire JSON [node] 에서 발행 가능(publishable) 이벤트를 판별해 화이트리스트 payload 를 구성한다.
     *
     * `"type"` 이 [WebhookEventCatalog.PUBLISHABLE] 밖이면 `null`(EC8 방어 — 호출자가 delete 처리).
     */
    private fun extractPublishableEvent(node: JsonNode): ParsedEvent? =
        when (node.path("type").asText("")) {
            WebhookEventCatalog.ISSUE_CREATED -> parseIssueCreated(node)
            WebhookEventCatalog.ISSUE_TRANSITIONED -> parseIssueTransitioned(node)
            else -> null
        }

    /** `issue.created` wire JSON → 화이트리스트 [ParsedEvent](spec §6 — issueKey/projectKey/summary). */
    private fun parseIssueCreated(node: JsonNode): ParsedEvent {
        val issueKey = node.path("issueKey").asText("")
        val projectKey = node.path("projectKey").asText("")
        return ParsedEvent(
            eventType = WebhookEventCatalog.ISSUE_CREATED,
            projectKey = projectKey,
            occurredAt = node.path("occurredAt").asText(""),
            data =
                linkedMapOf(
                    "issueKey" to issueKey,
                    "projectKey" to projectKey,
                    "summary" to node.path("summary").asText(""),
                ),
        )
    }

    /**
     * `issue.transitioned` wire JSON → 화이트리스트 [ParsedEvent](spec §6 — issueKey/projectKey/
     * fromState/toState). `projectKey` 필드가 없어(EC1) issueKey 접두사(`-` 앞부분)에서 파싱한다.
     */
    private fun parseIssueTransitioned(node: JsonNode): ParsedEvent {
        val issueKey = node.path("issueKey").asText("")
        val projectKey = issueKey.substringBefore(PROJECT_KEY_SEPARATOR, issueKey)
        return ParsedEvent(
            eventType = WebhookEventCatalog.ISSUE_TRANSITIONED,
            projectKey = projectKey,
            occurredAt = node.path("occurredAt").asText(""),
            data =
                linkedMapOf(
                    "issueKey" to issueKey,
                    "projectKey" to projectKey,
                    "fromState" to node.path("fromState").asText(""),
                    "toState" to node.path("toState").asText(""),
                ),
        )
    }

    /** spec §6 발송 envelope(`event`/`deliveryId`/`occurredAt`/`data`)를 raw JSON 바이트로 직렬화한다. */
    private fun buildPayloadBody(
        parsed: ParsedEvent,
        deliveryId: String,
    ): ByteArray {
        val envelope =
            linkedMapOf<String, Any?>(
                "event" to parsed.eventType,
                "deliveryId" to deliveryId,
                "occurredAt" to parsed.occurredAt,
                "data" to parsed.data,
            )
        return objectMapper.writeValueAsBytes(envelope)
    }

    /**
     * [webhookId]:[msgId] 조합으로 결정적 안정 멱등키를 계산한다(B1).
     *
     * `UUID.nameUUIDFromBytes` 는 JDK 표준 이름 기반 UUID(RFC 4122 버전 3, MD5) 생성 함수로, 같은
     * 입력 바이트에는 항상 같은 UUID 를 반환한다 — 재전달 시에도 `msgId` 가 같으므로 동일 키가 나온다.
     */
    private fun stableDeliveryId(
        webhookId: UUID,
        msgId: Long,
    ): String = UUID.nameUUIDFromBytes("$webhookId:$msgId".toByteArray(StandardCharsets.UTF_8)).toString()

    /** messageJson 을 Jackson 으로 파싱한다. 실패 시 null 반환. */
    @Suppress("TooGenericExceptionCaught")
    private fun parseJson(
        messageJson: String,
        msgId: Long,
    ): JsonNode? =
        try {
            objectMapper.readTree(messageJson)
        } catch (e: Exception) {
            log.error("webhook_dispatch_worker_json_parse_failed msgId={} error={}", msgId, e.message)
            null
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
                "webhook_dispatch_worker_poison_retry msgId={} readCt={} maxReceiveCount={}",
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
        log.error("webhook_dispatch_worker_dead_letter msgId={} readCt={} action=archive", msgId, readCt)
        dsl.execute("SELECT pgmq.archive(?, ?)", QUEUE_NAME, msgId)
    }

    /** 처리 완료 후 pgmq 에서 메시지를 삭제한다. */
    private fun deleteMessage(msgId: Long) {
        dsl.execute("SELECT pgmq.delete(?, ?)", QUEUE_NAME, msgId)
        log.debug("webhook_dispatch_worker_deleted msgId={}", msgId)
    }

    /**
     * 화이트리스트 처리된 이벤트 — 발송에 필요한 값만 담는다(내부 VO/사용자 UUID 미포함, C3).
     *
     * @property eventType `X-BTS-Event`/body `event` 값.
     * @property projectKey 구독 매칭에 사용할 프로젝트 키.
     * @property occurredAt 원본 이벤트 발생 시각 문자열(재파싱 없이 그대로 전달).
     * @property data body `data` 필드에 들어갈 화이트리스트 스칼라 맵.
     */
    private data class ParsedEvent(
        val eventType: String,
        val projectKey: String,
        val occurredAt: String,
        val data: Map<String, String>,
    )

    companion object {
        /** pgmq 큐 이름 — issue-tracking V034 마이그레이션에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_webhook_events"

        /**
         * pgmq visibility timeout(초).
         *
         * fanout(메시지 1건 → 매칭 구독 N건 순차 POST)이라 `read timeout × N × BATCH` 가 VT 를 넘으면
         * 처리 중 재전달로 중복 발송이 생길 수 있다(spec §8 C1). [BATCH_SIZE]=1 로 시작해 완화한다.
         */
        const val VT_SECONDS = 60

        /**
         * pgmq.read 1회 폴링에서 읽어올 최대 메시지 수.
         *
         * fanout 특성상 1(spec §8 C1) — 메시지 1건의 전체 fanout 이 VT 안에 끝나도록 보수적으로 설정한다.
         */
        const val BATCH_SIZE = 1

        /** poison 메시지 최대 수신 허용 횟수. 초과 시 `pgmq.archive` 로 dead-letter 처리한다. */
        const val MAX_RECEIVE_COUNT = 5

        /** `issue.transitioned` issueKey 에서 projectKey 를 파싱할 때 쓰는 구분자(EC1). */
        private const val PROJECT_KEY_SEPARATOR = "-"

        /** SSRF 차단(Rejected) 발송 실패 이력에 남기는 일반 메시지 — 내부 host/IP 를 echo 하지 않는다(EC6). */
        private const val REJECTED_ERROR_DETAIL = "webhook URL이 발송 시점 SSRF 검증에서 차단되었습니다"

        /** secret 복호화 실패 발송 실패 이력에 남기는 일반 메시지 — 예외 메시지/평문을 echo 하지 않는다(C7). */
        private const val DECRYPT_FAILURE_DETAIL = "webhook signing secret 복호화에 실패했습니다"
    }
}

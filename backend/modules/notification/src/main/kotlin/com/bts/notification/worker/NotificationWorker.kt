// q_issue_events를 폴링해 정책 평가→수신자 해석→채널 발송하는 알림 워커 (pgmq consumer)

package com.bts.notification.worker

import com.bts.notification.application.NotificationPolicyEvaluator
import com.bts.notification.channel.NotificationChannelSender
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import com.bts.notification.recipient.EventRecipientResolver
import com.bts.notification.recipient.NotificationSourceEvent
import com.bts.notification.recipient.ResolvedRecipient
import com.bts.notification.repository.NotificationRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * pgmq `q_issue_events` 큐를 폴링하여 알림을 생성·발송하는 스케줄 워커.
 *
 * ## 처리 흐름
 * 1. `pgmq.read(queue, vt, qty)` 로 메시지 읽기
 * 2. JSON type 필드 → [NotificationEventType.fromWire]. null 이면 메시지 삭제(소임 없음)
 * 3. type 별 필드 파싱 → [NotificationSourceEvent] 구성
 * 4. [NotificationPolicyEvaluator.evaluate] → [com.bts.notification.application.PolicyMatch] 목록
 * 5. [EventRecipientResolver.resolve] → [ResolvedRecipient] 목록
 * 6. 각 수신자: [Notification] 생성 → [NotificationRepository.insertIfAbsent]
 *    - true(신규): 채널 매칭 sender 로 [NotificationChannelSender.send]
 *    - false(중복): send 생략 (멱등, S4)
 * 7. 성공 → `pgmq.delete`. 예외 → delete 안 함 (vt 만료 재전달, at-least-once)
 *
 * ## dead-letter (poison 메시지)
 * 예외 발생 시 [read_ct][MAX_RECEIVE_COUNT] 초과 여부 확인.
 * 초과 시 `pgmq.archive` 로 dead-letter 처리.
 *
 * ## @Transactional 없음 — 의도적 설계
 * pgmq.read 는 트랜잭션 범위 밖에서 호출해도 pgmq 내부 vt 가 atomic 하게 갱신된다.
 * pollAndProcess 에 @Transactional 을 걸면 REQUIRES_NEW 협력자의 트랜잭션 전파 문제가 발생한다.
 * (learnings: transaction-self-invocation-REQUIRES_NEW)
 *
 * ## BC 격리
 * issue-tracking BC 의 IssueDomainEvent 를 직접 import 하지 않는다.
 * pgmq JSON 을 [NotificationSourceEvent] 로 직접 파싱해 내부 표현으로 변환한다.
 *
 * @param dsl jOOQ [DSLContext]. pgmq raw SQL 실행. 문자열 결합 금지 — ? 바인딩 사용.
 * @param policyEvaluator 알림 정책 평가 서비스
 * @param recipientResolver 이벤트+정책 → 수신자 목록 해석기
 * @param repository 알림 멱등 INSERT repository
 * @param channelSenders 채널 sender 목록 (Spring 자동 주입)
 * @param objectMapper JSON 파싱용 Jackson ObjectMapper
 */
@Suppress("TooManyFunctions") // pgmq 워커 처리 흐름 헬퍼(parse/build/delete/archive 등) 포함, 역할 명확해 분리 불필요
@Component
class NotificationWorker(
    private val dsl: DSLContext,
    private val policyEvaluator: NotificationPolicyEvaluator,
    private val recipientResolver: EventRecipientResolver,
    private val repository: NotificationRepository,
    private val channelSenders: List<NotificationChannelSender>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * q_issue_events 큐를 폴링하여 대기 중인 이벤트 메시지를 처리한다.
     *
     * 메시지가 없으면 즉시 반환한다.
     * 예외가 발생한 메시지는 delete 하지 않아 vt 만료 후 재전달된다 (at-least-once).
     *
     * @Transactional 없음 — 의도적 설계 (KDoc 클래스 레벨 참조).
     */
    @Scheduled(fixedDelayString = "\${bts.notification.worker.poll-interval-ms:500}")
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

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 단일 메시지를 처리한다.
     *
     * 예외 발생 시 로그를 남기고 delete 를 건너뛴다 (재전달 허용).
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
        val eventType = NotificationEventType.fromWire(typeStr)

        if (eventType == null) {
            log.info("notification_worker_unsupported_type msgId={} type={} action=delete", msgId, typeStr)
            deleteMessage(msgId)
            return
        }

        log.info("notification_worker_received msgId={} type={} readCt={}", msgId, typeStr, readCt)

        try {
            val sourceEvent = buildSourceEvent(eventType, node)
            dispatch(sourceEvent)
            deleteMessage(msgId)
        } catch (e: Exception) {
            log.error(
                "notification_worker_processing_failed msgId={} type={} error={}",
                msgId,
                typeStr,
                e.message,
                e,
            )
            if (readCt > MAX_RECEIVE_COUNT) {
                archiveMessage(msgId, readCt)
            }
            // delete 하지 않음 — vt 만료 후 재전달 (at-least-once)
        }
    }

    /**
     * 정책 평가 → 수신자 해석 → 알림 생성·발송을 순서대로 수행한다.
     *
     * @param event pgmq 역직렬화된 이벤트 표현
     */
    private fun dispatch(event: NotificationSourceEvent) {
        val matches = policyEvaluator.evaluate(event.eventType, event.projectKey)
        if (matches.isEmpty()) {
            log.debug("notification_worker_no_policy eventType={} projectKey={}", event.eventType, event.projectKey)
            return
        }

        val recipients = recipientResolver.resolve(event, matches)
        for (recipient in recipients) {
            sendToRecipient(event, recipient)
        }
    }

    /**
     * 수신자 1명에 대해 알림을 생성하고 중복 여부를 확인한 뒤 발송한다.
     *
     * @param event 원본 이벤트
     * @param recipient 수신자 정보 (userId, channel)
     */
    private fun sendToRecipient(
        event: NotificationSourceEvent,
        recipient: ResolvedRecipient,
    ) {
        val notification = buildNotification(event, recipient)
        val isNew = repository.insertIfAbsent(notification)
        if (!isNew) {
            log.debug(
                "notification_worker_duplicate_skipped recipientUserId={} eventType={} dedupKey={}",
                recipient.userId,
                event.eventType,
                notification.dedupKey,
            )
            return
        }

        val sender = channelSenders.firstOrNull { it.supports(recipient.channel) }
        if (sender == null) {
            log.warn(
                "notification_worker_no_sender_for_channel channel={} notificationId={}",
                recipient.channel,
                notification.id,
            )
            return
        }
        deliver(notification, sender)
    }

    /**
     * 알림을 채널로 전송하고, 전송이 성공한 경우에만 status 를 SENT 로 전이한다.
     *
     * 푸시 실패는 best-effort 로 처리한다 — 예외를 전파하지 않아 pgmq 메시지 재전달(중복 푸시)을 막고,
     * 행은 PENDING 으로 남겨 Inbox(FR-UX-03)가 영속 fallback 이 되게 한다. 이로써 "발송 전 SENT 박제 +
     * dedup 이 재시도를 영구 차단해 푸시가 유실되는" 결함을 막는다.
     *
     * @param notification 발송할 알림(이미 PENDING 으로 삽입됨)
     * @param sender 채널 sender
     */
    @Suppress("TooGenericExceptionCaught")
    private fun deliver(
        notification: Notification,
        sender: NotificationChannelSender,
    ) {
        try {
            sender.send(notification)
            repository.markSent(notification.id)
        } catch (e: Exception) {
            log.warn(
                "notification_worker_push_failed_keep_pending notificationId={} recipientUserId={} error={}",
                notification.id,
                notification.recipientUserId,
                e.message,
                e,
            )
        }
    }

    /**
     * 이벤트와 수신자 정보로 [Notification] 도메인 객체를 생성한다.
     *
     * title/body 는 issueKey + eventType 기반 간단 문자열 — actor 이름 조회 안 함 (FR10).
     * status 는 PENDING 으로 삽입한다. 채널 전송이 성공한 뒤에만 [deliver] 가 markSent 로 SENT 전이한다
     * (발송 전 SENT 박제 방지 — 푸시 실패 시 PENDING 으로 남아 Inbox(FR-UX-03)가 영속 fallback).
     *
     * @param event 원본 이벤트
     * @param recipient 수신자 정보
     * @return 생성된 알림 객체
     */
    private fun buildNotification(
        event: NotificationSourceEvent,
        recipient: ResolvedRecipient,
    ): Notification {
        val dedupKey =
            Notification.computeDedupKey(
                eventType = event.eventType,
                issueKey = event.issueKey,
                occurredAt = event.occurredAt,
                recipientUserId = recipient.userId,
                channel = recipient.channel,
            )
        val (title, body) = buildTitleBody(event)
        return Notification(
            id = UUID.randomUUID(),
            recipientUserId = recipient.userId,
            eventType = event.eventType,
            channel = recipient.channel,
            issueKey = event.issueKey,
            title = title,
            body = body,
            payload = null,
            status = NotificationStatus.PENDING,
            dedupKey = dedupKey,
            readAt = null,
            createdAt = event.occurredAt,
        )
    }

    /**
     * 이벤트 유형과 이슈 키를 기반으로 알림 제목·본문을 생성한다.
     *
     * actor 이름 조회 없이 간단 문자열로 구성 (FR10 설계 제약).
     *
     * @param event 원본 이벤트
     * @return Pair(title, body)
     */
    private fun buildTitleBody(event: NotificationSourceEvent): Pair<String, String?> {
        val issueRef = event.issueKey ?: ""
        val title =
            when (event.eventType) {
                NotificationEventType.ISSUE_MENTIONED -> "$issueRef 에서 멘션되었습니다"
                NotificationEventType.ISSUE_CREATED -> "$issueRef 이슈가 생성되었습니다"
                NotificationEventType.ISSUE_TRANSITIONED -> "$issueRef 상태가 변경되었습니다"
                NotificationEventType.ISSUE_ASSIGNED -> "$issueRef 이슈가 할당되었습니다"
                NotificationEventType.ISSUE_COMMENTED -> "$issueRef 에 댓글이 작성되었습니다"
                NotificationEventType.ISSUE_DUE_SOON -> "$issueRef 마감이 임박했습니다"
                NotificationEventType.ISSUE_OVERDUE -> "$issueRef 마감이 초과되었습니다"
                NotificationEventType.SPRINT_STARTED -> "스프린트가 시작되었습니다"
                NotificationEventType.SPRINT_ENDED -> "스프린트가 종료되었습니다"
                NotificationEventType.AUTOMATION_FAILED -> "자동화 룰 실행에 실패했습니다"
            }
        return title to null
    }

    /**
     * 이벤트 유형에 따라 JSON 노드에서 [NotificationSourceEvent] 를 구성한다.
     *
     * - issue.mentioned: mentionedUserIds, actorId, issueKey, projectKey
     * - issue.created: reporterId(payload), issueKey, projectKey
     * - issue.transitioned: issueKey, projectKey (담당/리포터는 resolver 포트 조회)
     * - 그 외 지원 type: issueKey, projectKey 공통 파싱
     *
     * BC 격리 — issue-tracking IssueDomainEvent 직접 import 금지.
     *
     * @param eventType 이미 매핑된 이벤트 유형
     * @param node 파싱된 JSON 루트 노드
     * @return 구성된 이벤트 표현
     */
    private fun buildSourceEvent(
        eventType: NotificationEventType,
        node: JsonNode,
    ): NotificationSourceEvent {
        val issueKey = node.path("issueKey").asText(null).takeIf { it?.isNotBlank() == true }
        val projectKey = node.path("projectKey").asText(null).takeIf { it?.isNotBlank() == true }
        // occurredAt 은 모든 프로듀서 이벤트가 항상 포함한다(ISO-8601 문자열). dedup_key 의 결정성이
        // occurredAt 에 의존하므로 now() 폴백은 멱등을 깨뜨린다(같은 이벤트 재전달 시 dedupKey 가 달라져
        // 중복 알림 발송). 부재/파싱불가 이벤트는 malformed 로 간주해 예외를 던진다 → processMessage 가
        // catch → delete 안 함 → 재전달 → read_ct>MAX 시 archive(dead-letter).
        val occurredAt =
            requireNotNull(parseInstant(node.path("occurredAt").asText(null))) {
                "event missing or invalid occurredAt: type=$eventType"
            }
        val actorId = parseActorId(node.path("actorId"))

        val mentionedUserIds =
            if (eventType == NotificationEventType.ISSUE_MENTIONED) {
                parseMentionedUserIds(node)
            } else {
                emptyList()
            }

        val reporterId =
            if (eventType == NotificationEventType.ISSUE_CREATED) {
                parseActorId(node.path("reporterId"))
            } else {
                null
            }

        return NotificationSourceEvent(
            eventType = eventType,
            issueKey = issueKey,
            projectKey = projectKey,
            mentionedUserIds = mentionedUserIds,
            reporterId = reporterId,
            actorId = actorId,
            occurredAt = occurredAt,
        )
    }

    /** JSON 에서 mentionedUserIds 배열을 파싱한다. 파싱 실패 항목은 건너뛴다. */
    private fun parseMentionedUserIds(node: JsonNode): List<UUID> {
        val arrayNode = node.path("mentionedUserIds")
        if (!arrayNode.isArray) return emptyList()
        return arrayNode.mapNotNull { parseUuid(it.asText(null)) }
    }

    /** UUID 문자열을 파싱한다. 실패 시 null 반환 (`!!` 금지). */
    private fun parseUuid(value: String?): UUID? {
        if (value.isNullOrBlank()) return null
        return runCatching { UUID.fromString(value) }.getOrNull()
    }

    /**
     * 행위자/리포터 식별자 노드를 UUID 로 파싱한다.
     *
     * issue-tracking 의 `ActorId` 는 일반 data class 라 Jackson 이 nested `{"value":"<uuid>"}` 로
     * 직렬화한다(value class `IssueKey` 의 flat 문자열과 대비). nested `value` 를 우선 추출하고,
     * 계약 변경/flat 형태에도 견디도록 노드 자체의 텍스트도 방어적으로 시도한다.
     *
     * @param node `actorId` / `reporterId` 필드 노드(없으면 MissingNode)
     * @return 파싱된 UUID 또는 null
     */
    private fun parseActorId(node: JsonNode): UUID? {
        val valueNode = node.path("value")
        val raw = if (valueNode.isMissingNode) node.asText(null) else valueNode.asText(null)
        return parseUuid(raw)
    }

    /** Instant 문자열을 파싱한다. 실패 시 null 반환. */
    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
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
            log.error("notification_worker_json_parse_failed msgId={} error={}", msgId, e.message)
            null
        }

    /** poison 메시지를 처리한다. read_ct > MAX 시 archive, 미만 시 재전달 대기. */
    private fun handlePoison(
        msgId: Long,
        readCt: Int,
    ) {
        if (readCt > MAX_RECEIVE_COUNT) {
            archiveMessage(msgId, readCt)
        } else {
            log.warn(
                "notification_worker_poison_retry msgId={} readCt={} maxReceiveCount={}",
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
        log.error(
            "notification_worker_dead_letter msgId={} readCt={} action=archive",
            msgId,
            readCt,
        )
        dsl.execute("SELECT pgmq.archive(?, ?)", QUEUE_NAME, msgId)
    }

    /** 처리 성공 후 pgmq 에서 메시지를 삭제한다. */
    private fun deleteMessage(msgId: Long) {
        dsl.execute("SELECT pgmq.delete(?, ?)", QUEUE_NAME, msgId)
        log.debug("notification_worker_deleted msgId={}", msgId)
    }

    companion object {
        /**
         * pgmq 이슈 이벤트 큐 이름 — issue-tracking BC 의 이벤트 발행 큐와 일치해야 한다.
         * V401 마이그레이션에서 생성된 큐.
         */
        const val QUEUE_NAME = "q_issue_events"

        /**
         * pgmq visibility timeout (초).
         *
         * 알림 p95 처리 예산 기준.
         * - 단건 처리(정책 평가 + 수신자 조회 + DB insert + STOMP push): ~50ms
         * - 배치 [BATCH_SIZE]=10건: 총 ~500ms
         * - vt = 예산(500ms) × 60배 = 30초 → 크래시 시 재전달 윈도우 확보
         * - 채택: 30초 (eng-review C2: 알림 지연 p95<1s 요구)
         */
        const val VT_SECONDS = 30

        /**
         * pgmq.read 1회 폴링에서 읽어올 최대 메시지 수.
         *
         * 단일 폴링이 너무 길어지지 않도록 소규모 제한.
         * 처리량 부족 시 poll-interval-ms 를 줄이는 것이 우선.
         */
        const val BATCH_SIZE = 10

        /**
         * poison 메시지 최대 수신 허용 횟수.
         *
         * read_ct 가 이 값을 초과하면 `pgmq.archive` 로 dead-letter 처리한다.
         * at-least-once 재전달 허용을 위해 즉시 archive 하지 않는다.
         */
        const val MAX_RECEIVE_COUNT = 5
    }
}

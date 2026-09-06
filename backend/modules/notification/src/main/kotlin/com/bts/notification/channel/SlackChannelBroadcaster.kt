// 프로젝트 활동 이벤트를 q_slack_channel_broadcasts 큐로 발행하는 브로드캐스터 — slack-integration BC 직접 의존 없이 JSON 경계만 공유

package com.bts.notification.channel

import com.bts.notification.recipient.NotificationSourceEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * projectKey 가 있는 이벤트마다 이벤트당 1회 pgmq [QUEUE_NAME] 큐로 채널 브로드캐스트 메시지를
 * 발행하는 컴포넌트 (FR-SL-06 PR-B).
 *
 * ### 수신자/정책과 독립
 * [com.bts.notification.worker.NotificationWorker] 의 정책 평가·수신자 해석과 무관하게 동작한다.
 * projectKey 가 있는 이벤트는 정책 매치가 0건이거나(관리자 정책 미설정) 수신자가 0명이어도 브로드캐스트한다
 * — 프로젝트 활동 피드는 개인 알림 구독과 별개의 관심사이기 때문이다.
 *
 * ### BC 격리
 * slack-integration 코드를 직접 import 하지 않는다. [QUEUE_NAME] 큐에 발행하는 JSON payload 가
 * 유일한 계약 경계다. 큐 자체는 slack-integration `V705__slack_channel_broadcast.sql` 이 생성한다
 * (producer-creates 예외 — V701 선례, [SlackChannelSender] KDoc 참고).
 *
 * ### JSON 계약 (q_slack_channel_broadcasts)
 * ```json
 * { "projectKey": "ATLAS", "eventType": "issue.created", "issueKey": "ATLAS-1",
 *   "title": "<제목>", "occurredAt": "<iso-8601>", "dedupKey": "<이벤트레벨 SHA-256 hex>" }
 * ```
 *
 * `dedupKey` 는 (projectKey, eventType, issueKey, occurredAt) 로만 결정되는 이벤트레벨 해시다.
 * 채널별 dedup(같은 이벤트가 여러 매핑 채널에 게시되는 경우 각 채널마다 게시 여부를 구분)은
 * slack-integration 채널 워커가 이 값에 channelId 를 결합해 처리한다
 * ([com.bts.slack.application.SlackChannelBroadcastDedupRepository] 참고).
 *
 * @param dsl jOOQ [DSLContext] — pgmq.send raw SQL 실행에 사용.
 * @param objectMapper Jackson [ObjectMapper] — payload Map → JSON 직렬화.
 * @param titleBuilder 이벤트 유형별 제목 생성기 (NotificationWorker 와 공유).
 */
@Component
class SlackChannelBroadcaster(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val titleBuilder: NotificationTitleBuilder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [event] 에 projectKey 가 있으면 [QUEUE_NAME] 큐에 JSON 으로 발행한다.
     *
     * projectKey 가 없으면(null) 프로젝트 채널 매핑 대상이 아니므로 발행하지 않는다.
     *
     * @param event 원본 이벤트
     */
    fun broadcastIfApplicable(event: NotificationSourceEvent) {
        val projectKey = event.projectKey ?: return

        val dedupKey = computeDedupKey(projectKey, event)
        val payload =
            mapOf(
                "projectKey" to projectKey,
                "eventType" to event.eventType.wireValue,
                "issueKey" to event.issueKey,
                "title" to titleBuilder.buildTitle(event),
                "occurredAt" to event.occurredAt.toString(),
                "dedupKey" to dedupKey,
            )
        val json = objectMapper.writeValueAsString(payload)

        dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, json)

        log.info(
            "slack channel broadcast enqueued queue={} projectKey={} eventType={} dedupKey={}",
            QUEUE_NAME,
            projectKey,
            event.eventType.wireValue,
            dedupKey,
        )
    }

    /**
     * 이벤트레벨 결정적 dedup 키를 계산한다.
     *
     * 구성 원소: projectKey + eventType.wireValue + issueKey("" 로 null 처리) + occurredAt ISO 문자열
     *            (+ commentId — 있을 때만).
     * 같은 입력이면 항상 같은 64자 소문자 hex SHA-256 문자열을 반환한다
     * ([com.bts.notification.domain.Notification.computeDedupKey] 동형 패턴).
     *
     * ## commentId 는 있을 때만 원소가 된다
     * 이 키는 recipient 를 안 쓰는 **이벤트 레벨** 키라 인앱 키보다 원소가 적다 — 같은 이슈에
     * 같은 `occurredAt` 으로 댓글 2건이 달리면 인앱보다 **먼저** 충돌한다. 그때
     * [com.bts.slack.worker.SlackDeliveryWorker] 가 `slack_delivery_skip_duplicate` 로 두 번째를
     * 버리므로 유실이 「중복 차단이 동작했다」는 얼굴로 나타난다.
     *
     * ★`listOfNotNull` 이라 commentId 가 null 이면 원소 자체가 없다 — 댓글과 무관한 이벤트의
     * 키는 오늘과 바이트 단위로 같다. 폭발 반경을 댓글 이벤트로만 가둔다.
     *
     * @param projectKey 프로젝트 키
     * @param event 원본 이벤트
     * @return 64자 소문자 hex SHA-256 문자열
     */
    private fun computeDedupKey(
        projectKey: String,
        event: NotificationSourceEvent,
    ): String {
        val parts =
            listOfNotNull(
                projectKey,
                event.eventType.wireValue,
                event.issueKey ?: "",
                event.occurredAt.toString(),
                event.commentId?.toString(),
            )
        return sha256Hex(parts.joinToString(DEDUP_SEPARATOR))
    }

    /** 입력 문자열을 SHA-256 해시해 소문자 hex 로 인코딩한다. */
    private fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance(DEDUP_ALGORITHM)
        val hashBytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** pgmq 큐 이름 — slack-integration V705__slack_channel_broadcast.sql 에서 생성된 큐와 일치해야 한다. */
        const val QUEUE_NAME = "q_slack_channel_broadcasts"
        private const val DEDUP_ALGORITHM = "SHA-256"
        private const val DEDUP_SEPARATOR = "|"
    }
}

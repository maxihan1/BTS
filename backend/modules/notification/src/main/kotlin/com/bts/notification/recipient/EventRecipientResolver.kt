// 이벤트+정책매치를 실제 수신자(userId×채널)로 해석 (멘션/리포터/담당, 워처·role은 FR-NT-03)

package com.bts.notification.recipient

import com.bts.notification.application.PolicyMatch
import com.bts.notification.domain.Channel
import com.bts.notification.domain.RecipientRole
import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 이벤트와 정책 매치 목록을 받아 실제 수신자([ResolvedRecipient]) 목록을 결정한다.
 *
 * ## 해석 규칙
 * - [RecipientRole.MENTIONED]: [NotificationSourceEvent.mentionedUserIds] 각각 → 수신자.
 * - [RecipientRole.REPORTER]: [NotificationSourceEvent.reporterId] 있으면 직접 사용,
 *   없으면 [issueRecipientLookupPort] 로 조회.
 * - [RecipientRole.ASSIGNEE]: [NotificationSourceEvent.issueKey] 로 포트 조회.
 * - 그 외([RecipientRole.WATCHER] 등): FR-NT-03 대상 — skip, 디버그 로그만.
 *
 * ## 공통 처리
 * - actor 본인 제외: [NotificationSourceEvent.actorId] 와 동일한 userId 는 결과에서 제거.
 * - 중복 제거: 동일 (userId, channel) 쌍은 1개로 합친다.
 * - N+1 방지: 같은 이벤트 내 REPORTER+ASSIGNEE 포트 조회는 1회 호출 후 재사용.
 *
 * @param issueRecipientLookupPort 이슈 수신자 cross-BC 조회 포트 (shared-kernel)
 */
@Component
class EventRecipientResolver(
    private val issueRecipientLookupPort: IssueRecipientLookupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [event] 의 이벤트 정보와 [matches] 의 역할×채널 목록을 결합해 실제 수신자를 반환한다.
     *
     * @param event pgmq 역직렬화된 이벤트 표현
     * @param matches 정책 평가기가 반환한 역할×채널 목록
     * @return 중복 제거된 수신자 목록 (actorId 제외)
     */
    fun resolve(
        event: NotificationSourceEvent,
        matches: List<PolicyMatch>,
    ): List<ResolvedRecipient> {
        if (matches.isEmpty()) return emptyList()

        // N+1 방지: REPORTER/ASSIGNEE 중 하나라도 포트 조회가 필요한 경우 1회만 호출
        val recipients = lazyRecipientsLookup(event, matches)

        val resolved =
            matches.flatMap { match ->
                resolveRole(event, match, recipients)
            }

        return resolved
            .filter { it.userId != event.actorId }
            .distinctBy { it.userId to it.channel }
    }

    /** 역할 1개를 수신자 목록으로 해석한다. */
    private fun resolveRole(
        event: NotificationSourceEvent,
        match: PolicyMatch,
        recipients: IssueRecipients?,
    ): List<ResolvedRecipient> =
        when (match.recipientRole) {
            RecipientRole.MENTIONED -> resolveMentioned(event, match.channel)
            RecipientRole.REPORTER -> resolveReporter(event, match.channel, recipients)
            RecipientRole.ASSIGNEE -> resolveAssignee(match.channel, recipients)
            else -> {
                log.debug(
                    "역할 skip (FR-NT-03 대상) — role={}, eventType={}, issueKey={}",
                    match.recipientRole,
                    event.eventType,
                    event.issueKey,
                )
                emptyList()
            }
        }

    private fun resolveMentioned(
        event: NotificationSourceEvent,
        channel: Channel,
    ): List<ResolvedRecipient> = event.mentionedUserIds.map { ResolvedRecipient(userId = it, channel = channel) }

    private fun resolveReporter(
        event: NotificationSourceEvent,
        channel: Channel,
        recipients: IssueRecipients?,
    ): List<ResolvedRecipient> {
        val reporterId: UUID? = event.reporterId ?: recipients?.reporterId
        return reporterId
            ?.let { listOf(ResolvedRecipient(userId = it, channel = channel)) }
            ?: emptyList()
    }

    private fun resolveAssignee(
        channel: Channel,
        recipients: IssueRecipients?,
    ): List<ResolvedRecipient> {
        val assigneeId: UUID? = recipients?.assigneeId
        return assigneeId
            ?.let { listOf(ResolvedRecipient(userId = it, channel = channel)) }
            ?: emptyList()
    }

    /**
     * REPORTER 또는 ASSIGNEE 역할이 포함된 경우에만 포트를 1회 호출해 반환한다.
     *
     * event.reporterId 가 있어도 ASSIGNEE 는 포트가 필요하므로,
     * 두 역할 중 하나라도 있으면 issueKey 가 있을 때 포트를 호출한다.
     */
    private fun lazyRecipientsLookup(
        event: NotificationSourceEvent,
        matches: List<PolicyMatch>,
    ): IssueRecipients? {
        val needsPortLookup =
            matches.any {
                (it.recipientRole == RecipientRole.REPORTER && event.reporterId == null) ||
                    it.recipientRole == RecipientRole.ASSIGNEE
            }
        if (!needsPortLookup || event.issueKey == null) return null
        return issueRecipientLookupPort.findRecipients(event.issueKey)
    }
}

/**
 * [EventRecipientResolver.resolve] 의 출력 단위 — 수신자 1명의 userId 와 채널.
 *
 * @param userId 알림을 받을 사용자 UUID
 * @param channel 전송 채널
 */
data class ResolvedRecipient(
    val userId: UUID,
    val channel: Channel,
)

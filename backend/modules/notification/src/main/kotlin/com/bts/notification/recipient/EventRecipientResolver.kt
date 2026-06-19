// 이벤트+정책매치를 실제 수신자(userId×채널)로 해석 (멘션/리포터/담당/워처/컴포넌트/프로젝트)

package com.bts.notification.recipient

import com.bts.notification.application.PolicyMatch
import com.bts.notification.domain.Channel
import com.bts.notification.domain.RecipientRole
import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import com.bts.shared.issue.ProjectRecipientLookupPort
import com.bts.shared.issue.ProjectRecipients
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
 * - [RecipientRole.WATCHER]: [IssueRecipients.watcherIds] 목록 → 각각 수신자.
 * - [RecipientRole.COMPONENT_LEAD]: [IssueRecipients.componentLeadIds] 목록 → 각각 수신자. lead 없으면 빈.
 * - [RecipientRole.PREVIOUS_ASSIGNEE]: [IssueRecipients.previousAssigneeId] 단수. null 이면 빈.
 * - [RecipientRole.PROJECT_MEMBER]: [ProjectRecipients.memberIds] 목록 → 각각 수신자.
 * - [RecipientRole.PROJECT_ADMIN]: [ProjectRecipients.adminIds] 목록 → 각각 수신자.
 * - [RecipientRole.RULE_OWNER]: 현재 미구현 — skip, 디버그 로그만.
 *
 * ## 공통 처리
 * - actor 본인 제외: [NotificationSourceEvent.actorId] 와 동일한 userId 는 결과에서 제거.
 * - 중복 제거: 동일 (userId, channel) 쌍은 1개로 합친다.
 * - N+1 방지: 이슈 기반 역할(REPORTER/ASSIGNEE/WATCHER/COMPONENT_LEAD/PREVIOUS_ASSIGNEE)은
 *   issueKey 당 1회, 프로젝트 기반 역할(PROJECT_MEMBER/PROJECT_ADMIN)은 projectKey 당 1회 조회.
 *
 * @param issueRecipientLookupPort 이슈 수신자 cross-BC 조회 포트 (shared-kernel)
 * @param projectRecipientLookupPort 프로젝트 멤버·관리자 cross-BC 조회 포트 (shared-kernel)
 */
@Component
class EventRecipientResolver(
    private val issueRecipientLookupPort: IssueRecipientLookupPort,
    private val projectRecipientLookupPort: ProjectRecipientLookupPort,
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

        // N+1 방지: 이슈 기반 역할은 issueKey 당 1회, 프로젝트 기반 역할은 projectKey 당 1회 조회
        val issueRecipients = lazyIssueRecipientsLookup(event, matches)
        val projectRecipients = lazyProjectRecipientsLookup(event, matches)

        val resolved =
            matches.flatMap { match ->
                resolveRole(event, match, issueRecipients, projectRecipients)
            }

        return resolved
            .filter { it.userId != event.actorId }
            .distinctBy { it.userId to it.channel }
    }

    /** 역할 1개를 수신자 목록으로 해석한다. */
    private fun resolveRole(
        event: NotificationSourceEvent,
        match: PolicyMatch,
        issueRecipients: IssueRecipients?,
        projectRecipients: ProjectRecipients?,
    ): List<ResolvedRecipient> =
        when (match.recipientRole) {
            RecipientRole.MENTIONED -> resolveMentioned(event, match.channel)
            RecipientRole.REPORTER -> resolveReporter(event, match.channel, issueRecipients)
            RecipientRole.ASSIGNEE -> resolveAssignee(match.channel, issueRecipients)
            RecipientRole.WATCHER -> resolveWatcher(match.channel, issueRecipients)
            RecipientRole.COMPONENT_LEAD -> resolveComponentLead(match.channel, issueRecipients)
            RecipientRole.PREVIOUS_ASSIGNEE -> resolvePreviousAssignee(match.channel, issueRecipients)
            RecipientRole.PROJECT_MEMBER -> resolveProjectMember(match.channel, projectRecipients)
            RecipientRole.PROJECT_ADMIN -> resolveProjectAdmin(match.channel, projectRecipients)
            else -> {
                // RULE_OWNER 및 미래 역할 — 현재 미구현, skip
                log.debug(
                    "역할 skip (미구현) — role={}, eventType={}, issueKey={}",
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
        issueRecipients: IssueRecipients?,
    ): List<ResolvedRecipient> {
        val reporterId: UUID? = event.reporterId ?: issueRecipients?.reporterId
        return reporterId
            ?.let { listOf(ResolvedRecipient(userId = it, channel = channel)) }
            ?: emptyList()
    }

    private fun resolveAssignee(
        channel: Channel,
        issueRecipients: IssueRecipients?,
    ): List<ResolvedRecipient> {
        val assigneeId: UUID? = issueRecipients?.assigneeId
        return assigneeId
            ?.let { listOf(ResolvedRecipient(userId = it, channel = channel)) }
            ?: emptyList()
    }

    private fun resolveWatcher(
        channel: Channel,
        issueRecipients: IssueRecipients?,
    ): List<ResolvedRecipient> =
        issueRecipients?.watcherIds.orEmpty().map { ResolvedRecipient(userId = it, channel = channel) }

    private fun resolveComponentLead(
        channel: Channel,
        issueRecipients: IssueRecipients?,
    ): List<ResolvedRecipient> =
        issueRecipients?.componentLeadIds.orEmpty().map { ResolvedRecipient(userId = it, channel = channel) }

    private fun resolvePreviousAssignee(
        channel: Channel,
        issueRecipients: IssueRecipients?,
    ): List<ResolvedRecipient> {
        val previousAssigneeId: UUID? = issueRecipients?.previousAssigneeId
        return previousAssigneeId
            ?.let { listOf(ResolvedRecipient(userId = it, channel = channel)) }
            ?: emptyList()
    }

    private fun resolveProjectMember(
        channel: Channel,
        projectRecipients: ProjectRecipients?,
    ): List<ResolvedRecipient> =
        projectRecipients?.memberIds.orEmpty().map { ResolvedRecipient(userId = it, channel = channel) }

    private fun resolveProjectAdmin(
        channel: Channel,
        projectRecipients: ProjectRecipients?,
    ): List<ResolvedRecipient> =
        projectRecipients?.adminIds.orEmpty().map { ResolvedRecipient(userId = it, channel = channel) }

    /**
     * 이슈 기반 역할(REPORTER/ASSIGNEE/WATCHER/COMPONENT_LEAD/PREVIOUS_ASSIGNEE)이 포함된 경우에만
     * 포트를 1회 호출해 반환한다 (N+1 방지).
     *
     * REPORTER 는 event.reporterId 가 있어도 다른 이슈 기반 역할이 함께 요청되면
     * 어차피 1회 조회가 발생한다. 조건 단순화: "포트가 필요한 이슈 기반 역할 중 하나라도 있으면 조회".
     * - REPORTER 는 reporterId 가 없을 때만 포트 필요
     * - ASSIGNEE/WATCHER/COMPONENT_LEAD/PREVIOUS_ASSIGNEE 는 항상 포트 필요
     */
    private fun lazyIssueRecipientsLookup(
        event: NotificationSourceEvent,
        matches: List<PolicyMatch>,
    ): IssueRecipients? {
        val needsPortLookup = matches.any { match ->
            when (match.recipientRole) {
                RecipientRole.REPORTER -> event.reporterId == null
                RecipientRole.ASSIGNEE,
                RecipientRole.WATCHER,
                RecipientRole.COMPONENT_LEAD,
                RecipientRole.PREVIOUS_ASSIGNEE -> true
                else -> false
            }
        }
        if (!needsPortLookup || event.issueKey == null) return null
        return issueRecipientLookupPort.findRecipients(event.issueKey)
    }

    /**
     * 프로젝트 기반 역할(PROJECT_MEMBER/PROJECT_ADMIN)이 포함된 경우에만
     * 프로젝트 포트를 1회 호출해 반환한다 (N+1 방지).
     */
    private fun lazyProjectRecipientsLookup(
        event: NotificationSourceEvent,
        matches: List<PolicyMatch>,
    ): ProjectRecipients? {
        val projectBasedRoles = setOf(RecipientRole.PROJECT_MEMBER, RecipientRole.PROJECT_ADMIN)
        val needsPortLookup = matches.any { it.recipientRole in projectBasedRoles }
        if (!needsPortLookup || event.projectKey == null) return null
        return projectRecipientLookupPort.findProjectRecipients(event.projectKey)
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

// 구독 필터 통합 테스트 전용 — cross-BC 포트 stub 빈 설정 (watcher=[USER_A, USER_B])

package com.bts.notification.worker

import com.bts.notification.worker.NotificationWorkerSubscriptionFilterTest.Companion.USER_A
import com.bts.notification.worker.NotificationWorkerSubscriptionFilterTest.Companion.USER_B
import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import com.bts.shared.issue.ProjectRecipientLookupPort
import com.bts.shared.issue.ProjectRecipients
import com.bts.shared.permission.IssueVisibilityPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/**
 * [NotificationWorkerSubscriptionFilterTest] 전용 포트 빈 설정.
 *
 * ## IssueRecipientLookupPort
 * 모든 이슈에 대해 watcher=[USER_A, USER_B] 를 반환한다.
 * 두 명 모두 visibility 필터를 통과하므로, 구독 필터만이 발송 여부를 결정한다.
 *
 * ## IssueVisibilityPort
 * allow-all 구현 — 구독 필터 단독 검증이 목적이므로 visibility 제외 없이 전원 통과.
 *
 * ## UserLookupPort
 * EmailChannelSender 가 주입받으므로 fail-safe stub 제공.
 *
 * ## ProjectRecipientLookupPort
 * PROJECT_MEMBER 역할 검증 범위 외이므로 빈 반환.
 */
@TestConfiguration
class SubscriptionFilterTestPortsConfig {
    /**
     * 모든 이슈에 대해 watcher=[USER_A, USER_B] 를 반환하는 이슈 포트 빈.
     */
    @Bean
    fun issueRecipientLookupPort(): IssueRecipientLookupPort =
        object : IssueRecipientLookupPort {
            override fun findRecipients(issueKey: String): IssueRecipients =
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    watcherIds = listOf(USER_A, USER_B),
                    componentLeadIds = emptyList(),
                    previousAssigneeId = null,
                )
        }

    /**
     * allow-all IssueVisibilityPort — USER_A, USER_B 모두 통과.
     *
     * 구독 필터(user_notification_subs)만 검증하기 위해 visibility 차단은 없다.
     */
    @Bean
    fun issueVisibilityPort(): IssueVisibilityPort =
        object : IssueVisibilityPort {
            override fun filterVisibleUserIds(
                issueKey: String,
                candidateUserIds: Set<UUID>,
            ): Set<UUID> = candidateUserIds
        }

    /**
     * fail-safe UserLookupPort stub.
     *
     * EmailChannelSender 가 cross-BC UserLookupPort 를 주입받으므로 빈이 필요하다.
     * 이 테스트는 실제 이메일 발송을 검증하지 않으므로 exists=false 로 충분하다.
     */
    @Bean
    fun userLookupPort(): com.bts.shared.user.UserLookupPort =
        object : com.bts.shared.user.UserLookupPort {
            override fun exists(userId: UUID): Boolean = false
        }

    /**
     * fail-safe ProjectRecipientLookupPort stub.
     */
    @Bean
    fun projectRecipientLookupPort(): ProjectRecipientLookupPort =
        object : ProjectRecipientLookupPort {
            override fun findProjectRecipients(projectKey: String): ProjectRecipients {
                return ProjectRecipients.empty()
            }
        }
}

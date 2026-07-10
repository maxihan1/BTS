// issue.commented 알림 e2e 통합 테스트 전용 — worker 흐름 검증용 제어 가능 포트 빈 설정
// (BC 격리 유지: issue-tracking/identity-access 모듈 클래스패스 불포함)

package com.bts.notification.worker

import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import com.bts.shared.issue.ProjectRecipientLookupPort
import com.bts.shared.issue.ProjectRecipients
import com.bts.shared.permission.IssueVisibilityPort
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/**
 * [IssueCommentedNotificationIntegrationTest] 전용 포트 빈 설정.
 *
 * notification 모듈은 BC 격리 원칙상 issue-tracking/identity-access 모듈을 클래스패스에 포함하지 않는다.
 * 실 cross-BC adapter 빈 대신, **worker 흐름 검증 목적으로 제어 가능한 포트 빈**을 제공한다.
 *
 * [RecipientResolutionTestPortsConfig] 와 달리 REPORTER/ASSIGNEE 도 함께 고정 반환해
 * V401 시드의 issue.commented × REPORTER/ASSIGNEE/WATCHER/MENTIONED 매트릭스 전체를 검증한다.
 *
 * ## IssueRecipientLookupPort — 고정 수신자 반환형
 * - reporterId: [REPORTER_USER_ID]
 * - assigneeId: [ASSIGNEE_USER_ID]
 * - watcherIds: [[WATCHER_USER_ID], [ACTOR_WATCHER_USER_ID], [VISIBILITY_EXCLUDED_USER_ID]]
 *   - [ACTOR_WATCHER_USER_ID] 는 이벤트 actorId 와 동일 — actor 자기 제외 로직 검증
 *   - [VISIBILITY_EXCLUDED_USER_ID] 는 visibility 필터로 걸러짐
 *
 * ## IssueVisibilityPort — 누출 재현형
 * allow-all 구현은 가짜 그린을 만든다. 이 빈은 [VISIBILITY_EXCLUDED_USER_ID] 를 명시적으로 제외해,
 * worker 흐름이 `IssueVisibilityPort.filterVisibleUserIds` 를 실제로 호출하는지 확인한다.
 */
@TestConfiguration
class IssueCommentedRecipientTestPortsConfig {
    companion object {
        /** 이슈 리포터 userId — REPORTER 역할 정책으로 알림을 받아야 한다. */
        val REPORTER_USER_ID: UUID = UUID.fromString("30303030-3030-3030-3030-303030303030")

        /** 이슈 담당자 userId — ASSIGNEE 역할 정책으로 알림을 받아야 한다. */
        val ASSIGNEE_USER_ID: UUID = UUID.fromString("40404040-4040-4040-4040-404040404040")

        /** 순수 watcher userId — WATCHER 역할 정책으로 알림을 받아야 한다. */
        val WATCHER_USER_ID: UUID = UUID.fromString("50505050-5050-5050-5050-505050505050")

        /**
         * watcher 이면서 동시에 이벤트 actorId 로 사용될 userId.
         *
         * 테스트 단언: watcher 목록에 포함되어 있어도 actor 자기 제외 로직으로
         * notifications 테이블에 **미기록** 되어야 한다.
         */
        val ACTOR_WATCHER_USER_ID: UUID = UUID.fromString("60606060-6060-6060-6060-606060606060")

        /**
         * watcher 이지만 IssueVisibilityPort 에 의해 제외될 userId.
         *
         * 테스트 단언: 이 ID 가 notifications 테이블에 **미기록** 되어야 한다.
         */
        val VISIBILITY_EXCLUDED_USER_ID: UUID = UUID.fromString("70707070-7070-7070-7070-707070707070")
    }

    /**
     * issue.commented 이벤트에 대해 고정된 수신자를 반환하는 이슈 포트 빈.
     */
    @Bean
    fun issueRecipientLookupPort(): IssueRecipientLookupPort =
        object : IssueRecipientLookupPort {
            override fun findRecipients(issueKey: String): IssueRecipients =
                IssueRecipients(
                    reporterId = REPORTER_USER_ID,
                    assigneeId = ASSIGNEE_USER_ID,
                    watcherIds = listOf(WATCHER_USER_ID, ACTOR_WATCHER_USER_ID, VISIBILITY_EXCLUDED_USER_ID),
                    componentLeadIds = emptyList(),
                    previousAssigneeId = null,
                )
        }

    /**
     * fail-safe 프로젝트 수신자 포트 빈 — 이 테스트는 PROJECT_MEMBER/PROJECT_ADMIN 역할을 검증하지 않는다.
     */
    @Bean
    fun projectRecipientLookupPort(): ProjectRecipientLookupPort =
        object : ProjectRecipientLookupPort {
            override fun findProjectRecipients(projectKey: String): ProjectRecipients = ProjectRecipients.empty()
        }

    /**
     * 누출 재현형 IssueVisibilityPort 빈 — worker 경로 배선 검증 핵심.
     *
     * [VISIBILITY_EXCLUDED_USER_ID] 를 후보에서 명시적으로 제거한다. 나머지 후보는 모두 통과시킨다.
     */
    @Bean
    fun issueVisibilityPort(): IssueVisibilityPort =
        object : IssueVisibilityPort {
            override fun filterVisibleUserIds(
                issueKey: String,
                candidateUserIds: Set<UUID>,
            ): Set<UUID> = candidateUserIds - VISIBILITY_EXCLUDED_USER_ID
        }
}

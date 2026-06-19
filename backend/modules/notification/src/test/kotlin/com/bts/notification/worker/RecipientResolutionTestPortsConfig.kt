// 수신자 해석 통합 테스트 전용 — worker 흐름 검증용 제어 가능 포트 빈 설정
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
 * [RecipientResolutionIntegrationTest] 전용 포트 빈 설정.
 *
 * notification 모듈은 BC 격리 원칙상 issue-tracking/identity-access 모듈을 클래스패스에 포함하지 않는다.
 * 실 cross-BC adapter 빈 대신, **worker 흐름 검증 목적으로 제어 가능한 포트 빈**을 제공한다.
 *
 * ## IssueVisibilityPort — 누출 재현형 (핵심)
 * allow-all 구현은 가짜 그린을 만든다 — visibility 필터가 비활성화된 것과 동일해 누출을 탐지 못 한다.
 * 이 빈은 [EXCLUDED_USER_ID] 를 명시적으로 제외하도록 구현해,
 * worker 흐름이 `IssueVisibilityPort.filterVisibleUserIds` 를 실제로 호출하고
 * 제외된 userId 가 `notifications` 테이블에 기록되지 않는지 단언할 수 있게 한다.
 *
 * 실제 보안 판정 정확성(권한 매트릭스 + 보안등급 결합)은 T6 `IssueVisibilityAdapterIntegrationTest` 가
 * 실 DB 로 검증했다. 이 빈의 역할은 "worker 경로에 visibility 필터가 배선되어 있음" 을 확인하는 것이다.
 *
 * ## IssueRecipientLookupPort — 고정 수신자 반환형
 * issue.transitioned 이벤트 시나리오에서 WATCHER 역할 수신자를 제공한다.
 * 두 명의 watcher 중 한 명이 [EXCLUDED_USER_ID] 이고 나머지 한 명이 [VISIBLE_USER_ID] 이다.
 *
 * ## ProjectRecipientLookupPort — fail-safe (빈 반환)
 * 이 테스트는 PROJECT_MEMBER 역할을 검증하지 않으므로 빈 수신자를 반환한다.
 */
@TestConfiguration
class RecipientResolutionTestPortsConfig {
    companion object {
        /**
         * visibility 필터에 의해 제외될 userId.
         *
         * IssueVisibilityPort 빈이 이 ID 를 candidate 에서 제거한다.
         * 테스트 단언: 이 ID 가 notifications 테이블에 **미기록** 되어야 한다.
         */
        val EXCLUDED_USER_ID: UUID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")

        /**
         * visibility 필터를 통과해 알림을 받는 userId.
         *
         * 테스트 단언: 이 ID 가 notifications 테이블에 **기록** 되어야 한다.
         */
        val VISIBLE_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        /**
         * 이벤트 발화자 userId — actor 제외 로직 검증용.
         *
         * 테스트 단언: 이 ID 가 notifications 테이블에 **미기록** 되어야 한다 (self-exclude).
         */
        val ACTOR_USER_ID: UUID = UUID.fromString("a0a0a0a0-a0a0-a0a0-a0a0-a0a0a0a0a0a0")
    }

    /**
     * issue.transitioned 이벤트에 대해 고정된 수신자를 반환하는 이슈 포트 빈.
     *
     * - watcherIds: [[VISIBLE_USER_ID], [EXCLUDED_USER_ID], [ACTOR_USER_ID]] 세 명
     *   - [ACTOR_USER_ID]: actor 자기 제외 로직으로 걸러짐
     *   - [EXCLUDED_USER_ID]: visibility 필터로 걸러짐
     *   - [VISIBLE_USER_ID]: 통과 → notifications 기록 대상
     * - 그 외 역할 필드는 null/empty (이 테스트 범위 외)
     */
    @Bean
    fun issueRecipientLookupPort(): IssueRecipientLookupPort =
        object : IssueRecipientLookupPort {
            override fun findRecipients(issueKey: String): IssueRecipients =
                IssueRecipients(
                    reporterId = null,
                    assigneeId = null,
                    watcherIds = listOf(VISIBLE_USER_ID, EXCLUDED_USER_ID, ACTOR_USER_ID),
                    componentLeadIds = emptyList(),
                    previousAssigneeId = null,
                )
        }

    /**
     * fail-safe 프로젝트 수신자 포트 빈 — 이 테스트는 PROJECT_MEMBER 역할을 검증하지 않는다.
     */
    @Bean
    fun projectRecipientLookupPort(): ProjectRecipientLookupPort =
        object : ProjectRecipientLookupPort {
            override fun findProjectRecipients(projectKey: String): ProjectRecipients =
                ProjectRecipients.empty()
        }

    /**
     * 누출 재현형 IssueVisibilityPort 빈 — worker 경로 배선 검증 핵심.
     *
     * [EXCLUDED_USER_ID] 를 후보에서 명시적으로 제거한다.
     * 나머지 후보는 모두 통과시킨다.
     *
     * ## 실 판정 계약과의 관계
     * 이 빈은 "필터가 worker 경로에 배선되어 동작한다" 를 확인하기 위한 제어 가능 구현이다.
     * 프로덕션 보안 판정 정확성(권한 매트릭스 + 보안등급 결합)은
     * T6 `IssueVisibilityAdapterIntegrationTest` 가 identity-access 실 DB 로 검증한다.
     */
    @Bean
    fun issueVisibilityPort(): IssueVisibilityPort =
        object : IssueVisibilityPort {
            override fun filterVisibleUserIds(
                issueKey: String,
                candidateUserIds: Set<UUID>,
            ): Set<UUID> = candidateUserIds - EXCLUDED_USER_ID
        }
}

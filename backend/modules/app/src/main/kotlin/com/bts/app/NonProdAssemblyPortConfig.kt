// 비-prod 조립에서 빈이 0개였던 cross-BC 포트 3종을 prod 실구현 그대로 등록하는 조립 전용 설정

package com.bts.app

import com.atlas.bts.identity.permission.IdentityAccessAutomationPermissionResolver
import com.atlas.bts.identity.permission.PermissionSchemeRepository
import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.issue.adapter.outbound.automation.AutomationIssueMutationAdapter
import com.bts.issue.adapter.outbound.automation.AutomationIssueSnapshotAdapter
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.IssueSnapshotPort
import com.bts.shared.permission.AutomationPermissionResolver
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.transaction.support.TransactionTemplate

/**
 * 비-prod 조립 컨텍스트에서 **빈이 0개**였던 cross-BC 포트 3종을 채운다.
 *
 * ## 왜 비어 있었나
 * 세 포트의 유일한 구현이 `@Component @Profile("prod")` 다. 원 설계는 *"non-prod 에서는 소비 모듈이
 * 자신의 컨텍스트에 fail-safe stub 을 등록한다(consumer-owns-stub)"* 였는데
 * (`IdentityAccessAutomationPermissionResolver` KDoc), automation 의 그 스텁은 **`src/test` 에만 존재**한다.
 * 각 BC 가 독립 컨텍스트로 뜨던 시절엔 드러나지 않았지만, 조립 모듈이 생기며 비-prod 조립에는
 * 구현이 하나도 없고 소비자(automation `@Service`/`@Component`)는 non-null 로 요구해
 * `NoSuchBeanDefinitionException` 으로 부팅이 죽는다.
 *
 * ## 왜 스텁이 아니라 실구현인가 (2026-07-29 Maxi 결정 D7)
 * `IssueMutationPort`/`IssueSnapshotPort` 는 권한 게이트가 아니라 **기능 통로**다 — 자동화 규칙이 이슈를
 * 실제로 읽고 바꾸는 경로. 여기에 스텁을 두면 dev 에서 자동화가 **조용히 아무것도 안 하고**, 손검증하는
 * 사람은 그것을 새 고장으로 오인한다. 이 PR 의 목적 자체가 "실 백엔드 손검증 경로를 여는 것"이라
 * 스텁은 목적을 깬다. `AutomationPermissionResolver` 도 D5(비-prod 실제 판정)와 방향을 맞춘다.
 *
 * ## prod 와 상호 배타 — 중복이 생기지 않는 이유
 * 아래 `@Bean` 은 `@Profile("!prod")`, 실구현 클래스의 `@Component` 는 `@Profile("prod")` 다.
 * 두 조건이 배타이므로 어느 프로파일에서도 활성 빈은 **정확히 1개**다
 * (`NonProdAssemblyBootTest` 가 11종 전부에 대해 이를 단언한다).
 *
 * ## ★ 생성자 변경은 여기서 컴파일 에러로 드러난다 — 그게 이 방식을 택한 이유다
 * 조립 모듈이 다른 BC 의 구현 클래스 생성자를 직접 호출하므로, 그 생성자가 바뀌면 **빌드가 막힌다**.
 * 스텁을 따로 뒀다면 시그니처가 갈라져도 조용히 굴러가다 prod 에서만 다르게 동작했을 것이다.
 *
 * ## BC 격리 규칙의 명시적 예외
 * `CLAUDE.md §핵심 패턴` 은 BC 간 직접 import 를 금지하지만, **조립 모듈은 그 예외다** — 9개 BC 를 한
 * 컨텍스트로 결선하는 것이 이 모듈의 유일한 직무이고, [BtsApplication] 이 이미 두 BC 의 `*Application` 을
 * import 하는 선례가 있다. 근거는 ADR `2026-07-29-assembly-nonprod-bean-wiring`.
 */
@Configuration
@Profile("!prod")
class NonProdAssemblyPortConfig {
    /**
     * 자동화 권한 판정 — prod 와 동일하게 멤버십 + 권한 스킴 매트릭스로 실제 평가한다.
     * 소비자. `AutomationRuleService` · `RuleExecutionService` · `GitWebhookRegistrationService`.
     */
    @Bean
    fun automationPermissionResolver(
        projectDirectory: ProjectDirectory,
        membershipRepo: ProjectMembershipRepository,
        permissionSchemeRepo: PermissionSchemeRepository,
    ): AutomationPermissionResolver =
        IdentityAccessAutomationPermissionResolver(
            projectDirectory = projectDirectory,
            membershipRepo = membershipRepo,
            permissionSchemeRepo = permissionSchemeRepo,
        )

    /**
     * 자동화 액션의 이슈 변경 통로. 소비자. `ActionExecutor` · `SlackInteractionService`.
     * `ObjectMapper` 는 Boot 자동설정 단일 빈이다(main 전량에 커스텀 `@Bean ObjectMapper` 0건 — 실측).
     */
    @Bean
    fun issueMutationPort(
        issueApplicationService: IssueApplicationService,
        commentApplicationService: CommentApplicationService,
        objectMapper: ObjectMapper,
        transactionTemplate: TransactionTemplate,
    ): IssueMutationPort =
        AutomationIssueMutationAdapter(
            issueApplicationService = issueApplicationService,
            commentApplicationService = commentApplicationService,
            objectMapper = objectMapper,
            transactionTemplate = transactionTemplate,
        )

    /**
     * 자동화 조건 평가용 이슈 스냅샷 조회. 소비자. `ActionExecutor`.
     * 조회는 이슈 가시성 게이트를 거치므로 비-prod 라고 더 열리지 않는다.
     */
    @Bean
    fun issueSnapshotPort(issueApplicationService: IssueApplicationService): IssueSnapshotPort =
        AutomationIssueSnapshotAdapter(issueApplicationService = issueApplicationService)
}

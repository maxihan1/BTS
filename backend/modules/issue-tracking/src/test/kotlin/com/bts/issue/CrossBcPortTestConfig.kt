// issue-tracking 단독 full-boot 시 부재한 cross-BC outbound port 를 Mockito stub 빈으로 채우는 공통 테스트 설정

package com.bts.issue

import com.bts.shared.membership.ProjectMembershipPort
import com.bts.shared.membership.ProjectMembershipWritePort
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowTransitionPort
import com.bts.workflow.scheme.application.port.IssueTypeUsagePort
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * `classes = [IssueTrackingApplication::class]` 로 부팅하는 `@SpringBootTest` 전용 공통 mock 설정.
 *
 * ## 배경
 * `IssueTrackingApplication` 은 `com.bts.issue` 패키지 전체를 component-scan 하므로, 다른 BC
 * (project-workflow / identity-access) 가 구현 빈을 제공하는 outbound port 를 주입받는 서비스가
 * 하나라도 있으면 issue-tracking 단독 부팅 시 그 빈이 존재하지 않아
 * `NoSuchBeanDefinitionException` 으로 컨텍스트 부팅이 실패한다.
 *
 * `IssueTrackingApplicationContextTest` 등 8개 `@SpringBootTest` 클래스가 매번 동일한 5개
 * `@MockBean` 필드([WorkflowTransitionPort]·[WorkflowKeyResolver]·[WorkflowStateCatalog]·
 * [UserLookupPort]·[IssueTypeUsagePort])를 반복 선언해왔다. 이 설정을 `@SpringBootTest(classes=...)`
 * 에 추가하면 반복 없이 동일 stub 을 재사용할 수 있다([com.bts.issue.project.web.Require2faTestPermissionConfig]
 * 와 동형 — `@TestConfiguration` + `@Bean` factory 패턴).
 *
 * ## ProjectMembershipWritePort — FR-PJ-01 신규 (issue-tracking test task-9 회귀 수리)
 * [com.bts.issue.project.application.ProjectCreateApplicationService] 가 새로 소비하는
 * cross-BC 포트(identity-access BC 구현). 아무 테스트도 이 mock 들의 호출을 검증(verify)하거나
 * 동작을 stub(`when`) 하지 않으므로, `@MockBean` 대신 평범한 `@Bean mock()` 팩토리로 충분하다.
 *
 * ## ProjectMembershipPort — FR-PJ PR-3 신규 (issue-tracking 단독 부팅 회귀 수리)
 * [com.bts.issue.project.query.ProjectQueryService] 가 새로 소비하는 cross-BC 읽기 포트
 * (identity-access BC 구현 — [com.bts.issue.project.query.ProjectQueryService.listAccessible] 가
 * 사용자의 프로젝트 멤버십 key 집합을 조회한다). 8개 `@SpringBootTest` 클래스 중 어느 것도 프로젝트
 * 목록 조회 엔드포인트를 호출하지 않으므로, Mockito 기본 stub(호출 시 빈 `Set` 반환 — fail-closed
 * 방향과 일치)으로 컨텍스트 로드만 충족하면 된다. 실제 멤버십 데이터가 필요한 시나리오 검증은
 * [com.bts.issue.project.web.ProjectQueryControllerTest] (`@WebMvcTest` 슬라이스)가 담당한다.
 */
@TestConfiguration
class CrossBcPortTestConfig {
    @Bean
    fun workflowTransitionPort(): WorkflowTransitionPort = mock(WorkflowTransitionPort::class.java)

    @Bean
    fun workflowKeyResolver(): WorkflowKeyResolver = mock(WorkflowKeyResolver::class.java)

    @Bean
    fun workflowStateCatalog(): WorkflowStateCatalog = mock(WorkflowStateCatalog::class.java)

    @Bean
    fun userLookupPort(): UserLookupPort = mock(UserLookupPort::class.java)

    @Bean
    fun issueTypeUsagePort(): IssueTypeUsagePort = mock(IssueTypeUsagePort::class.java)

    @Bean
    fun projectMembershipWritePort(): ProjectMembershipWritePort = mock(ProjectMembershipWritePort::class.java)

    @Bean
    fun projectMembershipPort(): ProjectMembershipPort = mock(ProjectMembershipPort::class.java)
}

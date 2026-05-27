// WorkflowResolver outbound port 구현체 — 프로젝트+이슈타입 기준 워크플로우 결정

package com.bts.workflow.scheme.adapter.inbound

import com.bts.issue.type.domain.IssueTypeKey
import com.bts.workflow.domain.Workflow
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.exception.ProjectNotFoundException
import com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.scheme.port.outbound.WorkflowResolver
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [WorkflowResolver] 구현체 — 프로젝트 키 + 이슈 타입 키 기준 워크플로우 결정.
 *
 * ## 책임 (resolveFor 4 시나리오)
 * - **S5**: assignment 있음 + issueTypeKey 명시적 매핑 → 해당 workflow 반환.
 * - **EC-2**: assignment 있음 + issueTypeKey 매핑 없음(또는 null) → default mapping(issueTypeId IS NULL) workflow 반환.
 *   default mapping 도 없으면 [WorkflowSchemeNoDefaultException] 발생.
 * - **EC-1 D10**: assignment 없는 신규 프로젝트 → software-scheme 자동 배정([WorkflowSchemeApplicationService.assignToProject]) 후 resolve.
 * - **EC-7**: projectKey 에 해당하는 프로젝트 없음 → [ProjectNotFoundException] 발생.
 *
 * ## 트랜잭션 정책 (Propagation.MANDATORY)
 * 호출자(Application Service 또는 동등 계층)가 반드시 활성 트랜잭션 안에서 호출해야 한다.
 * 트랜잭션 없이 호출하면 Spring 이 [org.springframework.transaction.IllegalTransactionStateException] 을 던진다.
 * 읽기 전용(readOnly=true) — write 는 EC-1 D10 auto-assign 시 [WorkflowSchemeApplicationService] 가 수행한다.
 *
 * ## BC 격리 — ProjectLookupPort 임시 stub
 * [ProjectLookupPort] 구현체([com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter]) 는
 * `projects` 테이블을 직접 읽는 임시 stub 이다.
 * **FR-PM-04 정식 cross-BC port 도입 시 이 의존성을 project-management 공개 API 구현체로 교체한다.**
 *
 * ## PR #6 learning — @Service 구체 클래스 부착
 * interface 가 아닌 구체 클래스에 @Service 를 부착해야 Spring AOP 프록시가 @Transactional 을 정상 적용한다.
 *
 * @param projectLookup projectKey → projects.id(UUID) 변환 port (임시 stub — FR-PM-04 교체 예정).
 * @param assignmentRepo 프로젝트-스킴 할당 조회.
 * @param mappingRepo 스킴-이슈타입 매핑 조회.
 * @param workflowRepo workflow aggregate 조회.
 * @param schemeAS EC-1 D10 auto-assign 에 사용하는 Application Service.
 */
@Service
class WorkflowResolverImpl(
    private val projectLookup: ProjectLookupPort,
    private val assignmentRepo: ProjectWorkflowSchemeAssignmentRepository,
    private val mappingRepo: SchemeIssueTypeMappingRepository,
    private val workflowRepo: WorkflowRepository,
    private val schemeAS: WorkflowSchemeApplicationService,
) : WorkflowResolver {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트와 이슈 타입에 적합한 워크플로우를 반환한다.
     *
     * 결정 순서.
     * 1. [ProjectLookupPort.findIdByKey] 로 projectId(UUID) 조회 — 없으면 [ProjectNotFoundException] (EC-7).
     * 2. assignment 조회 — 없으면 software-scheme 자동 배정 (EC-1 D10).
     * 3. issueTypeKey 명시 시 [SchemeIssueTypeMappingRepository.findByIssueTypeKey] 로 명시적 매핑 조회 (S5).
     *    없으면 default mapping([SchemeIssueTypeMappingRepository.findDefaultMapping]) 조회 (EC-2).
     * 4. [WorkflowRepository.findById] 로 workflow aggregate 반환 — mapping 은 있으나 workflow 없으면 데이터 무결성 오류.
     *
     * @param projectKey 워크플로우를 조회할 프로젝트 키.
     * @param issueTypeKey 워크플로우를 조회할 이슈 타입 키. null 이면 default mapping 직접 조회.
     * @return 해당 프로젝트+타입에 적용된 [Workflow].
     * @throws ProjectNotFoundException projectKey 에 해당하는 프로젝트가 없을 때 (EC-7).
     * @throws WorkflowSchemeNoDefaultException 명시적 + default mapping 모두 없을 때 (EC-2).
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun resolveFor(
        projectKey: ProjectKey,
        issueTypeKey: IssueTypeKey?,
    ): Workflow {
        // 1. projectKey → projectId(UUID) 변환. 없으면 EC-7.
        val projectId = projectLookup.findIdByKey(projectKey)
            ?: throw ProjectNotFoundException(projectKey.value)

        log.debug("resolveFor projectKey={} projectId={} issueTypeKey={}", projectKey.value, projectId, issueTypeKey?.value)

        // 2. assignment 조회. 없으면 EC-1 D10 — software-scheme 자동 배정.
        val assignment = assignmentRepo.findByProjectId(projectId)
            ?: run {
                log.info("resolveFor: no assignment for projectId={}, auto-assigning software-scheme (EC-1 D10)", projectId)
                schemeAS.assignToProject(
                    actor = WorkflowSchemeApplicationService.SYSTEM_ACTOR,
                    projectId = projectId,
                    projectKey = projectKey.value,
                    schemeKey = WorkflowSchemeApplicationService.SOFTWARE_SCHEME_KEY,
                )
            }

        val schemeId = assignment.workflowSchemeId

        // 3. 매핑 조회 — S5(명시적) 우선, 없으면 EC-2(default fallback).
        val mapping = if (issueTypeKey != null) {
            mappingRepo.findByIssueTypeKey(schemeId, issueTypeKey.value)
                ?: mappingRepo.findDefaultMapping(schemeId)
        } else {
            mappingRepo.findDefaultMapping(schemeId)
        } ?: throw WorkflowSchemeNoDefaultException(schemeId.value.toString())

        // 4. workflow aggregate 반환. 매핑에 있는 workflowId 가 없으면 데이터 무결성 위반.
        return workflowRepo.findById(mapping.workflowId)
            ?: error("Workflow ${mapping.workflowId} 가 mapping 에 있지만 workflows 에 없음 — 데이터 무결성 위반")
    }
}

// 워크플로우 어플리케이션 서비스 — 트랜잭션 경계 + 엔진 호출 위임

package com.bts.workflow.application

import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.repository.WorkflowRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 워크플로우 도메인 유스케이스 진입점.
 *
 * 컨트롤러 레이어와 엔진/리포지토리 레이어 사이의 트랜잭션 경계를 책임진다.
 * [WorkflowEngine.plan] 이 [org.springframework.transaction.annotation.Propagation.MANDATORY] 를 선언하므로,
 * 이 서비스가 활성 트랜잭션을 열고 엔진을 호출한다.
 *
 * ### 트랜잭션 정책
 * - [planTransition] — 쓰기 가능 트랜잭션. 엔진 MANDATORY 요구 충족.
 * - [listWorkflows] / [getWorkflow] — `readOnly = true` 읽기 전용 트랜잭션.
 *
 * @param workflowEngine 전환 계획 계산 엔진
 * @param workflowRepository 워크플로우 조회 리포지토리
 */
@Service
class WorkflowApplicationService(
    private val workflowEngine: WorkflowEngine,
    private val workflowRepository: WorkflowRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장된 전체 워크플로우 목록을 반환한다.
     *
     * @return 워크플로우 도메인 객체 목록
     */
    @Transactional(readOnly = true)
    fun listWorkflows(): List<Workflow> {
        log.debug("WorkflowApplicationService.listWorkflows")
        return workflowRepository.findAll()
    }

    /**
     * [projectId] 프로젝트가 쓸 수 있는 워크플로우 목록을 반환한다 — 전역 공유 + 그 프로젝트 전용.
     *
     * ★ [listWorkflows] 는 전량을 준다. 프로젝트 설정 화면이 그걸 그대로 쓰면 남의 프로젝트 전용
     * 워크플로우가 이름째 보인다(FR-WF-08). 프로젝트 스코프 경로는 이쪽을 탄다.
     *
     * @param projectId 대상 프로젝트 `projects.id`.
     * @return 전역 + 해당 프로젝트 소유 워크플로우.
     */
    @Transactional(readOnly = true)
    fun listWorkflowsForProject(projectId: UUID): List<Workflow> {
        log.debug("WorkflowApplicationService.listWorkflowsForProject projectId={}", projectId)
        return workflowRepository.findAllForProject(projectId)
    }

    /**
     * 지정된 key 의 워크플로우를 단건 조회한다.
     *
     * @param key 워크플로우 식별 키
     * @return 워크플로우 도메인 객체
     * @throws WorkflowNotFoundException key 에 해당하는 워크플로우가 없을 때
     */
    @Transactional(readOnly = true)
    fun getWorkflow(key: String): Workflow {
        log.debug("WorkflowApplicationService.getWorkflow key={}", key)
        return workflowRepository.findByKey(key)
            ?: throw WorkflowNotFoundException(key)
    }

    /**
     * 워크플로우 전환 계획을 계산한다.
     *
     * [WorkflowEngine.plan] 의 MANDATORY 전파 요구를 충족하기 위해
     * 이 메서드가 트랜잭션을 개시한다.
     *
     * @param request 전환 요청 도메인 DTO
     * @return 검증 통과 후 계산된 전환 계획
     * @throws WorkflowNotFoundException 워크플로우·전환 정의를 찾을 수 없을 때
     * @throws com.bts.workflow.domain.exception.WorkflowValidatorFailureException Validator 가 전환을 거부할 때
     */
    @Transactional
    fun planTransition(request: TransitionRequest): TransitionPlan {
        log.debug(
            "WorkflowApplicationService.planTransition workflowKey={} issueKey={} {}→{}",
            request.workflowKey,
            request.issueKey,
            request.fromStateKey,
            request.toStateKey,
        )
        return workflowEngine.plan(request)
    }
}

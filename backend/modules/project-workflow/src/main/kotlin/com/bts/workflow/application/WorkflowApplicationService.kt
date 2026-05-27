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
 * @param workflowEngine 전이 계획 계산 엔진
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
     * 워크플로우 전이 계획을 계산한다.
     *
     * [WorkflowEngine.plan] 의 MANDATORY 전파 요구를 충족하기 위해
     * 이 메서드가 트랜잭션을 개시한다.
     *
     * @param request 전이 요청 도메인 DTO
     * @return 검증 통과 후 계산된 전이 계획
     * @throws WorkflowNotFoundException 워크플로우·전이 정의를 찾을 수 없을 때
     * @throws com.bts.workflow.domain.exception.WorkflowValidatorFailureException Validator 가 전이를 거부할 때
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

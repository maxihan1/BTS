// WorkflowController — 워크플로우 REST API 3종 (목록/단건/전이/캐시무효화)

package com.bts.workflow.web

import com.bts.workflow.application.WorkflowApplicationService
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.dto.TransitionRequest
import com.bts.workflow.web.dto.TransitionRequestDto
import com.bts.workflow.web.dto.TransitionResponseDto
import com.bts.workflow.web.dto.WorkflowDto
import com.bts.workflow.web.dto.toDto
import io.konform.validation.Invalid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 워크플로우 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - GET  /api/v1/workflows             — 전체 워크플로우 목록 조회
 * - GET  /api/v1/workflows/{key}       — 워크플로우 단건 조회 (계층 구조)
 * - POST /api/v1/workflows/{key}/transitions — 워크플로우 전이 계획 계산
 * - POST /api/v1/workflows/cache/invalidate  — 캐시 무효화 (WORKFLOW_MANAGE 권한 필요)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다 (learning #91).
 * 트랜잭션 개시는 [WorkflowApplicationService] 가 담당한다.
 *
 * @param workflowApplicationService 워크플로우 유스케이스 서비스
 * @param workflowCache 워크플로우 인메모리 캐시 (무효화 용도)
 */
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowController(
    private val workflowApplicationService: WorkflowApplicationService,
    private val workflowCache: WorkflowCache,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장된 전체 워크플로우 목록을 반환한다.
     *
     * @return 200 + 워크플로우 목록 `{ "data": [...] }`
     */
    @GetMapping
    fun listWorkflows(): ResponseEntity<DataResponse<List<WorkflowDto>>> {
        log.debug("WorkflowController.listWorkflows")
        val workflows = workflowApplicationService.listWorkflows().map { it.toDto() }
        return ResponseEntity.ok(DataResponse(data = workflows))
    }

    /**
     * 지정된 key 의 워크플로우를 단건 조회한다.
     *
     * @param key 워크플로우 식별 키
     * @return 200 + 워크플로우 DTO `{ "data": { ... } }`
     * @throws com.bts.workflow.domain.exception.WorkflowNotFoundException key 에 해당하는 워크플로우가 없을 때 (→ 404)
     */
    @GetMapping("/{key}")
    fun getWorkflow(@PathVariable key: String): ResponseEntity<DataResponse<WorkflowDto>> {
        log.debug("WorkflowController.getWorkflow key={}", key)
        val workflow = workflowApplicationService.getWorkflow(key)
        return ResponseEntity.ok(DataResponse(data = workflow.toDto()))
    }

    /**
     * 워크플로우 전이 계획을 계산한다.
     *
     * 트랜잭션 경계는 [WorkflowApplicationService.planTransition] 이 담당한다.
     *
     * @param key 적용할 워크플로우 키 (경로 변수)
     * @param body 전이 요청 바디
     * @return 200 + 전이 계획 `{ "data": { ... } }`
     */
    @PostMapping("/{key}/transitions")
    fun plan(
        @PathVariable key: String,
        @RequestBody body: TransitionPlanRequestBody,
    ): ResponseEntity<DataResponse<TransitionResponseDto>> {
        log.debug("WorkflowController.plan key={} body={}", key, body)

        val dto = TransitionRequestDto(
            toStateKey = body.toStateKey,
            transitionName = body.transitionName,
            fields = body.fields,
            version = body.version,
        )
        val validation = dto.validate()
        if (validation is Invalid) {
            val messages = validation.errors.joinToString("; ") { it.message }
            throw IllegalArgumentException("전이 요청 검증 실패: $messages")
        }

        val req = TransitionRequest(
            workflowKey = key,
            issueKey = body.issueKey,
            fromStateKey = body.fromStateKey,
            toStateKey = body.toStateKey,
            transitionName = body.transitionName,
            actorId = body.actorId,
            issueFields = body.fields,
            actorRoles = body.actorRoles,
            version = body.version,
        )

        val plan = workflowApplicationService.planTransition(req)
        return ResponseEntity.ok(DataResponse(data = plan.toDto()))
    }

    /**
     * 지정된 key 의 워크플로우 캐시를 무효화한다.
     *
     * WORKFLOW_MANAGE 권한이 없으면 403 Forbidden 을 반환한다.
     * 캐시 무효화는 캐시 레이어 직접 호출로 처리한다 (DB 트랜잭션 불필요).
     *
     * @param body 무효화할 워크플로우 키를 담은 바디
     * @return 200 + `{ "data": null }`
     */
    @PostMapping("/cache/invalidate")
    @PreAuthorize("hasAuthority('WORKFLOW_MANAGE')")
    fun invalidateCache(@RequestBody body: CacheInvalidateRequest): ResponseEntity<DataResponse<Nothing?>> {
        log.info("WorkflowController.invalidateCache key={}", body.key)
        workflowCache.invalidate(body.key)
        return ResponseEntity.ok(DataResponse(data = null))
    }
}

/**
 * 전이 계획 요청 바디.
 *
 * @property toStateKey 전이 목표 상태 키
 * @property transitionName 실행할 전이 이름
 * @property fields 이슈 커스텀 필드 스냅샷 (기본값 빈 Map)
 * @property version 낙관적 잠금 버전
 * @property issueKey 전이 대상 이슈 키
 * @property fromStateKey 이슈의 현재 상태 키
 * @property actorId 전이를 실행하는 사용자 ID
 * @property actorRoles 실행자의 역할 집합
 */
data class TransitionPlanRequestBody(
    val toStateKey: String,
    val transitionName: String,
    val fields: Map<String, Any?> = emptyMap(),
    val version: Long,
    val issueKey: String,
    val fromStateKey: String,
    val actorId: String,
    val actorRoles: Set<String> = emptySet(),
)

/**
 * 캐시 무효화 요청 바디.
 *
 * @property key 무효화할 워크플로우 식별 키
 */
data class CacheInvalidateRequest(val key: String)

/**
 * 성공 응답 래퍼.
 *
 * @param T 응답 데이터 타입
 * @property data 응답 페이로드
 */
data class DataResponse<T>(val data: T)

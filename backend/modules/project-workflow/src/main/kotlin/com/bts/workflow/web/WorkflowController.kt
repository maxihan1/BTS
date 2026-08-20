// WorkflowController — 워크플로우 REST API (목록/단건/전환계획/전환정의 CRUD/워크플로우 CRUD/캐시무효화)

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.application.WorkflowApplicationService
import com.bts.workflow.application.WorkflowCommandService
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.web.dto.CreateWorkflowRequest
import com.bts.workflow.web.dto.DuplicateWorkflowRequest
import com.bts.workflow.web.dto.TransitionDefinitionRequest
import com.bts.workflow.web.dto.TransitionRequestDto
import com.bts.workflow.web.dto.TransitionResponse
import com.bts.workflow.web.dto.TransitionResponseDto
import com.bts.workflow.web.dto.UpdateWorkflowRequest
import com.bts.workflow.web.dto.WorkflowDto
import com.bts.workflow.web.dto.toDto
import com.bts.workflow.web.dto.toTransitionResponse
import io.konform.validation.Invalid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 워크플로우 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - GET  /api/v1/workflows             — 전체 워크플로우 목록 조회
 * - GET  /api/v1/workflows/{key}       — 워크플로우 단건 조회 (계층 구조)
 * - POST /api/v1/workflows/{key}/transitions/plan — 워크플로우 전환 계획 계산
 * - POST /api/v1/workflows/{key}/transitions — 전환 정의 생성
 * - PUT  /api/v1/workflows/{key}/transitions/{transitionId} — 전환 정의 수정
 * - DELETE /api/v1/workflows/{key}/transitions/{transitionId} — 전환 정의 삭제
 * - POST /api/v1/workflows/cache/invalidate  — 캐시 무효화 (워크플로우 정의 UPDATE 권한 필요)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다 (learning #91).
 * 트랜잭션 개시는 [WorkflowApplicationService] 가 담당한다.
 *
 * ### `TooManyFunctions` 억제 사유
 * 전환 정의 CRUD 3종이 붙어 함수 수가 detekt 한도(11)에 닿았다. 하위 경로 단위로
 * `WorkflowTransitionController` 를 떼는 것이 이 BC 의 관례이고
 * (`WorkflowStatusCompositionController` 가 그 선례) **다음 PR 의 몫**이다 — 이 PR 의 파일
 * 범위(Task 8)에 새 컨트롤러 파일이 없다. 전역 임계값은 건드리지 않는다.
 *
 * @param workflowApplicationService 워크플로우 유스케이스 서비스
 * @param workflowCache 워크플로우 인메모리 캐시 (무효화 용도)
 */
@Suppress("TooManyFunctions")
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowController(
    private val workflowApplicationService: WorkflowApplicationService,
    private val workflowCommandService: WorkflowCommandService,
    private val workflowCache: WorkflowCache,
    private val permissionResolver: WorkflowDefinitionPermissionResolver,
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
    fun getWorkflow(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<WorkflowDto>> {
        log.debug("WorkflowController.getWorkflow key={}", key)
        val workflow = workflowApplicationService.getWorkflow(key)
        return ResponseEntity.ok(DataResponse(data = workflow.toDto()))
    }

    /**
     * 워크플로우 전환 계획을 계산한다.
     *
     * 트랜잭션 경계는 [WorkflowApplicationService.planTransition] 이 담당한다.
     *
     * ★ 경로가 `/{key}/transitions` 에서 내려왔다 (spec FR-WF-05 결정 D-1). 그 자리는 이제
     * **전환 정의 컬렉션**([createTransition])이 쓴다 — 같은 method+path 를 두 번 매핑하면 Spring 이
     * 기동에 실패한다. `plan` 은 자원이 아니라 계산이므로 하위 동사 경로가 REST 의미상으로도 맞다.
     *
     * @param key 적용할 워크플로우 키 (경로 변수)
     * @param body 전환 요청 바디
     * @return 200 + 전환 계획 `{ "data": { ... } }`
     */
    @PostMapping("/{key}/transitions/plan")
    fun plan(
        @PathVariable key: String,
        @RequestBody body: TransitionPlanRequestBody,
    ): ResponseEntity<DataResponse<TransitionResponseDto>> {
        log.debug("WorkflowController.plan key={} body={}", key, body)

        val dto =
            TransitionRequestDto(
                toStateKey = body.toStateKey,
                fields = body.fields,
                version = body.version,
            )
        val validation = dto.validate()
        if (validation is Invalid) {
            val messages = validation.errors.joinToString("; ") { it.message }
            throw IllegalArgumentException("전환 요청 검증 실패: $messages")
        }

        val req =
            TransitionRequest(
                workflowKey = key,
                issueKey = body.issueKey,
                fromStateKey = body.fromStateKey,
                toStateKey = body.toStateKey,
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
     * 워크플로우 정의 UPDATE 권한이 없으면 403 Forbidden 을 반환한다.
     *
     * ★ 종전에는 `@PreAuthorize("hasAuthority('WORKFLOW_MANAGE')")` 였다. 그 authority 를 발급하는
     * 경로가 저장소에 없어(정본 코드는 철자가 뒤집힌 `MANAGE_WORKFLOW` · authority 생성처 2곳은
     * 둘 다 `ROLE_` 접두어) **어떤 실제 요청으로도 통과할 수 없었다.**
     * 이 저장소의 권한 검사 관례는 `@PreAuthorize` SpEL 이 아니라 **명시적 resolver 호출**이다
     * (`VersionPermissionResolver` KDoc 이 명문화).
     * 캐시 무효화는 캐시 레이어 직접 호출로 처리한다 (DB 트랜잭션 불필요).
     *
     * @param body 무효화할 워크플로우 키를 담은 바디
     * @return 200 + `{ "data": null }`
     */
    @PostMapping("/cache/invalidate")
    fun invalidateCache(
        @RequestBody body: CacheInvalidateRequest,
    ): ResponseEntity<DataResponse<Nothing?>> {
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(actor.toUuid(), WorkflowDefinitionPermission.UPDATE)
        log.info("WorkflowController.invalidateCache key={}", body.key)
        workflowCache.invalidate(body.key)
        return ResponseEntity.ok(DataResponse(data = null))
    }

    /**
     * 워크플로우를 만든다.
     *
     * 상태 씨앗이 비면 400 이다 — `Workflow.of()` invariant 상 상태 0개 워크플로우는 조회가 불가능하다.
     * 살아 있는 워크플로우가 이미 그 key 를 쓰면 409. 소프트 삭제된 key 는 재사용할 수 있다(V206).
     *
     * @return 201 + `{ "data": { "key": ... } }`
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createWorkflow(
        @RequestBody request: CreateWorkflowRequest,
    ): DataResponse<CreatedWorkflowResponse> {
        val actor = CurrentActor.current()
        log.info("WorkflowController.createWorkflow key={}", request.key)
        workflowCommandService.create(actor.toUuid(), request.toCommand())
        return DataResponse(CreatedWorkflowResponse(request.key))
    }

    /**
     * 이름·설명을 고친다. `key` 는 받지 않는다 — 참조가 문자열이라 바뀌면 조용히 끊긴다.
     *
     * 대상이 없으면 404, 편집이 잠겼으면 409.
     */
    @PutMapping("/{key}")
    fun updateWorkflow(
        @PathVariable key: String,
        @RequestBody request: UpdateWorkflowRequest,
    ): DataResponse<Nothing?> {
        val actor = CurrentActor.current()
        log.info("WorkflowController.updateWorkflow key={}", key)
        workflowCommandService.update(actor.toUuid(), key, request.toCommand())
        return DataResponse(null)
    }

    /**
     * 소프트 삭제한다. 스킴 매핑이 참조 중이면 409.
     *
     * 행을 지우지 않고 `deleted_at` 만 채운다(`DATA.md §1.2`).
     */
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteWorkflow(
        @PathVariable key: String,
    ) {
        val actor = CurrentActor.current()
        log.info("WorkflowController.deleteWorkflow key={}", key)
        workflowCommandService.delete(actor.toUuid(), key)
    }

    /**
     * 워크플로우를 복제한다. 상태 편성과 전환을 함께 복사하고 `origin='CUSTOM'` 으로 만든다.
     *
     * 원본이 없으면 404, 새 key 가 이미 쓰이면 409.
     */
    @PostMapping("/{key}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    fun duplicateWorkflow(
        @PathVariable key: String,
        @RequestBody request: DuplicateWorkflowRequest,
    ): DataResponse<CreatedWorkflowResponse> {
        val actor = CurrentActor.current()
        log.info("WorkflowController.duplicateWorkflow source={} target={}", key, request.key)
        workflowCommandService.duplicate(actor.toUuid(), key, request.key, request.name)
        return DataResponse(CreatedWorkflowResponse(request.key))
    }

    /**
     * 전환 정의를 만든다. 같은 상태쌍에 이름이 다른 전환을 여럿 둘 수 있다 (FR-WF-05 F2).
     *
     * `kind` 를 생략하면 `NORMAL` 이다. `GLOBAL`·`INITIAL` 에 `fromStatusKey` 를 실으면 400,
     * `NORMAL` 인데 없으면 400. 최초 전환이 이미 있는데 또 만들면 409.
     *
     * @return 201 + 만들어진 전환 `{ "data": { "id": ..., "kind": ... } }`
     */
    @PostMapping("/{key}/transitions")
    @ResponseStatus(HttpStatus.CREATED)
    fun createTransition(
        @PathVariable key: String,
        @RequestBody request: TransitionDefinitionRequest,
    ): DataResponse<TransitionResponse> {
        val actor = CurrentActor.current()
        log.info("WorkflowController.createTransition workflow={} kind={}", key, request.kind)
        val created = workflowCommandService.createTransition(actor.toUuid(), key, request.toCommand())
        return DataResponse(created.toTransitionResponse())
    }

    /**
     * 전환 정의를 통째로 갈아 끼운다. 부분 수정이 아니다 — 바디는 생성과 같은 모양이다.
     *
     * 그 워크플로우의 전환이 아니면 404 다 (spec E9) — 남의 전환을 경로만 바꿔 고칠 수 없다.
     */
    @PutMapping("/{key}/transitions/{transitionId}")
    fun updateTransition(
        @PathVariable key: String,
        @PathVariable transitionId: UUID,
        @RequestBody request: TransitionDefinitionRequest,
    ): DataResponse<TransitionResponse> {
        val actor = CurrentActor.current()
        log.info("WorkflowController.updateTransition workflow={} id={}", key, transitionId)
        val updated =
            workflowCommandService.updateTransition(actor.toUuid(), key, transitionId, request.toCommand())
        return DataResponse(updated.toTransitionResponse())
    }

    /**
     * 전환 정의를 지운다. 매달린 validator·post-action 도 함께 사라진다 (spec E6).
     *
     * 최초 전환은 지울 수 없다 — 409 (spec E5).
     */
    @DeleteMapping("/{key}/transitions/{transitionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTransition(
        @PathVariable key: String,
        @PathVariable transitionId: UUID,
    ) {
        val actor = CurrentActor.current()
        log.info("WorkflowController.deleteTransition workflow={} id={}", key, transitionId)
        workflowCommandService.deleteTransition(actor.toUuid(), key, transitionId)
    }
}

/**
 * 전환 계획 요청 바디.
 *
 * @property toStateKey 전환 목표 상태 키
 * @property fields 이슈 커스텀 필드 스냅샷 (기본값 빈 Map)
 * @property version 낙관적 잠금 버전
 * @property issueKey 전환 대상 이슈 키
 * @property fromStateKey 이슈의 현재 상태 키
 * @property actorId 전환을 실행하는 사용자 ID
 * @property actorRoles 실행자의 역할 집합
 */
data class TransitionPlanRequestBody(
    val toStateKey: String,
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

/** 생성·복제 응답. 만들어진 워크플로우의 key 만 준다 — 상세는 GET 으로 받는다. */
data class CreatedWorkflowResponse(val key: String)

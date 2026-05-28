// WorkflowSchemeController — Scheme CRUD 5 endpoint + Mapping CRUD 2 endpoint REST 컨트롤러 (spec §4.1, §4.2)

package com.bts.workflow.scheme.web

import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermission
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermissionResolver
import com.bts.workflow.scheme.port.outbound.WorkflowSchemeScope
import com.bts.workflow.scheme.web.dto.CreateWorkflowSchemeRequest
import com.bts.workflow.scheme.web.dto.MappingRequestDto
import com.bts.workflow.scheme.web.dto.MappingResponse
import com.bts.workflow.scheme.web.dto.UpdateWorkflowSchemeRequest
import com.bts.workflow.scheme.web.dto.WorkflowSchemeResponse
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

/**
 * 워크플로우 스킴 CRUD REST 컨트롤러.
 *
 * spec §4.1 의 5 endpoint 를 담당한다.
 * - POST   /api/v1/workflow-schemes                 — 스킴 생성
 * - GET    /api/v1/workflow-schemes                 — 스킴 목록
 * - GET    /api/v1/workflow-schemes/{schemeKey}     — 스킴 단건 (mappings 동봉)
 * - PUT    /api/v1/workflow-schemes/{schemeKey}     — 스킴 수정 (name/description 만)
 * - DELETE /api/v1/workflow-schemes/{schemeKey}     — 스킴 삭제 (S6/S7 차단)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다 (learning #91).
 * 트랜잭션 개시는 [WorkflowSchemeApplicationService] 가 담당한다.
 *
 * ### 권한 검증
 * 모든 mutating endpoint (create/update/delete) 는 컨트롤러 진입 직후
 * [WorkflowSchemePermissionResolver.requirePermission] 을 호출한다 (Guard 패턴).
 * 인증 연동 전에는 [com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver]
 * stub 이 모든 요청을 허용한다.
 *
 * ### ActorId 임시 처리
 * security context 연동 전까지 고정 UUID (SYSTEM_ACTOR_UUID) 를 사용한다.
 *
 * @param applicationService 스킴 유스케이스 서비스.
 * @param permissionResolver 스킴 권한 평가 outbound port.
 */
@RestController
@RequestMapping("/api/v1/workflow-schemes")
class WorkflowSchemeController(
    private val applicationService: WorkflowSchemeApplicationService,
    private val permissionResolver: WorkflowSchemePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 워크플로우 스킴을 생성한다.
     *
     * @param request 생성 요청 바디 (Jakarta Validation 적용).
     * @return 201 Created + [WorkflowSchemeResponse] body.
     */
    @PostMapping
    fun create(
        @RequestBody request: CreateWorkflowSchemeRequest,
    ): ResponseEntity<DataEnvelope<WorkflowSchemeResponse>> {
        val actor = systemActor()
        permissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
        log.info("WorkflowSchemeController.create key={}", request.key)
        val scheme = applicationService.create(
            actor = actor,
            key = WorkflowSchemeKey(request.key),
            name = request.name,
            description = request.description,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(DataEnvelope(WorkflowSchemeResponse.from(scheme)))
    }

    /**
     * 활성 스킴 전체 목록을 반환한다.
     *
     * @return 200 OK + [WorkflowSchemeResponse] 목록.
     */
    @GetMapping
    fun list(): ResponseEntity<DataEnvelope<List<WorkflowSchemeResponse>>> {
        log.debug("WorkflowSchemeController.list")
        val schemes = applicationService.list().map { WorkflowSchemeResponse.from(it) }
        return ResponseEntity.ok(DataEnvelope(schemes))
    }

    /**
     * 지정된 key 의 스킴을 단건 조회한다.
     *
     * @param schemeKey 조회할 스킴 키 (경로 변수).
     * @return 200 OK + [WorkflowSchemeResponse] body.
     * @throws com.bts.workflow.scheme.exception.WorkflowSchemeNotFoundException 스킴이 없을 때 → 404.
     */
    @GetMapping("/{schemeKey}")
    fun get(
        @PathVariable schemeKey: String,
    ): ResponseEntity<DataEnvelope<WorkflowSchemeResponse>> {
        log.debug("WorkflowSchemeController.get schemeKey={}", schemeKey)
        val scheme = applicationService.find(WorkflowSchemeKey(schemeKey))
        return ResponseEntity.ok(DataEnvelope(WorkflowSchemeResponse.from(scheme)))
    }

    /**
     * 스킴의 name/description 을 수정한다.
     *
     * spec §4.1 PUT — key/is_default 는 변경 불가.
     * 표준 스킴(isDefault=true) 의 잠긴 필드 변경 시 403 SCHEME_STANDARD_FIELD_LOCKED.
     *
     * @param schemeKey 수정할 스킴 키 (경로 변수).
     * @param request 수정 요청 바디.
     * @return 200 OK + 변경된 [WorkflowSchemeResponse].
     */
    @PutMapping("/{schemeKey}")
    fun update(
        @PathVariable schemeKey: String,
        @RequestBody request: UpdateWorkflowSchemeRequest,
    ): ResponseEntity<DataEnvelope<WorkflowSchemeResponse>> {
        val actor = systemActor()
        permissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
        log.info("WorkflowSchemeController.update schemeKey={}", schemeKey)
        val scheme = applicationService.update(
            actor = actor,
            key = WorkflowSchemeKey(schemeKey),
            newName = request.name,
            newDescription = request.description,
            newIsDefault = false,
        )
        return ResponseEntity.ok(DataEnvelope(WorkflowSchemeResponse.from(scheme)))
    }

    /**
     * 스킴을 soft-delete 한다.
     *
     * S6 — 표준 스킴 삭제 시 403 SCHEME_STANDARD_NOT_DELETABLE.
     * S7 — 사용 중인 스킴 삭제 시 409 SCHEME_IN_USE.
     *
     * @param schemeKey 삭제할 스킴 키 (경로 변수).
     */
    @DeleteMapping("/{schemeKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable schemeKey: String,
    ) {
        val actor = systemActor()
        permissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
        log.info("WorkflowSchemeController.delete schemeKey={}", schemeKey)
        applicationService.softDelete(actor, WorkflowSchemeKey(schemeKey))
    }

    /**
     * 스킴에 이슈타입-워크플로우 매핑을 추가한다.
     *
     * spec §4.2 POST /api/v1/workflow-schemes/{schemeKey}/mappings.
     * [MappingRequestDto.issueTypeKey] null = default mapping (issue_type_id IS NULL).
     * G6 Jira align — PUT update endpoint 없음.
     *
     * @param schemeKey 매핑을 추가할 스킴 키 (경로 변수).
     * @param request 매핑 추가 요청 바디.
     * @return 200 OK + 저장된 [MappingResponse].
     */
    @PostMapping("/{schemeKey}/mappings")
    fun addMapping(
        @PathVariable schemeKey: String,
        @RequestBody request: MappingRequestDto,
    ): ResponseEntity<DataEnvelope<MappingResponse>> {
        val actor = systemActor()
        permissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
        log.info(
            "WorkflowSchemeController.addMapping schemeKey={} issueTypeKey={} workflowKey={}",
            schemeKey,
            request.issueTypeKey,
            request.workflowKey,
        )
        val mapping = applicationService.addMappingByKeys(
            actor = actor,
            schemeKey = WorkflowSchemeKey(schemeKey),
            issueTypeKey = request.issueTypeKey,
            workflowKey = request.workflowKey,
        )
        return ResponseEntity.ok(DataEnvelope(MappingResponse.from(mapping)))
    }

    /**
     * 스킴에서 매핑을 삭제한다.
     *
     * spec §4.2 DELETE /api/v1/workflow-schemes/{schemeKey}/mappings/{mappingId}.
     * G8 mapping_id BIGINT REST path 노출.
     * 존재하지 않는 mappingId 에 대해서는 no-op (204 반환).
     *
     * @param schemeKey 매핑이 속한 스킴 키 (경로 변수, 권한 범위 결정용).
     * @param mappingId 삭제할 매핑 PK (경로 변수).
     */
    @DeleteMapping("/{schemeKey}/mappings/{mappingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMapping(
        @PathVariable schemeKey: String,
        @PathVariable mappingId: Long,
    ) {
        val actor = systemActor()
        permissionResolver.requirePermission(actor, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowSchemeScope.Global)
        log.info("WorkflowSchemeController.deleteMapping schemeKey={} mappingId={}", schemeKey, mappingId)
        applicationService.deleteMapping(actor, mappingId)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 인증 연동 전 임시 사용하는 시스템 행위자 [ActorId].
     *
     * security-engineer wave 에서 SecurityContextHolder 의 인증된 UUID 로 교체 예정.
     */
    private fun systemActor(): ActorId = ActorId(SYSTEM_ACTOR_UUID_STRING)

    companion object {
        /** 인증 연동 전 임시 사용하는 시스템 행위자 UUID 문자열. */
        private const val SYSTEM_ACTOR_UUID_STRING = "00000000-0000-0000-0000-000000000001"
    }
}

/**
 * 성공 응답 래퍼.
 *
 * spec §5 응답 포맷 — `{ "data": T }`.
 *
 * @param T 응답 데이터 타입.
 * @property data 응답 페이로드.
 */
data class DataEnvelope<T>(val data: T)

// WorkflowSchemeController — Scheme CRUD 5 endpoint + Mapping CRUD 2 endpoint REST 컨트롤러 (spec §4.1, §4.2)

package com.bts.workflow.scheme.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowScope
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.scheme.application.WorkflowOwnershipScopeResolver
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.scheme.web.dto.CreateWorkflowSchemeRequest
import com.bts.workflow.scheme.web.dto.MappingRequestDto
import com.bts.workflow.scheme.web.dto.MappingResponse
import com.bts.workflow.scheme.web.dto.UpdateWorkflowSchemeRequest
import com.bts.workflow.scheme.web.dto.WorkflowSchemeDetailResponse
import com.bts.workflow.scheme.web.dto.WorkflowSchemeResponse
import com.bts.workflow.web.CurrentActor
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
import org.springframework.web.server.ResponseStatusException

/**
 * 워크플로우 스킴 CRUD + Mapping CRUD REST 컨트롤러.
 *
 * spec §4.1 의 5 endpoint 를 담당한다.
 * - POST   /api/v1/workflow-schemes                                  — 스킴 생성
 * - GET    /api/v1/workflow-schemes                                  — 스킴 목록
 * - GET    /api/v1/workflow-schemes/{schemeKey}                      — 스킴 단건 (mappings 동봉)
 * - PUT    /api/v1/workflow-schemes/{schemeKey}                      — 스킴 수정 (name/description 만)
 * - DELETE /api/v1/workflow-schemes/{schemeKey}                      — 스킴 삭제 (S6/S7 차단)
 *
 * spec §4.2 의 2 endpoint 를 담당한다 (Jira align — PUT update 없음, G6).
 * - POST   /api/v1/workflow-schemes/{schemeKey}/mappings             — 매핑 추가
 * - DELETE /api/v1/workflow-schemes/{schemeKey}/mappings/{mappingId} — 매핑 삭제 (G8 BIGINT path)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다 (learning #91).
 * 트랜잭션 개시는 [WorkflowSchemeApplicationService] 가 담당한다.
 *
 * ### 권한 검증
 * 모든 endpoint (create/update/delete 뿐 아니라 list/get 읽기 포함) 는 컨트롤러 진입 직후
 * [WorkflowSchemePermissionResolver.requirePermission] 을 호출한다 (Guard 패턴).
 * 인증 연동 전에는 [com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver]
 * stub 이 모든 요청을 허용한다.
 *
 * ### 인증 주체 actor 결선
 * 모든 endpoint 는 [CurrentActor.current] 로 Spring Security 인증 주체를
 * [com.bts.workflow.port.outbound.ActorId] 로 변환하여 권한 평가와 유스케이스 호출에 사용한다.
 * 미인증·익명·비-UUID 주체는 [CurrentActor] 가 401 을 던진다.
 *
 * @param applicationService 스킴 유스케이스 서비스.
 * @param permissionResolver 스킴 권한 평가 outbound port.
 * @param scopeResolver 소유 프로젝트 → 권한 스코프 변환의 단일 결정 지점 (FR-WF-08).
 * @param projectLookupPort 프로젝트 키 → UUID 변환. 생성 요청이 지목한 소유를 푼다.
 */
@RestController
@RequestMapping("/api/v1/workflow-schemes")
class WorkflowSchemeController(
    private val applicationService: WorkflowSchemeApplicationService,
    private val permissionResolver: WorkflowSchemePermissionResolver,
    private val scopeResolver: WorkflowOwnershipScopeResolver,
    private val projectLookupPort: ProjectLookupPort,
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
        val actor = CurrentActor.current()
        // 아직 저장되지 않은 스킴이라 소유를 DB 에서 되짚을 수 없다 — 요청이 지목한 프로젝트가 근거다.
        // 프로젝트 실재 확인보다 **먼저** 판정한다. 뒤집으면 없는 프로젝트엔 404, 있는 프로젝트엔
        // 403 이 나가서 권한 없는 사용자가 프로젝트 실재를 알아낸다(존재 probe).
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            scopeResolver.ofProjectKey(request.projectKey),
        )
        val projectId =
            request.projectKey?.let { key ->
                projectLookupPort.findIdByKey(ProjectKey(key))
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $key")
            }
        log.info("WorkflowSchemeController.create key={} projectKey={}", request.key, request.projectKey)
        val scheme =
            applicationService.create(
                actor = actor,
                key = WorkflowSchemeKey(request.key),
                name = request.name,
                description = request.description,
                projectId = projectId,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(DataEnvelope(WorkflowSchemeResponse.from(scheme)))
    }

    /**
     * 활성 스킴 전체 목록을 카운트와 함께 반환한다.
     *
     * usedByProjectsCount + mappingsCount 카운트를 동봉한다. mappings 는 빈 리스트.
     *
     * @return 200 OK + [WorkflowSchemeDetailResponse] 목록.
     * @throws com.bts.shared.permission.WorkflowSchemeAccessDeniedException 권한이 없을 때 → 403.
     */
    @GetMapping
    fun list(): ResponseEntity<DataEnvelope<List<WorkflowSchemeDetailResponse>>> {
        val actor = CurrentActor.current()
        // SCOPE-GLOBAL: 전역 관리자 목록이다. 프로젝트 관리자는 자기 프로젝트 화면이 쓰는
        // ProjectWorkflowSchemeController.listAssignableSchemes 를 타고, 그쪽이 소유로 좁힌다(FR-WF-08).
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            WorkflowScope.Global,
        )
        log.debug("WorkflowSchemeController.list")
        val schemes = applicationService.listWithCounts()
        return ResponseEntity.ok(DataEnvelope(schemes))
    }

    /**
     * 지정된 key 의 스킴을 매핑 + 카운트와 함께 단건 조회한다.
     *
     * mappings 리스트, usedByProjectsCount, mappingsCount 를 동봉한다 (PR #18 잠재 결함 정상화).
     *
     * @param schemeKey 조회할 스킴 키 (경로 변수).
     * @return 200 OK + [WorkflowSchemeDetailResponse] body.
     * @throws com.bts.workflow.scheme.exception.WorkflowSchemeNotFoundException 스킴이 없을 때 → 404.
     * @throws com.bts.shared.permission.WorkflowSchemeAccessDeniedException 권한이 없을 때 → 403.
     */
    @GetMapping("/{schemeKey}")
    fun get(
        @PathVariable schemeKey: String,
    ): ResponseEntity<DataEnvelope<WorkflowSchemeDetailResponse>> {
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            scopeResolver.ofScheme(schemeKey),
        )
        log.debug("WorkflowSchemeController.get schemeKey={}", schemeKey)
        val detail = applicationService.findDetail(WorkflowSchemeKey(schemeKey))
        return ResponseEntity.ok(DataEnvelope(detail))
    }

    /**
     * 스킴의 name/description 을 수정한다.
     *
     * spec §4.1 PUT — key/is_default 는 변경 불가.
     * 표준 스킴(`isStandard=true`, DB 컬럼 `is_default`) 의 잠긴 필드 변경 시 403 SCHEME_STANDARD_FIELD_LOCKED.
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
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            scopeResolver.ofScheme(schemeKey),
        )
        log.info("WorkflowSchemeController.update schemeKey={}", schemeKey)
        val scheme =
            applicationService.update(
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
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            scopeResolver.ofScheme(schemeKey),
        )
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
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            scopeResolver.ofScheme(schemeKey),
        )
        log.info(
            "WorkflowSchemeController.addMapping schemeKey={} issueTypeKey={} workflowKey={}",
            schemeKey,
            request.issueTypeKey,
            request.workflowKey,
        )
        val mapping =
            applicationService.addMappingByKeys(
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
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            scopeResolver.ofScheme(schemeKey),
        )
        log.info("WorkflowSchemeController.deleteMapping schemeKey={} mappingId={}", schemeKey, mappingId)
        applicationService.deleteMapping(actor, WorkflowSchemeKey(schemeKey), mappingId)
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

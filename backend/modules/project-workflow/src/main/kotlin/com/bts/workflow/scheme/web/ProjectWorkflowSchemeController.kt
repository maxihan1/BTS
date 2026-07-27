// ProjectWorkflowSchemeController — Project ↔ WorkflowScheme assignment + 배정 후보 목록 3 endpoint (spec §4.3)

package com.bts.workflow.scheme.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.domain.ProjectWorkflowSchemeAssignment
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.web.CurrentActor
import com.bts.workflow.web.DataResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * 프로젝트 ↔ 워크플로우 스킴 배정 REST API 컨트롤러.
 *
 * spec §4.3 Project assignment 3 endpoint.
 * - PUT  /api/v1/projects/{projectKey}/workflow-scheme — 프로젝트에 스킴 배정 (UPSERT)
 * - GET  /api/v1/projects/{projectKey}/workflow-scheme — 현재 배정된 스킴 조회
 * - GET  /api/v1/projects/{projectKey}/assignable-workflow-schemes — 배정 가능한 스킴 전체 목록 조회
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 트랜잭션 개시는 [WorkflowSchemeApplicationService] 가 담당한다.
 *
 * ### 권한
 * 세 endpoint 모두 [WorkflowSchemePermission.ASSIGN_SCHEME] +
 * [WorkflowSchemeScope.Project] 범위 검증. spec §4.3 주석 참조.
 *
 * ### 인증 주체 actor 결선
 * 세 endpoint 모두 메서드 진입 직후 [CurrentActor.current] 로 Spring Security 인증 주체를
 * [com.bts.workflow.port.outbound.ActorId] 로 변환하여 권한 평가와 유스케이스 호출에 사용한다.
 * 미인증·익명·비-UUID 주체는 [CurrentActor] 가 401 을 던진다.
 *
 * actor 추출은 [ProjectLookupPort.findIdByKey] 호출보다 먼저 수행한다. 인증을 먼저 강제하지 않으면
 * 미인증 요청이 존재하지 않는 프로젝트엔 404, 존재하는 프로젝트엔 401 을 받아 프로젝트 존재 여부를
 * probe 할 수 있다(정보 노출). 따라서 인증을 가장 앞에서 강제한다.
 *
 * @param appService 워크플로우 스킴 application service.
 * @param permissionResolver 스킴 권한 평가 outbound port.
 * @param projectLookupPort projectKey → projects.id(UUID) 변환 outbound port.
 */
@RestController
@RequestMapping("/api/v1/projects")
class ProjectWorkflowSchemeController(
    private val appService: WorkflowSchemeApplicationService,
    private val permissionResolver: WorkflowSchemePermissionResolver,
    private val projectLookupPort: ProjectLookupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트에 워크플로우 스킴을 배정(UPSERT)한다.
     *
     * spec §4.3 + S4 — `project_workflow_scheme_assignments` UPSERT.
     * 이미 배정된 스킴이 있으면 새 스킴으로 교체한다.
     *
     * @param projectKey 스킴을 배정할 프로젝트 키 (예. "ATLAS").
     * @param body 배정할 스킴 키를 담은 요청 바디.
     * @return 200 + `{ "data": { projectId, workflowSchemeId, assignedAt, assignedBy } }`
     * @throws ResponseStatusException(404) [projectKey] 에 해당하는 프로젝트가 없을 때.
     */
    @PutMapping("/{projectKey}/workflow-scheme")
    fun assignScheme(
        @PathVariable projectKey: String,
        @RequestBody body: AssignSchemeRequest,
    ): ResponseEntity<DataResponse<AssignmentResponse>> {
        log.info("assignScheme: projectKey={} schemeKey={}", projectKey, body.schemeKey)
        val actor = CurrentActor.current()
        val key = ProjectKey(projectKey)
        val projectId =
            projectLookupPort.findIdByKey(key)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectKey")
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.ASSIGN_SCHEME,
            WorkflowSchemeScope.Project(projectKey),
        )
        val assignment = appService.assignToProject(actor, projectId, projectKey, WorkflowSchemeKey(body.schemeKey))
        return ResponseEntity.ok(DataResponse(data = assignment.toResponse()))
    }

    /**
     * 프로젝트에 현재 배정된 워크플로우 스킴을 조회한다.
     *
     * spec §4.3 + EC-1 D10 — assignment 없는 신규 프로젝트는 software-scheme 자동 배정 후 반환.
     *
     * @param projectKey 스킴을 조회할 프로젝트 키 (예. "ATLAS").
     * @return 200 + `{ "data": { id, key, name, description, isStandard, ... } }`
     * @throws ResponseStatusException(404) [projectKey] 에 해당하는 프로젝트가 없을 때.
     */
    @GetMapping("/{projectKey}/workflow-scheme")
    fun getAssignedScheme(
        @PathVariable projectKey: String,
    ): ResponseEntity<DataResponse<SchemeResponse>> {
        log.info("getAssignedScheme: projectKey={}", projectKey)
        val actor = CurrentActor.current()
        val key = ProjectKey(projectKey)
        val projectId =
            projectLookupPort.findIdByKey(key)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectKey")
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.ASSIGN_SCHEME,
            WorkflowSchemeScope.Project(projectKey),
        )
        val scheme = appService.findAssignedScheme(projectId, projectKey)
        return ResponseEntity.ok(DataResponse(data = scheme.toResponse()))
    }

    /**
     * 프로젝트에 배정 가능한 워크플로우 스킴 전체 목록을 조회한다.
     *
     * 전역 스킴 목록 창구([com.bts.workflow.scheme.web.WorkflowSchemeController] 의 `list` 는
     * `MANAGE_SCHEME` + `Global` 게이트라 시스템 관리자만 접근 가능하다)가 막혀 있어도, 프로젝트
     * 관리자가 스킴 배정 화면에서 배정 후보를 조회할 수 있도록 `ASSIGN_SCHEME` + `Project(projectKey)`
     * 스코프로 평가하는 프로젝트 스코프 대체 창구다.
     *
     * [getAssignedScheme] 과의 차이. [getAssignedScheme] 은 프로젝트에 "지금 배정된 스킴 하나"를
     * 반환하지만, 이 메서드는 프로젝트 배정 여부와 무관하게 "배정 후보가 될 수 있는 활성 스킴
     * 전체 목록"([WorkflowSchemeApplicationService.list] 이 반환하는, 삭제되지 않은 스킴 전체)을 반환한다.
     *
     * @param projectKey 배정 후보를 조회할 프로젝트 키 (예. "ATLAS"). 이 컨트롤러가 프로젝트 스코프로
     * 권한을 평가하고 존재 여부를 404 로 검증하는 데 쓰인다 — 목록 조회 자체는 프로젝트에 한정되지
     * 않는 전역 활성 스킴 목록이다.
     * @return 200 + `{ "data": [ { id, key, name, description, isStandard }, ... ] }`
     * @throws ResponseStatusException(404) [projectKey] 에 해당하는 프로젝트가 없을 때.
     * @throws com.bts.shared.permission.WorkflowSchemeAccessDeniedException actor 가 [projectKey] 에 대해
     * `ASSIGN_SCHEME` 권한이 없을 때. [WorkflowSchemeExceptionHandler] 가 403 으로 변환한다.
     */
    @GetMapping("/{projectKey}/assignable-workflow-schemes")
    fun listAssignableSchemes(
        @PathVariable projectKey: String,
    ): ResponseEntity<DataResponse<List<SchemeResponse>>> {
        log.info("listAssignableSchemes: projectKey={}", projectKey)
        val actor = CurrentActor.current()
        val key = ProjectKey(projectKey)
        projectLookupPort.findIdByKey(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectKey")
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.ASSIGN_SCHEME,
            WorkflowSchemeScope.Project(projectKey),
        )
        return ResponseEntity.ok(DataResponse(data = appService.list().map { it.toResponse() }))
    }
}

// ── Request / Response DTO ────────────────────────────────────────────────────

/**
 * 스킴 배정 요청 바디.
 *
 * @property schemeKey 배정할 워크플로우 스킴 키. 예. "software-scheme".
 */
data class AssignSchemeRequest(val schemeKey: String)

/**
 * 스킴 배정 결과 응답.
 *
 * @property projectId 스킴이 배정된 프로젝트 UUID.
 * @property workflowSchemeId 배정된 스킴 ID.
 * @property assignedAt 배정 시각 (UTC ISO-8601).
 * @property assignedBy 배정 행위자 UUID.
 */
data class AssignmentResponse(
    val projectId: UUID,
    val workflowSchemeId: Long,
    val assignedAt: Instant,
    val assignedBy: UUID,
)

/**
 * 워크플로우 스킴 조회 응답.
 *
 * @property id 스킴 DB ID.
 * @property key 스킴 키.
 * @property name 스킴 이름.
 * @property description 스킴 설명.
 * @property isStandard 시스템 표준 스킴 여부 (DB 컬럼 `is_default`, 도메인 `WorkflowScheme.isDefault`).
 */
data class SchemeResponse(
    val id: Long?,
    val key: String,
    val name: String,
    val description: String?,
    /** 시스템 표준 스킴 여부. 도메인·DB 는 `isDefault`/`is_default` 그대로다(ADR D2 — 뷰 레이어 한정). */
    val isStandard: Boolean,
)

// ── extension mappers ─────────────────────────────────────────────────────────

private fun ProjectWorkflowSchemeAssignment.toResponse() =
    AssignmentResponse(
        projectId = projectId,
        workflowSchemeId = workflowSchemeId.value,
        assignedAt = assignedAt,
        assignedBy = assignedBy,
    )

private fun WorkflowScheme.toResponse() =
    SchemeResponse(
        id = id?.value,
        key = key.value,
        name = name,
        description = description,
        isStandard = isDefault,
    )

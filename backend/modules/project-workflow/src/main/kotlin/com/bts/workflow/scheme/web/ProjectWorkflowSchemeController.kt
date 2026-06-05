// ProjectWorkflowSchemeController — Project ↔ WorkflowScheme assignment 2 endpoint (spec §4.3)

package com.bts.workflow.scheme.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.domain.ProjectWorkflowSchemeAssignment
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
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
 * spec §4.3 Project assignment 2 endpoint.
 * - PUT  /api/v1/projects/{projectKey}/workflow-scheme — 프로젝트에 스킴 배정 (UPSERT)
 * - GET  /api/v1/projects/{projectKey}/workflow-scheme — 현재 배정된 스킴 조회
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 트랜잭션 개시는 [WorkflowSchemeApplicationService] 가 담당한다.
 *
 * ### 권한
 * 두 endpoint 모두 [WorkflowSchemePermission.ASSIGN_SCHEME] +
 * [WorkflowSchemeScope.Project] 범위 검증. spec §4.3 주석 참조.
 *
 * ### actor 임시 처리
 * 인증 시스템(FR-PM-04) 미완성 단계로, 모든 요청에서 SYSTEM_ACTOR(올-제로 UUID)를 사용한다.
 * FR-PM-04 정식 인증 도입 시 JWT/세션에서 ActorId 를 추출하는 방식으로 교체한다.
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
        val key = ProjectKey(projectKey)
        val projectId =
            projectLookupPort.findIdByKey(key)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectKey")
        val actor = ActorId(SYSTEM_ACTOR_UUID)
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
     * @return 200 + `{ "data": { id, key, name, description, isDefault, ... } }`
     * @throws ResponseStatusException(404) [projectKey] 에 해당하는 프로젝트가 없을 때.
     */
    @GetMapping("/{projectKey}/workflow-scheme")
    fun getAssignedScheme(
        @PathVariable projectKey: String,
    ): ResponseEntity<DataResponse<SchemeResponse>> {
        log.info("getAssignedScheme: projectKey={}", projectKey)
        val key = ProjectKey(projectKey)
        val projectId =
            projectLookupPort.findIdByKey(key)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectKey")
        val actor = ActorId(SYSTEM_ACTOR_UUID)
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.ASSIGN_SCHEME,
            WorkflowSchemeScope.Project(projectKey),
        )
        val scheme = appService.findAssignedScheme(projectId, projectKey)
        return ResponseEntity.ok(DataResponse(data = scheme.toResponse()))
    }

    // ── companion ─────────────────────────────────────────────────────────────

    companion object {
        /**
         * 인증 시스템 미완성 단계에서 사용하는 시스템 actor sentinel UUID.
         *
         * FR-PM-04 정식 인증 도입 시 교체 대상.
         */
        const val SYSTEM_ACTOR_UUID = "00000000-0000-0000-0000-000000000000"
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
 * @property isDefault 표준 스킴 여부.
 */
data class SchemeResponse(
    val id: Long?,
    val key: String,
    val name: String,
    val description: String?,
    val isDefault: Boolean,
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
        isDefault = isDefault,
    )

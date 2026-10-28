// 스킴 게시 · 프로젝트 스킴 교체 + 유형별 상태 이관 REST 컨트롤러 (R11 · FR-WF-02 · FR-WF-07)

package com.bts.workflow.scheme.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowScope
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.scheme.application.SchemeStatusMigrationService
import com.bts.workflow.scheme.application.WorkflowOwnershipScopeResolver
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.domain.TypeStatusMapping
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.scheme.web.dto.MappingRequestDto
import com.bts.workflow.web.CurrentActor
import com.bts.workflow.web.DataResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 스킴 변경을 이관과 함께 받는 두 진입점 (R11 A1 · A2).
 *
 * 상태 매핑 없이(또는 모자라게) 보내면 409 `SCHEME_CHANGE_STRANDS_ISSUES` + `strandedByType` 이
 * 돌아온다 — 화면은 그것으로 마법사 1/2 를 그리고 매핑을 실어 다시 보낸다.
 *
 * 가드 순서는 [ProjectWorkflowSchemeController] 와 같다 — 인증 → 권한 → 프로젝트 조회.
 * 권한을 먼저 보지 않으면 응답 코드 차이로 프로젝트 키의 실재를 열거할 수 있다.
 */
@RestController
class SchemeStatusMigrationController(
    private val migrationService: SchemeStatusMigrationService,
    private val schemeService: WorkflowSchemeApplicationService,
    private val permissionResolver: WorkflowSchemePermissionResolver,
    private val scopeResolver: WorkflowOwnershipScopeResolver,
    private val projectLookupPort: ProjectLookupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 스킴 매핑 전체를 교체한다 — 화면 안 초안의 「게시」. */
    @PostMapping("/api/v1/workflow-schemes/{schemeKey}/publish")
    fun publish(
        @PathVariable schemeKey: String,
        @RequestBody body: PublishSchemeRequest,
    ): ResponseEntity<DataResponse<SchemeMigrationResponse>> {
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            scopeResolver.ofScheme(schemeKey),
        )
        log.info("publishScheme: schemeKey={} mappings={} statusMappings={}", schemeKey, body.mappings.size, body.statusMappings.size)
        val ids =
            migrationService.publish(
                actor,
                WorkflowSchemeKey(schemeKey),
                schemeService.resolveMappingKeys(body.mappings.map { it.issueTypeKey to it.workflowKey }),
                body.statusMappings.map { it.toDomain() },
            )
        return ResponseEntity.ok(DataResponse(data = SchemeMigrationResponse(ids)))
    }

    /** 프로젝트 스킴을 교체한다 — 남는 이슈는 유형별 매핑으로 옮긴다. */
    @PostMapping("/api/v1/projects/{projectKey}/workflow-scheme/switch")
    fun switchScheme(
        @PathVariable projectKey: String,
        @RequestBody body: SwitchSchemeRequest,
    ): ResponseEntity<DataResponse<SchemeMigrationResponse>> {
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.ASSIGN_SCHEME,
            WorkflowScope.Project(projectKey),
        )
        val projectId =
            projectLookupPort.findIdByKey(ProjectKey(projectKey))
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectKey")
        log.info("switchScheme: projectKey={} schemeKey={} statusMappings={}", projectKey, body.schemeKey, body.statusMappings.size)
        val ids =
            migrationService.switchScheme(
                actor,
                projectId,
                projectKey,
                WorkflowSchemeKey(body.schemeKey),
                body.statusMappings.map { it.toDomain() },
            )
        return ResponseEntity.ok(DataResponse(data = SchemeMigrationResponse(ids)))
    }
}

/** 게시 요청 — 매핑 전체(기본 매핑은 `issueTypeKey` null) + 마법사 1/2 의 상태 매핑. */
data class PublishSchemeRequest(
    val mappings: List<MappingRequestDto>,
    val statusMappings: List<StatusMappingDto> = emptyList(),
)

/** 프로젝트 스킴 교체 요청. */
data class SwitchSchemeRequest(
    val schemeKey: String,
    val statusMappings: List<StatusMappingDto> = emptyList(),
)

/** 이슈 타입 하나의 「옛 상태 → 새 상태」. */
data class StatusMappingDto(
    val issueTypeId: Long,
    val fromStatusKey: String,
    val toStatusKey: String,
) {
    fun toDomain() = TypeStatusMapping(issueTypeId, fromStatusKey, toStatusKey)
}

/** 큐잉한 이관 작업 id — 마법사 2/2 가 `GET /api/v1/bulk-operations/{id}` 로 진행률을 본다. 비면 옮길 이슈가 없었다. */
data class SchemeMigrationResponse(
    val bulkOperationIds: List<UUID>,
)

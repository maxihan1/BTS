// 프로젝트 스코프 워크플로우 목록 REST 컨트롤러 — 전역 템플릿 + 그 프로젝트 전용만 준다 (FR-WF-08)

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.shared.permission.WorkflowScope
import com.bts.workflow.application.WorkflowApplicationService
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.web.dto.WorkflowDto
import com.bts.workflow.web.dto.toDto
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 프로젝트 설정 화면이 쓰는 워크플로우 목록 창구.
 *
 * - GET /api/v1/projects/{projectKey}/workflows — 전역 공유 + 그 프로젝트 전용 워크플로우
 *
 * ## 왜 `GET /api/v1/workflows` 를 그대로 쓰지 않는가
 * 그쪽은 **권한 게이트가 없다.** 이슈 화면이 상태 목록을 그리는 데 쓰이므로 로그인 사용자면
 * 읽을 수 있어야 한다는 판단이고([WorkflowDefinitionPermission] KDoc), 워크플로우가 전부
 * 전역이던 시절엔 새는 것이 없었다.
 *
 * FR-WF-08 이 프로젝트 전용 워크플로우를 만들면서 그 전제가 바뀌었다. 프로젝트 설정 화면이
 * 전량 목록을 그대로 그리면 **남의 프로젝트 전용 워크플로우가 이름째 보인다** — 이름만으로도
 * 그 팀이 무슨 흐름을 쓰는지 새는 셈이다. PR ② 가 스킴 목록에 같은 필터를 넣었으므로
 * (`listAssignableSchemes`), 워크플로우 쪽만 전량으로 남기면 두 목록이 서로 다른 규칙을 갖는다.
 *
 * ⇒ 프로젝트 스코프 전용 창구를 따로 둔다. `GET /api/v1/workflows` 는 이슈 화면의 것으로 남는다.
 *
 * ## 가드 순서 — 인증 → 권한 → 프로젝트 조회
 * [WorkflowDefinitionPermissionResolver.requirePermission](403) 을
 * [ProjectLookupPort.findIdByKey](404) **보다 먼저** 부른다. 순서가 반대면 인증은 됐지만 권한이
 * 없는 사용자가 응답 코드 차이(있는 키 403 / 없는 키 404)로 프로젝트 키의 실재를 열거할 수 있다.
 * 권한 스코프 [WorkflowScope.Project] 는 projectId 가 아니라 **키 기반**이라 조회 이전에도
 * 평가할 수 있다 — [com.bts.workflow.scheme.web.ProjectWorkflowSchemeController] 와 같은 관례다.
 *
 * ## 왜 [WorkflowDefinitionPermission.UPDATE] 인가
 * 이 목록은 **워크플로우 정의**를 다루므로 정의 판정기를 쓴다. 스킴 판정기를 쓰면 이름이
 * 스킴을 뜻하는데 정의 목록을 게이트하게 되어, 저장소가 enum 을 둘로 가른 이유를 되돌린다.
 * 프로젝트 스코프에서 두 판정기는 같은 `MANAGE_WORKFLOW` 매트릭스 코드로 수렴하므로 통과 집합은
 * 형제 엔드포인트(`listAssignableSchemes`, `ASSIGN_SCHEME`)와 **같다.**
 *
 * 읽기에 UPDATE 를 요구하는 것은 「이 프로젝트의 워크플로우를 관리할 수 있는 사람에게 관리 목록을
 * 준다」는 뜻이다 — 배정 후보 목록이 `ASSIGN_SCHEME` 을 요구하는 것과 같은 모양이다.
 *
 * @param workflowApplicationService 워크플로우 조회 유스케이스.
 * @param permissionResolver 워크플로우 정의 권한 평가 outbound port.
 * @param projectLookupPort projectKey → `projects.id` 변환 outbound port.
 */
@RestController
@RequestMapping("/api/v1/projects")
class ProjectWorkflowController(
    private val workflowApplicationService: WorkflowApplicationService,
    private val permissionResolver: WorkflowDefinitionPermissionResolver,
    private val projectLookupPort: ProjectLookupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트가 쓸 수 있는 워크플로우 목록을 조회한다.
     *
     * @param projectKey 대상 프로젝트 키. 예. "ATLAS".
     * @return 200 + `{ "data": [ { key, name, description, states, transitions }, ... ] }`
     * @throws ResponseStatusException(404) [projectKey] 에 해당하는 프로젝트가 없을 때.
     * @throws com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
     *   actor 가 그 프로젝트의 워크플로우를 관리할 권한이 없을 때 (403).
     */
    @GetMapping("/{projectKey}/workflows")
    fun listForProject(
        @PathVariable projectKey: String,
    ): ResponseEntity<DataResponse<List<WorkflowDto>>> {
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowDefinitionPermission.UPDATE,
            WorkflowScope.Project(projectKey),
        )
        val projectId =
            projectLookupPort.findIdByKey(ProjectKey(projectKey))
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: $projectKey")
        log.debug("ProjectWorkflowController.listForProject projectKey={}", projectKey)
        val workflows = workflowApplicationService.listWorkflowsForProject(projectId).map { it.toDto() }
        return ResponseEntity.ok(DataResponse(data = workflows))
    }
}

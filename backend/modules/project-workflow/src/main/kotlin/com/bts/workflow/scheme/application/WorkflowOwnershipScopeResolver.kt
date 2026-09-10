// 워크플로우·스킴의 소유 프로젝트를 권한 스코프로 옮기는 단일 결정 지점 (FR-WF-08)

package com.bts.workflow.scheme.application

import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 소유 프로젝트(`project_id`) → 권한 스코프([WorkflowSchemeScope]) 변환의 **단일 결정 지점**.
 *
 * ★ 이 변환이 호출부마다 흩어지면 한 곳만 고쳐지고 나머지는 조용히 옛 스코프로 남는다.
 * 각 컨트롤러 테스트가 자기 사본만 지키므로 red 도 나지 않는다 — [ManageSchemeGuard] 가 이미
 * 같은 이유로 단일 구현이다. 결정은 여기 한 곳에만 둔다.
 *
 * ## fail-closed
 *
 * 대상이 없거나(삭제된 스킴 · 없는 키) 소유 프로젝트 행을 되짚지 못하면 [WorkflowSchemeScope.Global]
 * 을 준다. 전역 스코프는 SYSTEM_ADMIN 만 통과하므로, 알 수 없는 소유는 **가장 좁은 통과**로 떨어진다.
 * 반대로 하면(프로젝트 스코프로 떨어뜨리면) 소유가 불명확한 대상을 아무 프로젝트 관리자나 만지게 된다.
 *
 * @param schemeRepo 스킴 소유 조회.
 * @param workflowRepo 워크플로우 소유 조회.
 * @param projectLookupPort `projects.id` → 프로젝트 키 역방향 조회.
 */
@Component
class WorkflowOwnershipScopeResolver(
    private val schemeRepo: WorkflowSchemeRepository,
    private val workflowRepo: WorkflowRepository,
    private val projectLookupPort: ProjectLookupPort,
) {
    /**
     * 스킴 키가 가리키는 스킴의 소유 스코프를 준다.
     *
     * 원시 문자열을 받는다 — 호출부(컨트롤러)가 쥔 것이 경로 변수 문자열이고, 형식이 어긋난 키는
     * 예외가 아니라 [WorkflowSchemeScope.Global] 로 떨어뜨려야 판정 순서가 흐트러지지 않는다.
     * 여기서 던지면 권한 판정 **전에** 400 이 나가 순서 계약이 깨진다.
     *
     * @param schemeKey 대상 스킴 키 원문.
     * @return 전역 스킴이거나 스킴이 없거나 키 형식이 어긋나면 [WorkflowSchemeScope.Global],
     * 아니면 소유 프로젝트 스코프.
     */
    fun ofScheme(schemeKey: String): WorkflowSchemeScope {
        val parsed = runCatching { WorkflowSchemeKey(schemeKey) }.getOrNull() ?: return WorkflowSchemeScope.Global
        return ofProjectId(schemeRepo.findByKey(parsed)?.projectId)
    }

    /**
     * 워크플로우 키가 가리키는 워크플로우의 소유 스코프를 준다.
     *
     * @param workflowKey 대상 워크플로우 키.
     * @return 전역 워크플로우이거나 워크플로우가 없으면 [WorkflowSchemeScope.Global], 아니면 소유 프로젝트 스코프.
     */
    fun ofWorkflow(workflowKey: String): WorkflowSchemeScope = ofProjectId(workflowRepo.findProjectIdByKey(workflowKey))

    /**
     * 요청이 지목한 프로젝트 키를 그대로 스코프로 옮긴다. 생성 경로가 쓴다.
     *
     * 아직 저장되지 않은 대상은 소유를 DB 에서 되짚을 수 없으므로 요청 바디가 유일한 근거다.
     *
     * @param projectKey 요청이 지목한 프로젝트 키. null 이면 전역 생성 요청이다.
     * @return [projectKey] 가 있으면 그 프로젝트 스코프, 없으면 [WorkflowSchemeScope.Global].
     */
    fun ofProjectKey(projectKey: String?): WorkflowSchemeScope =
        projectKey?.let { WorkflowSchemeScope.Project(it) } ?: WorkflowSchemeScope.Global

    /**
     * 소유 프로젝트 UUID 를 스코프로 옮긴다.
     *
     * @param projectId 소유 프로젝트 `projects.id`. null = 전역.
     * @return 되짚기에 성공하면 프로젝트 스코프, 아니면 [WorkflowSchemeScope.Global] (fail-closed).
     */
    fun ofProjectId(projectId: UUID?): WorkflowSchemeScope =
        projectId
            ?.let { projectLookupPort.findKeyById(it) }
            ?.let { WorkflowSchemeScope.Project(it.value) }
            ?: WorkflowSchemeScope.Global
}

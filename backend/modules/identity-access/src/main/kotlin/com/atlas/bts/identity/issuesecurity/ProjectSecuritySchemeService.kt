// 프로젝트에 이슈 보안 스킴을 적용/해제/조회하는 애플리케이션 서비스 + PROJECT_ADMIN 가드 (FR-PM-06 PR-A Task 6)

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.atlas.bts.identity.project.ProjectRole
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 대상 프로젝트가 존재하지 않음 (→ 404).
 *
 * [IssueSecurityException] 계층과 별도로 본 파일에 선언한다 — 스킴 관리(Task 5/7) BC 식별자가 아닌
 * cross-BC 프로젝트 식별자에 대한 부재이며, 동시 진행 Task 7 이 건드리는 `IssueSecurityExceptions.kt` 와의
 * 파일 충돌을 피하기 위해 본 서비스 파일에 둔다.
 */
class ProjectNotFoundException(projectKey: String) :
    RuntimeException("project not found: $projectKey")

/**
 * actor 가 프로젝트의 PROJECT_ADMIN 이 아니어서 스킴 적용/해제/조회가 거부됨 (→ 403).
 *
 * 거부 메시지에 actor/projectId 등 내부 식별자를 싣지 않는다(403 응답 body 누출 방지,
 * fr-pm-04-guard-exception-message-http-leak 회귀 방지). 컨트롤러(Task 8)는 일반 메시지로 매핑한다.
 */
class ProjectSchemeAccessDeniedException :
    RuntimeException("project security scheme administration is restricted to project admins")

/**
 * 프로젝트 ↔ 이슈 보안 스킴 적용 애플리케이션 서비스 (FR-PM-06 PR-A Task 6).
 *
 * ## 책임
 * - 프로젝트에 보안 스킴을 적용([assign])/해제([unassign])/조회([findByProject]).
 * - 각 작업 전 **PROJECT_ADMIN 가드** 를 수행한다.
 *
 * ## PROJECT_ADMIN 판정 = 역할 직접 확인 (plan Task 6 C2 결정)
 * FR-PM-04 는 MANAGE_WORKFLOW 권한 매트릭스를 쓰지만, 프로젝트 행정 권한코드(ADMIN_PROJECT)가
 * role_permissions 에 시드되어 있지 않다(V008~V014 확인). 따라서 매트릭스 대신
 * [ProjectMembershipRepository.findByProjectAndUser] 로 멤버십을 조회해 `role == PROJECT_ADMIN` 을
 * 직접 확인한다(ground-truth 정합). 멤버십이 없으면 거부한다.
 *
 * ## 검증 순서 (모든 작업 공통)
 * 1. [ProjectDirectory.resolveKeyToId] — 키 → id, 없으면 [ProjectNotFoundException] (404).
 * 2. [requireProjectAdmin] — PROJECT_ADMIN 아니면 [ProjectSchemeAccessDeniedException] (403).
 * 3. [assign] 만: [IssueSecuritySchemeRepository.findById] 로 스킴 실재 확인, 없으면 [SchemeNotFoundException] (404).
 *
 * ## 트랜잭션 경계 (DATA.md §6 / DEVELOPMENT.md §1.2)
 * 가드(조회) + 위임(쓰기)을 한 단위로 묶으므로 클래스 레벨 @Transactional(REQUIRED) 을 소유한다.
 * 읽기 전용 [findByProject] 는 readOnly 로 오버라이드한다.
 *
 * @see docs/decisions/2026-06-06-issue-security-level-scheme-model.md 설계 결정 ADR
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class ProjectSecuritySchemeService(
    private val projectSchemeRepository: ProjectSecuritySchemeRepository,
    private val projectDirectory: ProjectDirectory,
    private val membershipRepository: ProjectMembershipRepository,
    private val schemeRepository: IssueSecuritySchemeRepository,
) {
    /**
     * 프로젝트에 보안 스킴을 적용한다(이미 적용된 스킴이 있으면 교체).
     *
     * @param projectKey 대상 프로젝트 키 (예: "ATLAS").
     * @param schemeId 적용할 보안 스킴 식별자.
     * @param actorId 요청 사용자 식별자 (컨트롤러가 인증 주체에서 추출).
     * @throws ProjectNotFoundException 프로젝트가 없는 경우.
     * @throws ProjectSchemeAccessDeniedException actor 가 PROJECT_ADMIN 이 아닌 경우.
     * @throws SchemeNotFoundException 적용할 스킴이 없는 경우.
     */
    fun assign(
        projectKey: String,
        schemeId: UUID,
        actorId: UUID,
    ) {
        val projectId = resolveAndAuthorize(projectKey, actorId)
        schemeRepository.findById(schemeId) ?: throw SchemeNotFoundException(schemeId)
        projectSchemeRepository.assign(projectId, schemeId)
    }

    /**
     * 프로젝트의 보안 스킴 적용을 해제한다(미적용이어도 멱등).
     *
     * @param projectKey 대상 프로젝트 키.
     * @param actorId 요청 사용자 식별자.
     * @throws ProjectNotFoundException 프로젝트가 없는 경우.
     * @throws ProjectSchemeAccessDeniedException actor 가 PROJECT_ADMIN 이 아닌 경우.
     */
    fun unassign(
        projectKey: String,
        actorId: UUID,
    ) {
        val projectId = resolveAndAuthorize(projectKey, actorId)
        projectSchemeRepository.unassign(projectId)
    }

    /**
     * 프로젝트에 적용된 보안 스킴 식별자를 조회한다.
     *
     * @param projectKey 대상 프로젝트 키.
     * @param actorId 요청 사용자 식별자.
     * @return 적용된 scheme_id, 미적용이면 null.
     * @throws ProjectNotFoundException 프로젝트가 없는 경우.
     * @throws ProjectSchemeAccessDeniedException actor 가 PROJECT_ADMIN 이 아닌 경우.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    fun findByProject(
        projectKey: String,
        actorId: UUID,
    ): UUID? {
        val projectId = resolveAndAuthorize(projectKey, actorId)
        return projectSchemeRepository.findByProject(projectId)
    }

    /**
     * 프로젝트 키를 id 로 해석하고 actor 의 PROJECT_ADMIN 권한을 확인한다.
     *
     * @return 검증을 통과한 프로젝트 id.
     * @throws ProjectNotFoundException 프로젝트가 없는 경우.
     * @throws ProjectSchemeAccessDeniedException actor 가 PROJECT_ADMIN 이 아닌 경우.
     */
    private fun resolveAndAuthorize(
        projectKey: String,
        actorId: UUID,
    ): UUID {
        val projectId = projectDirectory.resolveKeyToId(projectKey) ?: throw ProjectNotFoundException(projectKey)
        requireProjectAdmin(projectId, actorId)
        return projectId
    }

    /**
     * actor 가 프로젝트의 PROJECT_ADMIN 역할 보유자인지 확인한다.
     *
     * 멤버십이 없거나 역할이 PROJECT_ADMIN 이 아니면 거부한다.
     *
     * @throws ProjectSchemeAccessDeniedException 권한이 없는 경우.
     */
    private fun requireProjectAdmin(
        projectId: UUID,
        actorId: UUID,
    ) {
        val membership = membershipRepository.findByProjectAndUser(projectId, actorId)
        if (membership?.role != ProjectRole.PROJECT_ADMIN) throw ProjectSchemeAccessDeniedException()
    }
}

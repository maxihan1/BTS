// 로그인 사용자가 접근 가능한 프로젝트 목록/단건을 조회하는 읽기 전용 Application Service (FR-PJ PR-3 Task 3/4)

package com.bts.issue.project.query

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.domain.Project
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.issue.project.repository.ProjectQueryRepository
import com.bts.shared.membership.ProjectMembershipPort
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 지정한 projectIdOrKey 에 해당하는 활성 프로젝트가 존재하지 않을 때(Task 4).
 *
 * 두 경로에서 던진다.
 * 1. [ProjectLookup.resolve] 또는 [ProjectLookupRepository.findProjectKeyById] 가 null 을 반환한
 *    경우(사전 미존재 또는 resolve~key조회 사이 경합으로 소프트삭제).
 * 2. [ProjectQueryRepository.findByIdOrKey] 가 null 을 반환한 경우(권한 판정 이후 경합으로
 *    소프트삭제 — TOCTOU 대비 후행 재조회, [ProjectQueryService.getOne] 흐름 참조).
 *
 * HTTP 404 매핑은 [com.bts.issue.project.web.ProjectQueryExceptionHandler] 가 담당한다.
 *
 * 동일 모듈에 이미 존재하는 [com.bts.issue.project.settings.ProjectNotFoundException] 과
 * 이름이 충돌하지 않도록 `Query` 를 접두한다(메모리: duplicate-exception-name-cross-package-status).
 *
 * @param projectIdOrKey 조회 대상 프로젝트 식별자 (UUID 또는 projectKey).
 */
class ProjectQueryNotFoundException(projectIdOrKey: String) :
    RuntimeException("Project not found: $projectIdOrKey")

/**
 * 행위자가 프로젝트 단건 조회에 필요한 [IssuePermission.BROWSE] 권한을 보유하지 않을 때(Task 4).
 *
 * HTTP 403 매핑은 [com.bts.issue.project.web.ProjectQueryExceptionHandler] 가 담당한다.
 *
 * **메시지는 내부 식별자(actor/projectKey)를 포함**하므로 로그에만 사용하고, HTTP 응답 detail 에는
 * 일반 메시지를 쓴다 (메모리: guard-exception-message-http-leak).
 *
 * @param actorId 권한 검사 대상 행위자 UUID.
 * @param projectKey BROWSE 판정에 사용한 프로젝트 key.
 */
class ProjectBrowseForbiddenException(
    actorId: UUID,
    projectKey: String,
) : RuntimeException(
        "Access denied: actor=$actorId, permission=${IssuePermission.BROWSE.name}, projectKey=$projectKey",
    )

/**
 * 로그인 사용자가 접근 가능한 프로젝트 목록/단건을 조회하는 읽기 전용 Application Service.
 *
 * 접근 가능 여부는 [ProjectMembershipPort] (cross-BC 창구)가 반환한 프로젝트 key 집합으로
 * 결정한다. 이 포트 외부에서 별도의 멤버십 조회 경로를 만들지 않는다(포트 KDoc 계약).
 *
 * ### fail-closed (PJ2-4)
 * [ProjectMembershipPort.projectKeysOf] 가 빈 Set 을 반환하면(멤버십 0개) 조회 자체를 생략하지
 * 않고 그대로 [ProjectQueryRepository.findAccessibleByKeys] 에 전달한다. 그 메서드는 빈 Set 이면
 * 즉시 빈 목록을 반환하므로 결과적으로 존재 누설이 0이다. nullable 술어로 조기 return 하지 않는다.
 *
 * @param projectQueryRepository 프로젝트 목록/설정 조회 전담 읽기 전용 Repository.
 * @param projectMembershipPort 사용자의 프로젝트 멤버십 key 집합을 조회하는 cross-BC 포트.
 * @param projectLookup projectIdOrKey → 활성 프로젝트 UUID 해석 (Task 4).
 * @param projectLookupRepository projectId → 정규 projectKey 역방향 조회 (Task 4, BROWSE scope 용).
 * @param permissionResolver 이슈 권한 판정 cross-BC 포트 (Task 4, BROWSE 게이트).
 */
@Service
@Transactional(readOnly = true)
class ProjectQueryService(
    private val projectQueryRepository: ProjectQueryRepository,
    private val projectMembershipPort: ProjectMembershipPort,
    private val projectLookup: ProjectLookup,
    private val projectLookupRepository: ProjectLookupRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [actorId] 가 접근 가능한 활성 프로젝트 목록을 name 오름차순으로 반환한다.
     *
     * @param actorId 조회 주체 사용자 UUID.
     * @return 접근 가능한 프로젝트 목록. 멤버십이 없으면 빈 목록(fail-closed).
     */
    fun listAccessible(actorId: UUID): List<Project> {
        val keys = projectMembershipPort.projectKeysOf(actorId)
        log.debug("ProjectQueryService.listAccessible actorId={} keyCount={}", actorId, keys.size)
        return projectQueryRepository.findAccessibleByKeys(keys)
    }

    /**
     * projectIdOrKey 로 프로젝트 단건을 조회한다. 순서 — **존재 → 권한 → 조회**(PJ2-3).
     *
     * 1. [ProjectLookup.resolve] 로 활성 프로젝트 UUID 해석. 미존재 시 [ProjectQueryNotFoundException](404).
     * 2. [ProjectLookupRepository.findProjectKeyById] 로 BROWSE 판정용 정규 projectKey 조회
     *    (projectIdOrKey 가 UUID 문자열일 수 있어 [IssueScope.Project] 에 그대로 쓸 수 없다). null 이면
     *    1~2 사이 경합으로 소프트삭제된 것으로 보고 [ProjectQueryNotFoundException](404).
     * 3. [IssuePermissionResolver.hasPermission] 로 [IssuePermission.BROWSE] 판정. 미충족 시
     *    [ProjectBrowseForbiddenException](403).
     * 4. [ProjectQueryRepository.findByIdOrKey] 로 재조회 — lock 밖에서 미리 읽은 결과를 그대로
     *    신뢰하지 않고, 권한 판정 이후 경합(TOCTOU)을 이 후행 조회 결과로 재확인한다. 미존재 시
     *    [ProjectQueryNotFoundException](404).
     *
     * 아카이브된 프로젝트도 단건 조회 대상이다(PJ2-3) — archived 개념은 이 PR 범위 밖이라
     * [ProjectQueryRepository.findByIdOrKey] 는 `deleted_at IS NULL` 인 활성 전체를 그대로 반환한다.
     *
     * @param actorId 조회 주체 사용자 UUID — BROWSE 권한 판정 대상.
     * @param projectIdOrKey 조회 대상 프로젝트 식별자(UUID 또는 projectKey).
     * @return 조회된 프로젝트.
     * @throws ProjectQueryNotFoundException 프로젝트가 미존재하거나 소프트삭제된 경우(404 의도).
     * @throws ProjectBrowseForbiddenException 행위자에게 BROWSE 권한이 없는 경우(403 의도).
     */
    fun getOne(
        actorId: UUID,
        projectIdOrKey: String,
    ): Project {
        val projectId = resolveProjectId(projectIdOrKey)
        val projectKey = resolveProjectKey(projectId, projectIdOrKey)
        requireBrowsePermission(actorId, projectKey)

        log.debug("ProjectQueryService.getOne actorId={} projectId={}", actorId, projectId)

        return projectQueryRepository.findByIdOrKey(projectId)
            ?: throw ProjectQueryNotFoundException(projectIdOrKey)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * projectIdOrKey 를 활성 프로젝트 UUID 로 해석한다. 미존재 시 [ProjectQueryNotFoundException].
     *
     * [getOne] 의 ThrowsCount 를 낮추기 위해 단일 throw 지점으로 분리한다
     * ([com.bts.agileplanning.application.SprintBurndownService] 동형 선례).
     */
    private fun resolveProjectId(projectIdOrKey: String): UUID =
        projectLookup.resolve(projectIdOrKey)
            ?: throw ProjectQueryNotFoundException(projectIdOrKey)

    /**
     * 프로젝트 UUID 로 BROWSE 판정용 정규 projectKey 를 조회한다.
     *
     * null 이면 [resolveProjectId] 성공 이후 경합으로 소프트삭제된 것으로 보고
     * [ProjectQueryNotFoundException] 을 던진다.
     */
    private fun resolveProjectKey(
        projectId: UUID,
        projectIdOrKey: String,
    ): String =
        projectLookupRepository.findProjectKeyById(projectId)
            ?: throw ProjectQueryNotFoundException(projectIdOrKey)

    /**
     * [actorId] 의 [projectKey] 에 대한 [IssuePermission.BROWSE] 권한을 판정하고 미충족 시
     * [ProjectBrowseForbiddenException] 을 던진다.
     */
    private fun requireBrowsePermission(
        actorId: UUID,
        projectKey: String,
    ) {
        if (!permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))) {
            throw ProjectBrowseForbiddenException(actorId, projectKey)
        }
    }
}

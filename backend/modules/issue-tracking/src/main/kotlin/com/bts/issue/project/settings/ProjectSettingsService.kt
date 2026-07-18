// 프로젝트 설정(name) 변경 ApplicationService — 컴포넌트 UPDATE 권한 가드 재사용 (FR-PJ PR-3 Task 5)

package com.bts.issue.project.settings

import com.bts.issue.project.ProjectLookup
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 지정한 projectIdOrKey 에 해당하는 활성 프로젝트가 존재하지 않을 때.
 *
 * 두 경로에서 던진다.
 * 1. [ProjectLookup.resolve] 가 null 을 반환한 경우(사전 미존재).
 * 2. [ProjectSettingsRepository.updateName] 이 0 행을 갱신한 경우(조회~갱신 사이 경합으로
 *    소프트삭제된 경우 — TOCTOU 대비 후행 확인).
 *
 * HTTP 404 매핑은 [com.bts.issue.project.web.ProjectSettingsExceptionHandler] 가 담당한다.
 *
 * @param projectIdOrKey 조회 대상 프로젝트 식별자(UUID 또는 projectKey).
 */
class ProjectNotFoundException(projectIdOrKey: String) :
    RuntimeException("Project not found: $projectIdOrKey")

/**
 * 행위자가 프로젝트 설정 변경에 필요한 컴포넌트 UPDATE(MANAGE_COMPONENTS, PROJECT_ADMIN 전용) 권한을
 * 보유하지 않을 때.
 *
 * HTTP 403 매핑은 [com.bts.issue.project.web.ProjectSettingsExceptionHandler] 가 담당한다.
 *
 * **메시지는 내부 식별자(actor/projectId)를 포함**하므로 로그에만 사용하고, HTTP 응답 detail 에는
 * 일반 메시지를 쓴다 (메모리: guard-exception-message-http-leak).
 *
 * @param actorId 권한 검사 대상 행위자 UUID.
 * @param projectId 설정을 변경하려는 프로젝트 UUID.
 */
class ProjectSettingsForbiddenException(
    actorId: UUID,
    projectId: UUID,
) : RuntimeException(
        "Access denied: actor=$actorId, permission=${ComponentPermission.UPDATE.name}, projectId=$projectId",
    )

/**
 * 프로젝트 설정(name) 변경 ApplicationService.
 *
 * [com.bts.issue.project.application.ProjectLeadApplicationService] 와 동형 패턴 — 존재 → 권한 → 영속 순.
 *
 * **흐름 — changeName.**
 * 1. [ProjectLookup.resolve] 로 projectIdOrKey → 활성 프로젝트 UUID 해석.
 *    null 이면 [ProjectNotFoundException] (404 의도).
 * 2. [ComponentPermissionResolver] 로 컴포넌트 UPDATE 권한 검증(게이트1 D2=(i) — MANAGE_COMPONENTS,
 *    role_permissions 시드상 PROJECT_ADMIN 전용, MEMBER 미보유). 거부 시 [ProjectSettingsForbiddenException]
 *    (403 의도).
 * 3. [ProjectSettingsRepository.updateName] 으로 name 갱신. 영향받은 행이 0 이면(경합으로 그 사이
 *    소프트삭제) [ProjectNotFoundException] 을 재던진다(TOCTOU 대비 lock 밖 판단 금지 — 갱신 결과로
 *    재확인).
 *
 * **권한 게이트 — 컴포넌트 UPDATE 재사용.**
 * 별도 권한코드/마이그레이션을 두지 않는다 — [com.bts.issue.project.application.ProjectLeadApplicationService]
 * 와 동일하게 컴포넌트 UPDATE(MANAGE_COMPONENTS) 로 게이트한다. prod 실판정은 identity-access 의
 * `IdentityAccessComponentPermissionResolver`(동일 포트)가, non-prod 는 `AlwaysAllowComponentPermissionResolver`
 * 가 담당한다.
 *
 * **트랜잭션.**
 * 클래스 레벨 @Transactional 이 기본. DEVELOPMENT.md §1 — public service 메서드 전체 @Transactional
 * 명시 원칙에 따라 클래스 레벨로 커버한다.
 */
@Service
@Transactional
class ProjectSettingsService(
    private val projectLookup: ProjectLookup,
    private val componentPermissionResolver: ComponentPermissionResolver,
    private val repository: ProjectSettingsRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 name 을 변경한다.
     *
     * @param actorId 요청 행위자 UUID — 컴포넌트 UPDATE 권한 검증 대상.
     * @param projectIdOrKey 대상 프로젝트 UUID 문자열 또는 projectKey.
     * @param name 설정할 새 이름(Jakarta Validation 은 컨트롤러 DTO 가 1차 방어 — NotBlank/Size).
     * @throws ProjectNotFoundException 프로젝트가 미존재하거나 소프트삭제된 경우(404 의도).
     * @throws ProjectSettingsForbiddenException 행위자에게 컴포넌트 UPDATE 권한이 없는 경우(403 의도).
     */
    fun changeName(
        actorId: UUID,
        projectIdOrKey: String,
        name: String,
    ) {
        log.info(
            "ProjectSettingsService.changeName actor={} projectIdOrKey={}",
            actorId,
            projectIdOrKey,
        )

        val projectId =
            projectLookup.resolve(projectIdOrKey)
                ?: throw ProjectNotFoundException(projectIdOrKey)

        assertProjectAdmin(actorId, projectId)

        val updated = repository.updateName(projectId, name)
        if (updated == 0) {
            // 1단계에서 미리 읽은 존재 여부를 그대로 신뢰하지 않는다 — resolve~update 사이 경합으로
            // 소프트삭제됐을 가능성을 갱신 결과(영향 행 수)로 재확인한다.
            throw ProjectNotFoundException(projectIdOrKey)
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 행위자의 컴포넌트 [ComponentPermission.UPDATE] 권한을 검증한다.
     * 거부 시 [ProjectSettingsForbiddenException] 을 던진다.
     *
     * 게이트1 D2=(i) 결정 반영 지점 — 헬퍼 1점으로 격리.
     */
    private fun assertProjectAdmin(
        actorId: UUID,
        projectId: UUID,
    ) {
        if (!componentPermissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId)) {
            throw ProjectSettingsForbiddenException(actorId, projectId)
        }
    }
}

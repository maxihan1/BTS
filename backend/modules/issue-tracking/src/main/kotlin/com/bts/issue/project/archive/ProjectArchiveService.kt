// 프로젝트 아카이브/아카이브 해제 ApplicationService — 컴포넌트 UPDATE 권한 가드 재사용 + Clock 주입 (FR-PJ-04 PR-4 Task 5)

package com.bts.issue.project.archive

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.repository.ProjectArchiveRepository
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 지정한 projectIdOrKey 에 해당하는 활성 프로젝트가 존재하지 않을 때.
 *
 * 두 경로에서 던진다.
 * 1. [ProjectLookup.resolve] 가 null 을 반환한 경우(사전 미존재).
 * 2. [ProjectArchiveRepository] 의 archive/unarchive 갱신이 0 행을
 *    반환한 경우(조회~갱신 사이 경합으로 소프트삭제된 경우 — TOCTOU 대비 후행 확인,
 *    [com.bts.issue.project.settings.ProjectSettingsService] 동형 패턴).
 *
 * HTTP 404 매핑은 [com.bts.issue.project.web.ProjectArchiveController] 동일 파일의 전용 advice
 * ([com.bts.issue.project.web.ProjectArchiveExceptionHandler])가 담당한다.
 *
 * @param projectIdOrKey 조회 대상 프로젝트 식별자(UUID 또는 projectKey).
 */
class ProjectArchiveNotFoundException(projectIdOrKey: String) :
    RuntimeException("Project not found: $projectIdOrKey")

/**
 * 행위자가 아카이브/아카이브 해제에 필요한 컴포넌트 UPDATE(PROJECT_ADMIN 전용) 권한을 보유하지
 * 않을 때.
 *
 * HTTP 403 매핑은 [com.bts.issue.project.web.ProjectArchiveExceptionHandler] 가 담당한다.
 *
 * **메시지는 내부 식별자(actor/projectId)를 포함**하므로 로그에만 사용하고, HTTP 응답 detail 에는
 * 일반 메시지를 쓴다 (memory: guard-exception-message-http-leak).
 *
 * @param actorId 권한 검사 대상 행위자 UUID.
 * @param projectId 아카이브/아카이브 해제를 시도한 프로젝트 UUID.
 */
class ProjectArchiveForbiddenException(
    actorId: UUID,
    projectId: UUID,
) : RuntimeException(
        "Access denied: actor=$actorId, permission=${ComponentPermission.UPDATE.name}, projectId=$projectId",
    )

/**
 * 아카이브/아카이브 해제 갱신 결과 — projectId + projectKey + archivedAt.
 *
 * 컨트롤러가 응답 DTO 로 변환할 때 이 결과를 사용한다.
 *
 * @property projectId 프로젝트 UUID.
 * @property projectKey 프로젝트 키(DB [ProjectArchiveRepository] 갱신 결과에서 온 값 — projectIdOrKey
 *   가 UUID 로 전달된 경우에도 정확하다, RETURNING 절 기반).
 * @property archivedAt 갱신 후 archived_at 값. archive 는 non-null, unarchive 는 null.
 */
data class ProjectArchiveResult(
    val projectId: UUID,
    val projectKey: String,
    val archivedAt: Instant?,
)

/**
 * 프로젝트 아카이브(archive)/아카이브 해제(unarchive) ApplicationService (FR-PJ-04 PR-4 Task 5).
 *
 * PROJECT_ADMIN 게이트 = 컴포넌트 UPDATE 권한 재사용
 * ([com.bts.issue.project.settings.ProjectSettingsService] PR-3 선례, 명시 호출 — `@PreAuthorize
 * hasRole` 아님. PAT 경로에는 role claim 이 없어 선언적 경로가 동작하지 않는다,
 * `UserGroupController.kt:53-58` 근거).
 *
 * ## D-UNARCHIVE — [ProjectArchiveGuard] 미소비
 * archive/unarchive 자체는 프로젝트 스코프 **쓰기 잠금의 대상이 아니라 라이프사이클 op**이다(plan
 * D-UNARCHIVE). 이미 아카이브된 프로젝트에도 `unarchive` 가 동작해야 하므로 이 서비스는
 * [ProjectArchiveGuard] 를 호출하지 않는다 — Version/Component 등 프로젝트 스코프 쓰기(Task 7/9)와
 * 구별되는 지점이다.
 *
 * ## 흐름 — 존재 → 권한 → 영속 ([resolveAndAuthorize])
 * 1. [ProjectLookup.resolve] 로 projectIdOrKey → 활성 프로젝트 UUID 해석. null 이면
 *    [ProjectArchiveNotFoundException](404 의도).
 * 2. [ComponentPermissionResolver] 로 컴포넌트 UPDATE 권한 검증. 거부 시
 *    [ProjectArchiveForbiddenException](403 의도) — 미인가 actor 가 상태(아카이브 여부)를 알아내지
 *    못하도록 권한 검증을 항상 먼저 수행한다(D-ORDER).
 * 3. [ProjectArchiveRepository] 로 archived_at 갱신 — 갱신 0행이면 TOCTOU 재확인으로
 *    [ProjectArchiveNotFoundException] 재던짐.
 *
 * ## EC-2 — 멱등 200
 * archive/unarchive 모두 이미 같은 상태에 대해 재호출해도 409 가 아니라 200 이다 —
 * [ProjectArchiveRepository] 가 조건 없이 덮어쓰므로 서비스 레벨에서 별도 분기가 필요 없다.
 *
 * ## Clock 주입 (PJ4-5)
 * `Instant.now()` 직접 호출 대신 [Clock] 을 주입받아 [Clock.instant] 로 archived_at 타임스탬프를
 * 얻는다([com.bts.issue.version.application.VersionApplicationService] 동형 — 생성자 기본값
 * `Clock.systemUTC()`).
 *
 * ## 트랜잭션
 * 클래스 레벨 @Transactional 이 기본 — DEVELOPMENT.md §1, public service 메서드 전체
 * @Transactional 명시 원칙.
 */
@Service
@Transactional
class ProjectArchiveService(
    private val projectLookup: ProjectLookup,
    private val componentPermissionResolver: ComponentPermissionResolver,
    private val repository: ProjectArchiveRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트를 아카이브한다 — `archived_at = clock.instant()`.
     *
     * @param actorId 요청 행위자 UUID — 컴포넌트 UPDATE 권한 검증 대상.
     * @param projectIdOrKey 대상 프로젝트 UUID 문자열 또는 projectKey.
     * @return [ProjectArchiveResult] — projectId + projectKey + archivedAt(non-null).
     * @throws ProjectArchiveNotFoundException 프로젝트가 미존재하거나 소프트삭제된 경우(404 의도).
     * @throws ProjectArchiveForbiddenException 행위자에게 컴포넌트 UPDATE 권한이 없는 경우(403 의도).
     */
    fun archive(
        actorId: UUID,
        projectIdOrKey: String,
    ): ProjectArchiveResult {
        log.info("ProjectArchiveService.archive actor={} projectIdOrKey={}", actorId, projectIdOrKey)

        val projectId = resolveAndAuthorize(actorId, projectIdOrKey)

        val updated =
            repository.archive(projectId, clock.instant())
                ?: throw ProjectArchiveNotFoundException(projectIdOrKey)

        return ProjectArchiveResult(projectId, updated.key, updated.archivedAt)
    }

    /**
     * 프로젝트의 아카이브를 해제한다 — `archived_at = NULL`.
     *
     * 아카이브 상태에서도 정상 동작한다(D-UNARCHIVE — [ProjectArchiveGuard] 미소비).
     *
     * @param actorId 요청 행위자 UUID — 컴포넌트 UPDATE 권한 검증 대상.
     * @param projectIdOrKey 대상 프로젝트 UUID 문자열 또는 projectKey.
     * @return [ProjectArchiveResult] — projectId + projectKey + archivedAt(null).
     * @throws ProjectArchiveNotFoundException 프로젝트가 미존재하거나 소프트삭제된 경우(404 의도).
     * @throws ProjectArchiveForbiddenException 행위자에게 컴포넌트 UPDATE 권한이 없는 경우(403 의도).
     */
    fun unarchive(
        actorId: UUID,
        projectIdOrKey: String,
    ): ProjectArchiveResult {
        log.info("ProjectArchiveService.unarchive actor={} projectIdOrKey={}", actorId, projectIdOrKey)

        val projectId = resolveAndAuthorize(actorId, projectIdOrKey)

        val updated =
            repository.unarchive(projectId)
                ?: throw ProjectArchiveNotFoundException(projectIdOrKey)

        return ProjectArchiveResult(projectId, updated.key, updated.archivedAt)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * projectIdOrKey 를 활성 프로젝트 UUID 로 해석하고, 행위자의 컴포넌트 UPDATE 권한을 검증한다.
     *
     * archive/unarchive 가 공유하는 "존재 → 권한" 단계 — D-ORDER(권한 검증을 항상 먼저 수행해
     * 미인가 actor 가 아카이브 상태를 유추하지 못하게 한다).
     *
     * @param actorId 권한 검사 대상 행위자 UUID.
     * @param projectIdOrKey 대상 프로젝트 UUID 문자열 또는 projectKey.
     * @return 해석된 활성 프로젝트 UUID.
     * @throws ProjectArchiveNotFoundException 프로젝트 미존재.
     * @throws ProjectArchiveForbiddenException 컴포넌트 UPDATE 권한 없음.
     */
    private fun resolveAndAuthorize(
        actorId: UUID,
        projectIdOrKey: String,
    ): UUID {
        val projectId =
            projectLookup.resolve(projectIdOrKey)
                ?: throw ProjectArchiveNotFoundException(projectIdOrKey)

        if (!componentPermissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId)) {
            throw ProjectArchiveForbiddenException(actorId, projectId)
        }
        return projectId
    }
}

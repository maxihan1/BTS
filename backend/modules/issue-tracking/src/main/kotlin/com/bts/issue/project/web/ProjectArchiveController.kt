// POST /api/v1/projects/{projectIdOrKey}/archive · /unarchive PROJECT_ADMIN 전용 컨트롤러 (FR-PJ-04 PR-4 Task 5)

package com.bts.issue.project.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.project.archive.ProjectArchiveForbiddenException
import com.bts.issue.project.archive.ProjectArchiveNotFoundException
import com.bts.issue.project.archive.ProjectArchiveResult
import com.bts.issue.project.archive.ProjectArchiveService
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * 프로젝트 아카이브(archive)/아카이브 해제(unarchive) REST API 컨트롤러 (FR-PJ-04 PR-4 Task 5).
 *
 * 엔드포인트 — POST /api/v1/projects/{projectIdOrKey}/archive · /unarchive. 둘 다 PROJECT_ADMIN
 * 전용(컴포넌트 UPDATE 권한 재사용, [ProjectSettingsController] PR-3 선례).
 *
 * ### 왜 명시 게이트인가 (`@PreAuthorize hasRole` 아님) — PAT 경로 대응
 * 클래스 레벨 `@PreAuthorize("isAuthenticated()")` 는 "인증됨"만 확인한다. PROJECT_ADMIN 여부(컴포넌트
 * UPDATE 권한) 자체는 [ProjectArchiveService] 안에서 [com.bts.shared.permission.
 * ComponentPermissionResolver] 를 **명시 호출**해 판정한다. `@PreAuthorize hasRole(...)` 같은 선언적
 * 권한 검사를 쓰지 않는 이유는, JWT 세션 경로와 달리 **PAT(Personal Access Token) 경로에는 role
 * claim 이 아예 없기 때문**이다 — 두 인증 경로 모두에서 일관된 판정을 보장하려면 DB 를 직접 조회하는
 * 명시 호출이 유일한 방법이다(`UserGroupController.kt:53-58` 근거, [ProjectSettingsController] 동형).
 *
 * ### 이중 인증 가드
 * 1. 클래스 레벨 `@PreAuthorize("isAuthenticated()")` + prod SecurityConfig 필터 체인(미인증 401).
 * 2. 핸들러의 [CurrentActor.current] — 미인증/nil-UUID/형식오류 시 401.
 *
 * ### actor 추출 순서 — 리소스 조회보다 먼저
 * [CurrentActor.current] 를 **가장 먼저** 추출한다(memory: auth-extraction-before-resource-lookup).
 * projectIdOrKey 해석/권한 판정은 [ProjectArchiveService] 안에서 일어나므로, 미인증자는 서비스에
 * 도달하지 못한다.
 *
 * ### D-UNARCHIVE — 아카이브 잠금(409) 대상이 아니다
 * archive/unarchive 자체는 [com.bts.issue.project.archive.ProjectArchiveGuard] 가 막는 프로젝트
 * 스코프 쓰기가 아니라 **라이프사이클 오퍼레이션**이다 — `unarchive` 는 아카이브된 프로젝트에서도
 * 정상 동작해야 하므로, 이 컨트롤러/서비스는 그 guard 를 호출하지 않는다.
 *
 * @param service 프로젝트 아카이브/아카이브 해제 Application Service.
 */
@RestController
@RequestMapping("/api/v1/projects")
@PreAuthorize("isAuthenticated()")
class ProjectArchiveController(
    private val service: ProjectArchiveService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트를 아카이브한다.
     *
     * EC-2(멱등) — 이미 아카이브된 프로젝트를 다시 아카이브해도 409 가 아니라 200 이다.
     *
     * @param projectIdOrKey path variable — 프로젝트 UUID 또는 projectKey.
     * @return 200 OK + [ProjectArchiveResponse] body(archivedAt non-null).
     * @throws org.springframework.web.server.ResponseStatusException 미인증 → 401(CurrentActor).
     * @throws ProjectArchiveNotFoundException 프로젝트 미존재 → 404.
     * @throws ProjectArchiveForbiddenException 컴포넌트 UPDATE 권한 없음(PROJECT_ADMIN 아님) → 403.
     */
    @PostMapping("/{projectIdOrKey}/archive")
    fun archive(
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<DataResponse<ProjectArchiveResponse>> {
        val actor = CurrentActor.current()
        log.info("ProjectArchiveController.archive actor={} projectIdOrKey={}", actor.value, projectIdOrKey)

        val result = service.archive(actor.value, projectIdOrKey)
        return ResponseEntity.ok(DataResponse(data = ProjectArchiveResponse.from(result)))
    }

    /**
     * 프로젝트의 아카이브를 해제한다.
     *
     * D-UNARCHIVE — 이미 아카이브된 프로젝트에서도 정상 동작한다(guard 우회). EC-2(멱등) — 이미
     * 활성인 프로젝트를 다시 해제해도 409 가 아니라 200 이다.
     *
     * @param projectIdOrKey path variable — 프로젝트 UUID 또는 projectKey.
     * @return 200 OK + [ProjectArchiveResponse] body(archivedAt null).
     * @throws org.springframework.web.server.ResponseStatusException 미인증 → 401(CurrentActor).
     * @throws ProjectArchiveNotFoundException 프로젝트 미존재 → 404.
     * @throws ProjectArchiveForbiddenException 컴포넌트 UPDATE 권한 없음(PROJECT_ADMIN 아님) → 403.
     */
    @PostMapping("/{projectIdOrKey}/unarchive")
    fun unarchive(
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<DataResponse<ProjectArchiveResponse>> {
        val actor = CurrentActor.current()
        log.info("ProjectArchiveController.unarchive actor={} projectIdOrKey={}", actor.value, projectIdOrKey)

        val result = service.unarchive(actor.value, projectIdOrKey)
        return ResponseEntity.ok(DataResponse(data = ProjectArchiveResponse.from(result)))
    }
}

/**
 * 아카이브/아카이브 해제 응답 DTO.
 *
 * @property projectId 프로젝트 UUID.
 * @property projectKey 프로젝트 키.
 * @property archivedAt 갱신 후 archived_at 값. archive 응답은 non-null, unarchive 응답은 null.
 */
data class ProjectArchiveResponse(
    val projectId: UUID,
    val projectKey: String,
    val archivedAt: Instant?,
) {
    companion object {
        /**
         * [ProjectArchiveResult] 를 [ProjectArchiveResponse] 로 변환한다.
         *
         * @param result ApplicationService 반환값.
         * @return HTTP 응답 DTO.
         */
        fun from(result: ProjectArchiveResult): ProjectArchiveResponse =
            ProjectArchiveResponse(
                projectId = result.projectId,
                projectKey = result.projectKey,
                archivedAt = result.archivedAt,
            )
    }
}

/**
 * 프로젝트 아카이브/아카이브 해제 에러 코드 상수.
 *
 * 모든 코드는 `ISSUE_` 접두사를 사용한다 (BTS 에러 코드 규칙 §1.16).
 */
internal object ProjectArchiveErrorCodes {
    /** 컴포넌트 UPDATE 권한 없음(PROJECT_ADMIN 아님) — 403. */
    const val FORBIDDEN = "ISSUE_PROJECT_ARCHIVE_FORBIDDEN"

    /** 프로젝트 미존재 — 404. */
    const val PROJECT_NOT_FOUND = "ISSUE_PROJECT_ARCHIVE_NOT_FOUND"
}

/**
 * [ProjectArchiveController] 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * `assignableTypes = [ProjectArchiveController::class]` 로 스코프를 좁혀 다른 컨트롤러의 예외를 잡지
 * 않는다. `@Order(Ordered.HIGHEST_PRECEDENCE)` — 같은 패키지 [ProjectLeadExceptionHandler] 의
 * `basePackages = ["com.bts.issue.project.web"]` 전역 스코프 + catch-all `Exception::class` fallback
 * 이 이 컨트롤러의 401/403/404 를 500 으로 삼키는 것을 방지한다
 * ([ProjectSettingsExceptionHandler] 선례,
 * memory: catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * ## 매핑 규칙
 * - [ResponseStatusException] → 원 상태 코드(미인증 401 등) 그대로 통과
 * - [ProjectArchiveNotFoundException] → 404 + [ProjectArchiveErrorCodes.PROJECT_NOT_FOUND]
 * - [ProjectArchiveForbiddenException] → 403 + [ProjectArchiveErrorCodes.FORBIDDEN](내부구조 미노출)
 */
@RestControllerAdvice(assignableTypes = [ProjectArchiveController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProjectArchiveExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── ResponseStatusException 재전파 — 401 프레임워크 예외 삼킴 방지 ─────────────

    /**
     * [ResponseStatusException] — 프레임워크 상태 코드 예외를 그대로 전달한다.
     *
     * [CurrentActor] 가 미인증 시 던지는 401 이 catch-all fallback 에 잡혀 500 으로 변질되는 것을
     * 막는다(memory: catch-all-exceptionhandler-swallows-responsestatusexception).
     *
     * @param ex 프레임워크가 생성한 ResponseStatusException.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ProblemDetail {
        log.debug("PROJECT_ARCHIVE ResponseStatusException status={} message='{}'", ex.statusCode, ex.message)
        val pd = ProblemDetail.forStatus(ex.statusCode.value())
        pd.detail = ex.reason
        return pd
    }

    // ── 404 PROJECT_NOT_FOUND ─────────────────────────────────────────────────

    /**
     * [ProjectArchiveNotFoundException] — 프로젝트 미존재(또는 갱신 시점 경합으로 소프트삭제) — 404.
     *
     * @param ex 조회한 projectIdOrKey 정보를 포함하는 예외.
     */
    @ExceptionHandler(ProjectArchiveNotFoundException::class)
    fun handleProjectNotFound(ex: ProjectArchiveNotFoundException): ProblemDetail {
        log.info("PROJECT_ARCHIVE_404 project_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "project-archive-not-found",
            title = "Project Not Found",
            errorCode = ProjectArchiveErrorCodes.PROJECT_NOT_FOUND,
            detail = "프로젝트를 찾을 수 없습니다.",
        )
    }

    // ── 403 FORBIDDEN ─────────────────────────────────────────────────────────

    /**
     * [ProjectArchiveForbiddenException] — 컴포넌트 UPDATE 권한 없음(PROJECT_ADMIN 아님) — 403.
     *
     * HTTP 응답 detail 에 actorId/projectId 등 내부 상세를 노출하지 않는다
     * (memory: guard-exception-message-http-leak).
     *
     * @param ex 권한 거부 예외 (message 에 내부 식별자 포함 — 로그 전용).
     */
    @ExceptionHandler(ProjectArchiveForbiddenException::class)
    fun handleForbidden(ex: ProjectArchiveForbiddenException): ProblemDetail {
        log.warn("PROJECT_ARCHIVE_403 forbidden message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "project-archive-forbidden",
            title = "Forbidden",
            errorCode = ProjectArchiveErrorCodes.FORBIDDEN,
            detail = "프로젝트를 아카이브/아카이브 해제할 권한이 없습니다.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type URI type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([ProjectArchiveErrorCodes]).
     * @param detail 이 특정 발생에 대한 상세 설명(내부구조 미노출 일반 메시지).
     * @return 완성된 [ProblemDetail] 인스턴스.
     */
    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        errorCode: String,
        detail: String,
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/$type")
        pd.title = title
        pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }
}

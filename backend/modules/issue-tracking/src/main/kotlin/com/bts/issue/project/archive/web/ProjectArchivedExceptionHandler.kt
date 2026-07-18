// ProjectArchivedException 을 HTTP 409 로 매핑하는 전용 핸들러 (FR-PJ-04 PR-4 Task 3)

package com.bts.issue.project.archive.web

import com.bts.issue.project.archive.ProjectArchivedException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * [ProjectArchivedException] → HTTP 409(Conflict) 전용 매핑 핸들러.
 *
 * ## 왜 전용 단일 타입 advice 인가 (PR-3 assignableTypes 선례와 다른 이유)
 * PR-3 의 [com.bts.issue.project.web.ProjectQueryExceptionHandler] 는 예외가 단일 컨트롤러 흐름에서만
 * 발생하므로 `assignableTypes = [ProjectQueryController]` 로 좁혔다. 그러나 [ProjectArchivedException]
 * 은 [com.bts.issue.project.archive.ProjectArchiveGuard] 가 던지는 **cross-cutting** 예외로,
 * Version·Component·CustomField·IssueTemplate·Settings·Issue·Attachment·Worklog·Watcher·Link·Move 등
 * 다수 컨트롤러에서 표면화된다(소비는 Task 7/8/9). 하나의 컨트롤러로 assignableTypes 를 좁힐 대상이 없다.
 *
 * 그래서 이 advice 는 `@ExceptionHandler(ProjectArchivedException::class)` **한 종류만** 처리한다.
 * catch-all `Exception::class` 도, 넓은 `basePackages` 도 아니므로 401/403/500·
 * [org.springframework.web.server.ResponseStatusException] 등 **다른 예외를 구조적으로 삼킬 수 없다**
 * (메모리 catch-all-exceptionhandler-swallows-responsestatusexception 의 위험 원천 자체를 차단).
 * 어느 컨트롤러에서 던져지든 항상 409 로 매핑돼, 소비 컨트롤러가 개별 핸들러를 빠뜨려도 500(내부 누출)로
 * 새지 않는 fail-safe 중앙 매핑이다. `@Order(HIGHEST_PRECEDENCE)` — 소비 컨트롤러가 향후 자체 핸들러를
 * 추가하더라도 이 매핑이 우선한다.
 *
 * ## 내부 상태 미노출
 * 응답 detail 에 예외 message(projectId/projectKey/issueKey)를 그대로 노출하지 않고 일반 메시지로
 * 치환한다(메모리 fr-pm-04-guard-exception-message-http-leak).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProjectArchivedExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [ProjectArchivedException] — 아카이브된 프로젝트에 대한 쓰기 시도 — 409.
     *
     * @param ex 아카이브 판정 식별자를 담은 예외(message 는 로그 전용, 응답 미노출).
     * @return 내부 상세를 감춘 RFC 7807 [ProblemDetail] (409).
     */
    @ExceptionHandler(ProjectArchivedException::class)
    fun handleArchived(ex: ProjectArchivedException): ProblemDetail {
        log.info("PROJECT_ARCHIVED_409 message='{}'", ex.message)
        val pd = ProblemDetail.forStatus(HttpStatus.CONFLICT)
        pd.type = URI.create("https://bts.example.com/problems/project-archived")
        pd.title = "Project Archived"
        pd.detail = "아카이브된 프로젝트에는 쓰기를 할 수 없습니다."
        pd.setProperty("errorCode", "ISSUE_PROJECT_ARCHIVED")
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }
}

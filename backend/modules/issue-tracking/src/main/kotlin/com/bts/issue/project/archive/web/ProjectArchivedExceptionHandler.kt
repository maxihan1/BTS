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
        // ★★ instance 를 반드시 명시한다 — 지우지 말 것 (N3).
        // Spring MVC 의 RequestResponseBodyMethodProcessor 는 instance 가 null 이면 **요청 URI 로
        // 자동으로 채운다**(#310 이 실측 확정한 기전). 이 advice 는 **선택자가 없어 레포의 어느
        // 컨트롤러에도 붙고**, 그중 4개 경로가 경로 세그먼트에 원문 비밀 토큰을 싣는다
        // (공유 대시보드 토큰·iCal 피드 토큰·git 웹훅 토큰·automation 웹훅 토큰).
        // 그 경로에서 이 핸들러가 발동하면 응답 본문에 평문 토큰이 실린다(DEVELOPMENT.md §1.1-1·§1.1-2).
        //
        // 값이 요청 경로가 아니라 문제 종류인 이유. 이 advice 는 전역이라 고정 엔드포인트가 없다.
        // 요청 URI 를 살려 쓰려면 "비밀 경로인가"를 가리는 판별식이 필요한데, 그 처방은
        // ADR 2026-07-25 §D1 이 기각했다 — 목록은 새 비밀 경로가 생길 때마다 갱신돼야 하고
        // 빠뜨리면 조용히 샌다. 진단은 위 log.info 와 접속 로그(#311 로 토큰만 마스킹됨)가 담당한다.
        pd.instance = URI.create(INSTANCE_PATH)
        pd.title = "Project Archived"
        pd.detail = "아카이브된 프로젝트에는 쓰기를 할 수 없습니다."
        pd.setProperty("errorCode", "ISSUE_PROJECT_ARCHIVED")
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }

    private companion object {
        /**
         * `instance` 고정값 — **요청 URI 가 아니다**(위 handleArchived 주석의 사유).
         * automation 두 웹훅 컨트롤러와 `PublicDashboardController` 가 같은 처방(고정 경로)을 쓰지만,
         * 그쪽은 단일 엔드포인트라 "토큰 뺀 그 엔드포인트 경로"를 쓸 수 있었다. 전역 advice 인 이 클래스는
         * 그럴 대상이 없어 문제 종류를 가리키는 경로를 쓴다.
         */
        const val INSTANCE_PATH = "/problems/project-archived"
    }
}

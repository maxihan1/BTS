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
import java.util.UUID

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
        // ★★ instance 를 반드시 명시한다 — 지우지 말 것 (N3).
        // Spring MVC 의 RequestResponseBodyMethodProcessor 는 instance 가 null 이면 **요청 URI 로
        // 자동으로 채운다**(#310 이 실측 확정한 기전). 이 advice 는 **선택자가 없어 레포의 어느
        // 컨트롤러에도 붙고**, 그중 4개 경로가 경로 세그먼트에 원문 비밀 토큰을 싣는다
        // (공유 대시보드 토큰·iCal 피드 토큰·git 웹훅 토큰·automation 웹훅 토큰).
        // 그 경로에서 이 핸들러가 발동하면 응답 본문에 평문 토큰이 실린다(DEVELOPMENT.md §1.1-1·§1.1-2).
        //
        // ## 값이 발생 UUID 인 이유 (RFC 9457 §3.1.5 시맨틱 + 진단성 회복)
        // 요청 URI 를 살려 쓰려면 "비밀 경로인가"를 가리는 판별식이 필요한데, 그 처방은
        // ADR 2026-07-25 §D1 이 기각했다 — 목록은 새 비밀 경로가 생길 때마다 갱신돼야 하고
        // 빠뜨리면 조용히 샌다. 그렇다고 고정 문자열을 쓰면 `instance` 가 "특정 **발생**을 식별하는
        // URI" 라는 RFC 시맨틱을 잃고 `type` 과 사실상 중복이 된다.
        //
        // 그래서 요청마다 새 UUID 를 발급해 **응답의 instance 와 서버 로그에 같은 값**을 싣는다.
        // 유출 위험 0(요청 정보와 무관한 난수)이면서, 사용자가 붙여넣은 오류 응답 하나로 서버 로그를
        // 곧장 특정할 수 있다 — #310 이 `instance` 를 정화하며 포기했던 진단성을 되찾는 유일한 방향이다.
        // **이 상관관계가 계약이다.** 로그와 응답 중 한쪽만 바꾸면 그 값어치가 사라지므로
        // `ProjectArchivedExceptionHandlerTest` 가 두 값의 일치를 못 박는다.
        val occurrenceId = UUID.randomUUID()
        log.info("PROJECT_ARCHIVED_409 occurrenceId={} message='{}'", occurrenceId, ex.message)

        val pd = ProblemDetail.forStatus(HttpStatus.CONFLICT)
        pd.type = URI.create("https://bts.example.com/problems/project-archived")
        pd.instance = URI.create("$INSTANCE_URN_PREFIX$occurrenceId")
        pd.title = "Project Archived"
        pd.detail = "아카이브된 프로젝트에는 쓰기를 할 수 없습니다."
        pd.setProperty("errorCode", "ISSUE_PROJECT_ARCHIVED")
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }

    companion object {
        /**
         * `instance` 의 URN 접두사. RFC 4122 UUID URN 형식(`urn:uuid:<uuid>`)이라 RFC 9457 의
         * "특정 발생을 식별하는 URI" 요구를 충족하면서 **요청 정보를 일절 담지 않는다**.
         */
        const val INSTANCE_URN_PREFIX = "urn:uuid:"

        /** 로그↔응답 상관관계의 로그 측 키. 테스트가 이 키로 발생 UUID 를 되찾는다. */
        const val LOG_OCCURRENCE_KEY = "occurrenceId="
    }
}

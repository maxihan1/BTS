// CFD 컨트롤러 전용 예외 핸들러 — CfdController 스코프 한정 (FR-RP-03 Task 5)

package com.bts.issue.cfd.web

import com.bts.issue.domain.IssueAccessDeniedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * CFD 컨트롤러 예외 → HTTP 응답 변환 핸들러.
 *
 * `basePackages` 를 `com.bts.issue.cfd.web` 으로 한정한다.
 *
 * ### catch-all Exception 핸들러 미포함
 * `@ExceptionHandler(Exception::class)` catch-all 은 포함하지 않는다.
 * catch-all 이 [ResponseStatusException](401/400) 을 삼켜 500 으로 변질시키는 사고
 * (FR-WT-01 교훈, [com.bts.issue.worklog.aggregate.web.WorklogAggregateExceptionHandler] 선례)를
 * 반복하지 않기 위해 구체 핸들러만 선언한다. 분류되지 않은 예외는 Spring 기본 처리에 위임한다.
 *
 * ### 매핑 규칙
 * - [ResponseStatusException] → 상태 코드 보존 (401/400 등 catch-all 삼킴 차단)
 * - [IssueAccessDeniedException] → 403 Forbidden (detail 에 내부 정보 비노출)
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.cfd.web"])
class CfdExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── ResponseStatusException 상태 전파 (401/400 catch-all 변질 차단) ───────

    /**
     * [ResponseStatusException] — 컨트롤러가 명시한 HTTP 상태를 그대로 전파한다.
     *
     * [com.bts.issue.adapter.inbound.rest.CurrentActor.current] 가 미인증/nil-UUID/비-UUID 시
     * 던지는 401 이 catch-all 에 삼켜 500 으로 변질되는 것을 차단한다.
     * 컨트롤러 파라미터 검증 400 도 이 핸들러를 통해 전파된다.
     *
     * @param ex 상태 코드 보유 예외.
     * @return [ProblemDetail] (상태 코드 보존).
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("CFD_{} response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED -> "UNAUTHENTICATED" to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.BAD_REQUEST -> CFD_VALIDATION_FAILED to (ex.reason ?: "요청 파라미터가 올바르지 않습니다.")
                else -> "ISSUE_INTERNAL_ERROR" to "요청을 처리할 수 없습니다."
            }
        return problem(
            status = status,
            type = "response-status",
            title = status.reasonPhrase,
            errorCode = errorCode,
            detail = detail,
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * [IssueAccessDeniedException] → 403 Forbidden.
     *
     * 응답 detail 에 행위자 UUID, 권한명, 프로젝트 키 등 내부 정보를 노출하지 않는다.
     * 프로젝트 존재 여부 누출 차단.
     *
     * @param ex 행위자/권한/범위 정보를 포함하는 예외 (로그에만 기록).
     * @return 403 [ProblemDetail].
     */
    @ExceptionHandler(IssueAccessDeniedException::class)
    fun handleAccessDenied(ex: IssueAccessDeniedException): ProblemDetail {
        log.info("CFD_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "access-denied",
            title = "Access Denied",
            errorCode = "ISSUE_ACCESS_DENIED",
            detail = "이 작업을 수행할 권한이 없습니다.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * [ProblemDetail] (RFC 7807) 인스턴스를 생성하는 헬퍼.
     *
     * @param status    HTTP 응답 상태 코드.
     * @param type      type suffix (URI 경로에 추가).
     * @param title     문제 유형 요약.
     * @param errorCode BTS 에러 코드.
     * @param detail    상세 설명. null 이면 생략.
     * @return [ProblemDetail].
     */
    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        errorCode: String,
        detail: String?,
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/$type")
        pd.title = title
        if (detail != null) pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }

    companion object {
        /** CFD 파라미터 검증 실패 에러 코드. */
        const val CFD_VALIDATION_FAILED = "ISSUE_CFD_VALIDATION_FAILED"
    }
}

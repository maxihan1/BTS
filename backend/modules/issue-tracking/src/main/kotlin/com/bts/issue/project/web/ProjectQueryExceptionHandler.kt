// ProjectQueryController 전용 예외 핸들러 — ResponseStatusException 재전파 (FR-PJ PR-3 Task 3)

package com.bts.issue.project.web

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * [ProjectQueryController] 전용 예외 핸들러.
 *
 * `assignableTypes = [ProjectQueryController::class]` 로 스코프를 좁혀 다른 컨트롤러의 예외를 잡지
 * 않는다. `@Order(Ordered.HIGHEST_PRECEDENCE)` — 같은 패키지 [ProjectLeadExceptionHandler] 의
 * `basePackages = ["com.bts.issue.project.web"]` 전역 스코프 + catch-all `Exception::class` fallback
 * 이 이 컨트롤러의 401 을 500 으로 삼키는 것을 방지한다([ProjectCreateExceptionHandler] 선례,
 * 메모리 catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * ## 매핑 규칙
 * - [ResponseStatusException] → 원 상태 코드 (미인증 401 등) 그대로 통과
 */
@RestControllerAdvice(assignableTypes = [ProjectQueryController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProjectQueryExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [ResponseStatusException] — 프레임워크 상태 코드 예외를 그대로 전달한다.
     *
     * [com.bts.issue.adapter.inbound.rest.CurrentActor] 가 미인증 시 던지는 401 이 catch-all
     * fallback 에 잡혀 500 으로 변질되는 것을 막는다.
     *
     * @param ex 프레임워크가 생성한 ResponseStatusException.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ProblemDetail {
        log.debug("PROJECT_QUERY ResponseStatusException status={} message='{}'", ex.statusCode, ex.message)
        val pd = ProblemDetail.forStatus(ex.statusCode.value())
        pd.detail = ex.reason
        return pd
    }
}

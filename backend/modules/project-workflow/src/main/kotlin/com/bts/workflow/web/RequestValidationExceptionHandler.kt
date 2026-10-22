// 워크플로우·상태 쓰기 API 의 요청 DTO 검증(@Valid) 실패를 400 표준 본문으로 바꾸는 advice

package com.bts.workflow.web

import com.bts.workflow.status.web.StatusController
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * `@Valid` 요청 바디 검증 실패 — 400 + `WORKFLOW_INVALID_REQUEST`.
 *
 * ★ 스코프를 **컨트롤러로 좁힌다.** [WorkflowExceptionHandler] 는 전역이라 거기서
 * [MethodArgumentNotValidException] 을 잡으면 다른 BC 의 검증 실패 응답 형식까지 빼앗는다.
 * 핸들러가 없으면 Spring 기본 400 이 나가 프론트가 읽는 `{ error: { code, message } }` 와 어긋난다.
 */
@RestControllerAdvice(
    assignableTypes = [WorkflowController::class, WorkflowStatusCompositionController::class, StatusController::class],
)
class RequestValidationExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleInvalidBody(ex: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val reason = ex.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" }
        log.info("WORKFLOW_400 body reason='{}'", reason)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(error = ErrorBody(code = "WORKFLOW_INVALID_REQUEST", message = reason)),
        )
    }
}

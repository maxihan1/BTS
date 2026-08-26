// post-action 도메인 예외를 HTTP 응답으로 변환하는 @ControllerAdvice

package com.bts.workflow.postaction.web

import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.workflow.postaction.PostActionNotFoundException
import com.bts.workflow.postaction.PostActionValidationException
import com.bts.workflow.validator.web.TransitionRuleFrameworkErrors
import com.bts.workflow.web.ErrorBody
import com.bts.workflow.web.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

/**
 * post-action 관리 API 도메인 예외를 HTTP 응답으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.workflow.postaction` 으로 한정하여 다른 패키지 컨트롤러 예외를 잡지 않는다.
 *
 * 매핑 규칙.
 * - [PostActionValidationException] → 400 Bad Request + WORKFLOW_POST_ACTION_INVALID
 * - [PostActionNotFoundException]   → 404 Not Found  + WORKFLOW_POST_ACTION_NOT_FOUND
 * - [MethodArgumentTypeMismatchException] → 400 + WORKFLOW_INVALID_REQUEST
 * - [HttpMessageNotReadableException]     → 400 + WORKFLOW_INVALID_REQUEST
 * - [ResponseStatusException]             → 예외가 지정한 상태 + WORKFLOW_UNAUTHENTICATED 등
 *
 * ### 응답 봉투는 모듈 공용 한 벌이다
 * `com.bts.workflow.web` 의 [ErrorResponse]/[ErrorBody] 를 쓴다. 같은 `{error:{code,message}}` 를
 * 패키지마다 따로 선언하면 사본이 늘어나는데, 사본들은 서로를 검사하지 않아 한쪽만 바뀐 사실이
 * 드러나지 않는다. 형제 `ValidatorExceptionHandler` 도 같은 봉투를 쓴다 (게이트 1 결정, 2026-08-25).
 */
@RestControllerAdvice(basePackages = ["com.bts.workflow.postaction"])
class PostActionExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 워크플로우 스킴 권한 거부 — 403.
     *
     * actorId/permission/scope 등 내부 식별자는 로그에만 남기고 응답에는 일반 메시지만 노출한다.
     * (메모리 fr-pm-04-guard-exception-message-http-leak 준수)
     *
     * @param ex 거부된 행위자·권한·범위 정보를 담은 예외.
     */
    @ExceptionHandler(WorkflowSchemeAccessDeniedException::class)
    fun handleAccessDenied(ex: WorkflowSchemeAccessDeniedException): ResponseEntity<ErrorResponse> {
        log.info("POST_ACTION_403 access_denied detail='{}'", ex.message)
        val errorBody =
            ErrorBody(
                code = WorkflowSchemeAccessDeniedException.WORKFLOW_SCHEME_ACCESS_DENIED,
                message = "이 작업을 수행할 권한이 없습니다.",
            )
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse(errorBody))
    }

    /**
     * post-action 검증 실패 — 400.
     *
     * @param ex 검증 실패 사유를 담은 예외.
     */
    @ExceptionHandler(PostActionValidationException::class)
    fun handleValidation(ex: PostActionValidationException): ResponseEntity<ErrorResponse> {
        log.info("POST_ACTION_400 reason='{}'", ex.reason)
        val errorBody =
            ErrorBody(code = "WORKFLOW_POST_ACTION_INVALID", message = "post-action 설정이 유효하지 않습니다.")
        return ResponseEntity.badRequest().body(ErrorResponse(errorBody))
    }

    /**
     * post-action 또는 전환 미존재 — 404.
     *
     * @param ex 조회를 시도한 대상 상세 정보를 담은 예외.
     */
    @ExceptionHandler(PostActionNotFoundException::class)
    fun handleNotFound(ex: PostActionNotFoundException): ResponseEntity<ErrorResponse> {
        log.info("POST_ACTION_404 detail='{}'", ex.detail)
        val errorBody =
            ErrorBody(code = "WORKFLOW_POST_ACTION_NOT_FOUND", message = "post-action 또는 전환을 찾을 수 없습니다.")
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse(errorBody))
    }

    /**
     * 경로 변수 타입 불일치(비-UUID `{id}` 등) — 400.
     *
     * 파라미터 이름·타입·들어온 값은 로그에만 남기고 응답에는 일반 메시지만 노출한다.
     *
     * @param ex 변환에 실패한 파라미터 정보를 담은 예외.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        log.info("POST_ACTION_400 type_mismatch param='{}'", ex.name)
        val errorBody =
            ErrorBody(
                code = TransitionRuleFrameworkErrors.INVALID_REQUEST,
                message = "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
            )
        return ResponseEntity.badRequest().body(ErrorResponse(errorBody))
    }

    /**
     * 요청 본문 파싱 실패(깨진 JSON · 필수 필드 누락) — 400.
     *
     * 깨진 본문에는 사용자 입력이 그대로 들어 있을 수 있으므로 로그에도 본문 조각을 남기지 않고
     * **원인 예외의 타입 이름만** 남긴다.
     *
     * @param ex 본문을 읽지 못한 원인을 담은 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.info("POST_ACTION_400 message_not_readable cause='{}'", ex.mostSpecificCause.javaClass.simpleName)
        val errorBody =
            ErrorBody(
                code = TransitionRuleFrameworkErrors.INVALID_REQUEST,
                message = "요청 본문을 읽을 수 없습니다.",
            )
        return ResponseEntity.badRequest().body(ErrorResponse(errorBody))
    }

    /**
     * [ResponseStatusException] — 미인증(401) 이 대표 경로다.
     *
     * 이 401 은 Spring Security 필터가 아니라 `CurrentActor.current()` 가 **컨트롤러 실행 중**에
     * 던지므로 이 advice 가 잡는다. 상태는 예외가 지정한 것을 그대로 싣는다 — 401 로 못박으면
     * 이 패키지에 다른 상태의 [ResponseStatusException] 이 생기는 날 상태가 조용히 둔갑한다.
     *
     * @param ex 상태 코드와 사유를 지정한 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        log.info("POST_ACTION_{} response_status reason='{}'", ex.statusCode.value(), ex.reason)
        val unauthenticated = ex.statusCode == HttpStatus.UNAUTHORIZED
        val errorBody =
            if (unauthenticated) {
                ErrorBody(
                    code = TransitionRuleFrameworkErrors.UNAUTHENTICATED,
                    message = "인증이 필요합니다. 다시 로그인해 주세요.",
                )
            } else {
                ErrorBody(
                    code = TransitionRuleFrameworkErrors.REQUEST_REJECTED,
                    message = "요청을 처리할 수 없습니다.",
                )
            }
        return ResponseEntity.status(ex.statusCode).body(ErrorResponse(errorBody))
    }
}

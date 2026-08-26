// post-action 도메인 예외를 HTTP 응답으로 변환하는 @ControllerAdvice

package com.bts.workflow.postaction.web

import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.workflow.postaction.PostActionNotFoundException
import com.bts.workflow.postaction.PostActionValidationException
import com.bts.workflow.web.ErrorBody
import com.bts.workflow.web.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * post-action 관리 API 도메인 예외를 HTTP 응답으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.workflow.postaction` 으로 한정하여 다른 패키지 컨트롤러 예외를 잡지 않는다.
 *
 * 매핑 규칙.
 * - [PostActionValidationException] → 400 Bad Request + WORKFLOW_POST_ACTION_INVALID
 * - [PostActionNotFoundException]   → 404 Not Found  + WORKFLOW_POST_ACTION_NOT_FOUND
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
}

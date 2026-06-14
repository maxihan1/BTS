// post-action 도메인 예외를 HTTP 응답으로 변환하는 @ControllerAdvice

package com.bts.workflow.postaction.web

import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.workflow.postaction.PostActionNotFoundException
import com.bts.workflow.postaction.PostActionValidationException
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
    fun handleAccessDenied(ex: WorkflowSchemeAccessDeniedException): ResponseEntity<PostActionErrorResponse> {
        log.info("POST_ACTION_403 access_denied detail='{}'", ex.message)
        val errorBody = PostActionErrorBody(code = "WORKFLOW_SCHEME_ACCESS_DENIED", message = "이 작업을 수행할 권한이 없습니다.")
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(PostActionErrorResponse(errorBody))
    }

    /**
     * post-action 검증 실패 — 400.
     *
     * @param ex 검증 실패 사유를 담은 예외.
     */
    @ExceptionHandler(PostActionValidationException::class)
    fun handleValidation(ex: PostActionValidationException): ResponseEntity<PostActionErrorResponse> {
        log.info("POST_ACTION_400 reason='{}'", ex.reason)
        val errorBody =
            PostActionErrorBody(code = "WORKFLOW_POST_ACTION_INVALID", message = "post-action 설정이 유효하지 않습니다.")
        return ResponseEntity.badRequest().body(PostActionErrorResponse(errorBody))
    }

    /**
     * post-action 또는 전이 미존재 — 404.
     *
     * @param ex 조회를 시도한 대상 상세 정보를 담은 예외.
     */
    @ExceptionHandler(PostActionNotFoundException::class)
    fun handleNotFound(ex: PostActionNotFoundException): ResponseEntity<PostActionErrorResponse> {
        log.info("POST_ACTION_404 detail='{}'", ex.detail)
        val errorBody =
            PostActionErrorBody(code = "WORKFLOW_POST_ACTION_NOT_FOUND", message = "post-action 또는 전이를 찾을 수 없습니다.")
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(PostActionErrorResponse(errorBody))
    }
}

/**
 * post-action API 에러 응답 래퍼.
 *
 * @property error 에러 상세 정보.
 */
data class PostActionErrorResponse(val error: PostActionErrorBody)

/**
 * post-action API 에러 상세 정보.
 *
 * @property code WORKFLOW_ 접두사로 시작하는 에러 코드.
 * @property message 사람이 읽을 수 있는 에러 설명 (내부 식별자 미포함).
 */
data class PostActionErrorBody(val code: String, val message: String)

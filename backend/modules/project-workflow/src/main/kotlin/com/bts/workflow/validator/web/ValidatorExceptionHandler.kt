// validator 관리 API 도메인 예외를 HTTP 응답으로 변환하는 @ControllerAdvice

package com.bts.workflow.validator.web

import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.workflow.validator.ValidatorNotFoundException
import com.bts.workflow.validator.ValidatorTypeNotEditableException
import com.bts.workflow.validator.ValidatorValidationException
import com.bts.workflow.web.ErrorBody
import com.bts.workflow.web.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * validator 관리 API 도메인 예외를 HTTP 응답으로 변환하는 핸들러.
 *
 * 매핑 규칙.
 * - [WorkflowSchemeAccessDeniedException]     → 403 + `WORKFLOW_SCHEME_ACCESS_DENIED`
 * - [ValidatorValidationException]            → 400 + `WORKFLOW_VALIDATOR_INVALID`
 * - [ValidatorTypeNotEditableException]       → 400 + `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE`
 * - [ValidatorNotFoundException]              → 404 + `WORKFLOW_VALIDATOR_NOT_FOUND`
 *
 * ### 응답 타입은 새로 만들지 않는다
 * 봉투는 `com.bts.workflow.web` 의 [ErrorResponse]/[ErrorBody] 를 그대로 쓴다. 같은
 * `{error:{code,message}}` 를 패키지마다 새로 만들면 그 벌들은 서로를 검사하지 않는다
 * (게이트 1 결정, 2026-08-25). 형제 post-action 핸들러도 자기 사본을 지우고 이쪽으로 왔다.
 *
 * ### `@Order` 를 붙이지 않는다
 * [basePackages] 를 `com.bts.workflow.validator` 로 한정하는 것으로 충분하다.
 * `AmbiguousTransitionExceptionHandler` 가 `@Order(HIGHEST_PRECEDENCE)` 를 쓰는 이유는 그 예외가
 * issue-tracking 컨트롤러에서 표면화되어 그쪽 catch-all 에 삼켜지기 때문이고, 이 경로에는 해당하지
 * 않는다 — 이 advice 의 예외는 전부 validator 컨트롤러에서만 나오고,
 * `com.bts.workflow.web.WorkflowExceptionHandler` 에는 catch-all 자체가 없다. 필요 없는 전역 우선권은
 * 다른 advice 의 매핑을 빼앗아 응답 형식을 조용히 바꾼다
 * (`TransitionConflictExceptionHandler` 가 같은 판단으로 일부러 뺐다).
 */
@RestControllerAdvice(basePackages = ["com.bts.workflow.validator"])
class ValidatorExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 워크플로우 스킴 권한 거부 — 403.
     *
     * 예외 메시지의 actorId/permission/scope 는 **로그에만** 남기고 응답에는 일반 메시지만 싣는다.
     * 그대로 흘리면 응답이 곧 내부 권한 모델의 설명서가 된다
     * (메모리 fr-pm-04-guard-exception-message-http-leak).
     *
     * @param ex 거부된 행위자·권한·범위 정보를 담은 예외.
     * @return 403 Forbidden + `WORKFLOW_SCHEME_ACCESS_DENIED`.
     */
    @ExceptionHandler(WorkflowSchemeAccessDeniedException::class)
    fun handleAccessDenied(ex: WorkflowSchemeAccessDeniedException): ResponseEntity<ErrorResponse> {
        log.info("VALIDATOR_403 access_denied detail='{}'", ex.message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = WorkflowSchemeAccessDeniedException.WORKFLOW_SCHEME_ACCESS_DENIED,
                        message = "이 작업을 수행할 권한이 없습니다.",
                    ),
            ),
        )
    }

    /**
     * validator type·config 검증 실패 — 400.
     *
     * @param ex 검증 실패 사유를 담은 예외.
     * @return 400 Bad Request + `WORKFLOW_VALIDATOR_INVALID`.
     */
    @ExceptionHandler(ValidatorValidationException::class)
    fun handleValidation(ex: ValidatorValidationException): ResponseEntity<ErrorResponse> {
        log.info("VALIDATOR_400 reason='{}'", ex.reason)
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = ValidatorValidationException.ERROR_CODE,
                        message = "validator 설정이 유효하지 않습니다.",
                    ),
            ),
        )
    }

    /**
     * 화면에서 편집할 수 없는 validator type — 400.
     *
     * [ValidatorValidationException] 과 코드를 가르는 이유는 화면 안내가 달라야 하기 때문이다.
     * 한쪽은 설정을 고쳐 다시 내면 되고, 다른 한쪽은 무엇을 고쳐도 이 경로로는 안 된다.
     *
     * @param ex 거절된 type 식별자를 담은 예외.
     * @return 400 Bad Request + `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE`.
     */
    @ExceptionHandler(ValidatorTypeNotEditableException::class)
    fun handleTypeNotEditable(ex: ValidatorTypeNotEditableException): ResponseEntity<ErrorResponse> {
        log.info("VALIDATOR_400 type_not_editable type='{}'", ex.type)
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = ValidatorTypeNotEditableException.ERROR_CODE,
                        message = "이 validator 유형은 화면에서 편집할 수 없습니다.",
                    ),
            ),
        )
    }

    /**
     * validator 또는 전환 미존재 — 404.
     *
     * 「있긴 한데 네 것이 아니다」 도 여기로 묶는다 (IDOR 차단).
     *
     * @param ex 조회를 시도한 대상 상세 정보를 담은 예외.
     * @return 404 Not Found + `WORKFLOW_VALIDATOR_NOT_FOUND`.
     */
    @ExceptionHandler(ValidatorNotFoundException::class)
    fun handleNotFound(ex: ValidatorNotFoundException): ResponseEntity<ErrorResponse> {
        log.info("VALIDATOR_404 detail='{}'", ex.detail)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = ValidatorNotFoundException.ERROR_CODE,
                        message = "validator 또는 전환을 찾을 수 없습니다.",
                    ),
            ),
        )
    }
}

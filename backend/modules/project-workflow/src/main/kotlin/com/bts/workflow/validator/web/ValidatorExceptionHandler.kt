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
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException

/**
 * validator 관리 API 도메인 예외를 HTTP 응답으로 변환하는 핸들러.
 *
 * 매핑 규칙.
 * - [WorkflowSchemeAccessDeniedException]     → 403 + `WORKFLOW_SCHEME_ACCESS_DENIED`
 * - [ValidatorValidationException]            → 400 + `WORKFLOW_VALIDATOR_INVALID`
 * - [ValidatorTypeNotEditableException]       → 400 + `WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE`
 * - [ValidatorNotFoundException]              → 404 + `WORKFLOW_VALIDATOR_NOT_FOUND`
 * - [MethodArgumentTypeMismatchException]     → 400 + `WORKFLOW_INVALID_REQUEST`
 * - [HttpMessageNotReadableException]         → 400 + `WORKFLOW_INVALID_REQUEST`
 * - [ResponseStatusException]                 → 예외가 지정한 상태 + `WORKFLOW_UNAUTHENTICATED` 등
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

    /**
     * 경로 변수 타입 불일치(비-UUID `{id}` 등) — 400.
     *
     * 예외 메시지에는 파라미터 이름·타입·들어온 값이 실려 있다. 그대로 흘리면 응답이 곧 바인딩
     * 규칙의 설명서가 되므로 상세는 로그에만 남기고 응답에는 일반 메시지를 싣는다
     * — 위 [handleAccessDenied] 와 같은 규율이다.
     *
     * @param ex 변환에 실패한 파라미터 정보를 담은 예외.
     * @return 400 Bad Request + `WORKFLOW_INVALID_REQUEST`.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> {
        log.info("VALIDATOR_400 type_mismatch param='{}'", ex.name)
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = TransitionRuleFrameworkErrors.INVALID_REQUEST,
                        message = "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
                    ),
            ),
        )
    }

    /**
     * 요청 본문 파싱 실패(깨진 JSON · 필수 필드 누락) — 400.
     *
     * 로그에도 본문 조각을 남기지 않는다. 깨진 본문에는 사용자가 입력한 값이 그대로 들어 있을 수
     * 있으므로 **원인 예외의 타입 이름만** 남긴다 — 파서 실패인지 매핑 실패인지는 그것으로 갈린다.
     *
     * @param ex 본문을 읽지 못한 원인을 담은 예외.
     * @return 400 Bad Request + `WORKFLOW_INVALID_REQUEST`.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.info("VALIDATOR_400 message_not_readable cause='{}'", ex.mostSpecificCause.javaClass.simpleName)
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = TransitionRuleFrameworkErrors.INVALID_REQUEST,
                        message = "요청 본문을 읽을 수 없습니다.",
                    ),
            ),
        )
    }

    /**
     * [ResponseStatusException] — 미인증(401) 이 대표 경로다.
     *
     * 이 401 은 Spring Security 필터가 아니라 `CurrentActor.current()` 가 **컨트롤러 실행 중**에
     * 던진다(`ManageSchemeGuard.requireManageScheme` → `CurrentActor`). 필터 체인에서 났다면
     * DispatcherServlet 앞이라 이 advice 가 잡을 수 없다.
     *
     * 상태는 **예외가 지정한 것을 그대로** 싣는다. 401 로 못박으면 이 패키지에 다른 상태의
     * [ResponseStatusException] 이 생기는 날 상태가 조용히 401 로 둔갑한다.
     *
     * @param ex 상태 코드와 사유를 지정한 예외.
     * @return 예외가 지정한 상태 + `WORKFLOW_UNAUTHENTICATED`(401) 또는 `WORKFLOW_REQUEST_REJECTED`.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ResponseEntity<ErrorResponse> {
        log.info("VALIDATOR_{} response_status reason='{}'", ex.statusCode.value(), ex.reason)
        val errorBody =
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
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
        return ResponseEntity.status(ex.statusCode).body(ErrorResponse(error = errorBody))
    }
}

/**
 * 전환 규칙 관리 두 표면(validator · post-action)이 공유하는 **프레임워크 층** 에러 코드.
 *
 * 이 셋은 도메인 실패가 아니라 요청이 컨트롤러 본문에 닿기도 전에(또는 인증을 확인하는 자리에서)
 * 깨진 경우다. 표면에 따라 코드가 갈리면 화면이 「validator 인지 post-action 인지」로 한 번 더
 * 분기해야 하므로 **양쪽이 같은 값**을 쓴다. 코드 문자열을 두 핸들러에 각각 적으면 그 순간 두 벌이
 * 되고, 두 벌은 서로를 검사하지 않는다.
 *
 * ### 위치
 * 봉투([ErrorResponse])가 사는 `com.bts.workflow.web` 이 더 중립적인 자리지만, 중요한 것은 패키지가
 * 아니라 **선언이 한 벌**이라는 사실이다. `internal` 이라 모듈 안 어디서든 보이고 형제
 * `PostActionExceptionHandler` 가 이 선언을 그대로 참조한다.
 */
internal object TransitionRuleFrameworkErrors {
    /** 경로 변수·요청 본문이 형식부터 잘못된 경우 — 400. 모듈 기존 명명을 그대로 재사용한다. */
    const val INVALID_REQUEST: String = "WORKFLOW_INVALID_REQUEST"

    /** 인증 주체를 확인할 수 없는 경우 — 401. */
    const val UNAUTHENTICATED: String = "WORKFLOW_UNAUTHENTICATED"

    /** 401 이 아닌 [ResponseStatusException] — 상태는 예외가 지정한 것을 그대로 싣는다. */
    const val REQUEST_REJECTED: String = "WORKFLOW_REQUEST_REJECTED"
}

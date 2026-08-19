// WorkflowExceptionHandler — 도메인 예외를 HTTP 응답 코드로 변환하는 @ControllerAdvice

package com.bts.workflow.web

import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.workflow.cache.WorkflowCacheLockTimeoutException
import com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
import com.bts.workflow.domain.exception.WorkflowInUseException
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.domain.exception.WorkflowKeyConflictException
import com.bts.workflow.domain.exception.WorkflowLockedException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * project-workflow BC 의 도메인 예외를 HTTP 응답으로 변환하는 핸들러.
 *
 * 매핑 규칙:
 * - [WorkflowValidatorFailureException] → 422 Unprocessable Entity (검증 실패)
 * - [WorkflowNotFoundException] → 404 Not Found (워크플로우/전환 부재)
 * - [WorkflowCacheLockTimeoutException] → 503 Service Unavailable (캐시 lock 타임아웃)
 * - [WorkflowExpressionTimeoutException] → 503 Service Unavailable (SpEL 평가 타임아웃)
 * - [AccessDeniedException] → 403 Forbidden (@PreAuthorize 실패)
 *
 * 응답 포맷은 표준 `{ "error": { "code": "...", "message": "..." } }` 를 따른다.
 */
@RestControllerAdvice
class WorkflowExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Validator 검증 실패 — 422.
     *
     * @param ex 거부한 Validator 타입, 필드, 사유를 담은 예외
     */
    @ExceptionHandler(WorkflowValidatorFailureException::class)
    fun handleValidatorFailure(ex: WorkflowValidatorFailureException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_422 validator='{}' field='{}' reason='{}'", ex.validatorType, ex.field, ex.reason)
        return ResponseEntity.unprocessableEntity().body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_VALIDATION_FAILED",
                        message = ex.message ?: "워크플로우 검증에 실패했습니다.",
                    ),
            ),
        )
    }

    /**
     * 워크플로우/전환 부재 — 404.
     *
     * @param ex 조회를 시도한 워크플로우 키를 담은 예외
     */
    @ExceptionHandler(WorkflowNotFoundException::class)
    fun handleNotFound(ex: WorkflowNotFoundException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_404 key='{}'", ex.workflowKey)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_NOT_FOUND",
                        message = ex.message ?: "워크플로우를 찾을 수 없습니다.",
                    ),
            ),
        )
    }

    /**
     * key 중복 — 409.
     *
     * 소프트 삭제된 워크플로우의 key 는 여기 걸리지 않는다. `V206` 이 key 유니크를
     * `WHERE deleted_at IS NULL` 부분 인덱스로 바꿔 지운 key 를 다시 쓸 수 있게 했다.
     */
    @ExceptionHandler(WorkflowKeyConflictException::class)
    fun handleKeyConflict(ex: WorkflowKeyConflictException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_409_KEY key='{}'", ex.workflowKey)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_KEY_CONFLICT",
                        message = "이미 쓰이고 있는 워크플로우 키입니다. 다른 키를 입력해 주세요.",
                    ),
            ),
        )
    }

    /**
     * 사용 중 워크플로우 삭제 — 409.
     *
     * 원인을 뭉뚱그리지 않는다. 사용자가 먼저 할 수 있는 행동(스킴에서 뗀다)을 앞에 둔다
     * (MEMORY `permission-assert-before-existence-makes-403-lie` §처방 2).
     */
    @ExceptionHandler(WorkflowInUseException::class)
    fun handleInUse(ex: WorkflowInUseException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_409_IN_USE key='{}' refs={}", ex.workflowKey, ex.referenceCount)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_IN_USE",
                        message =
                            "워크플로우 스킴 ${ex.referenceCount}곳이 이 워크플로우를 쓰고 있습니다. " +
                                "스킴에서 먼저 뗀 뒤 삭제해 주세요.",
                    ),
            ),
        )
    }

    /** 편집 잠금 — 409. */
    @ExceptionHandler(WorkflowLockedException::class)
    fun handleLocked(ex: WorkflowLockedException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_409_LOCKED key='{}'", ex.workflowKey)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_LOCKED",
                        message = "편집이 잠긴 워크플로우입니다. 발행이 끝난 뒤 다시 시도해 주세요.",
                    ),
            ),
        )
    }

    /**
     * 워크플로우 정의 권한 거부 — 403.
     *
     * 스킴 권한 거부와 **다른 에러 코드**를 쓴다. 같으면 프론트가 「스킴 권한이 없다」와
     * 「워크플로우 편집 권한이 없다」를 같은 문구로 안내한다.
     */
    @ExceptionHandler(WorkflowDefinitionAccessDeniedException::class)
    fun handleDefinitionAccessDenied(ex: WorkflowDefinitionAccessDeniedException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_403_DEFINITION code={}", ex.errorCode)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = ex.errorCode,
                        message = "워크플로우를 편집할 권한이 없습니다. 시스템 관리자에게 요청해 주세요.",
                    ),
            ),
        )
    }

    /**
     * 커맨드 입력 위반(상태 씨앗 부재 등) — 400.
     *
     * ★ `IllegalArgumentException` 을 잡지 **않는다.** 이 advice 는 `@RestControllerAdvice` 에
     * 스코프가 없어 **전역**이라, 그 타입을 잡으면 다른 BC 의 `require()` 실패까지 400 으로
     * 둔갑한다. 500 이어야 할 서버 결함이 400 으로 보이면 장애 대응이 엉뚱한 곳을 판다.
     * 그래서 이 BC 전용 예외만 잡는다.
     */
    @ExceptionHandler(WorkflowInvalidRequestException::class)
    fun handleInvalidRequest(ex: WorkflowInvalidRequestException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_400 key='{}' reason='{}'", ex.workflowKey, ex.reason)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(error = ErrorBody(code = "WORKFLOW_INVALID_REQUEST", message = ex.reason)),
        )
    }

    /**
     * 캐시 lock 타임아웃 또는 SpEL 평가 타임아웃 — 503.
     *
     * @param ex 타임아웃 상세 정보를 담은 예외
     */
    @ExceptionHandler(WorkflowCacheLockTimeoutException::class)
    fun handleCacheLockTimeout(ex: WorkflowCacheLockTimeoutException): ResponseEntity<ErrorResponse> {
        log.warn("WORKFLOW_503 cache lock timeout key='{}' timeoutMs={}", ex.workflowKey, ex.timeoutMillis)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_UNAVAILABLE",
                        message = ex.message ?: "워크플로우 서비스를 일시적으로 사용할 수 없습니다.",
                    ),
            ),
        )
    }

    /**
     * SpEL 표현식 평가 타임아웃 — 503.
     *
     * @param ex 평가에 실패한 표현식 정보를 담은 예외
     */
    @ExceptionHandler(WorkflowExpressionTimeoutException::class)
    fun handleExpressionTimeout(ex: WorkflowExpressionTimeoutException): ResponseEntity<ErrorResponse> {
        log.warn("WORKFLOW_503 expression timeout expression='{}' timeoutMs={}", ex.expression, ex.timeoutMillis)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_UNAVAILABLE",
                        message = "워크플로우 표현식 평가 시간이 초과되었습니다.",
                    ),
            ),
        )
    }

    /**
     * @PreAuthorize 권한 거부 — 403.
     *
     * Spring Security 의 [AccessDeniedException] 은 기본 ExceptionTranslationFilter 가 처리하지만
     * @ControllerAdvice 에서도 명시적으로 처리하여 표준 에러 응답 포맷을 보장한다.
     *
     * @param ex Spring Security 권한 거부 예외
     */
    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_403 access denied: {}", ex.message)
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_PERMISSION_DENIED",
                        message = "이 작업을 수행할 권한이 없습니다.",
                    ),
            ),
        )
    }
}

/**
 * 표준 에러 응답 래퍼.
 *
 * @property error 에러 상세 정보
 */
data class ErrorResponse(val error: ErrorBody)

/**
 * 에러 상세 정보.
 *
 * @property code WORKFLOW_ 접두사로 시작하는 에러 코드
 * @property message 사람이 읽을 수 있는 에러 설명
 */
data class ErrorBody(val code: String, val message: String)

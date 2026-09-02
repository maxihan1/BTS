// BulkOperationExceptionHandler — bulk-operations BC 예외 정의 + RFC 7807 ProblemDetail 변환

package com.bts.issue.bulk.web

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * 일괄 작업이 존재하지 않을 때 발생하는 예외.
 *
 * @property operationId 조회한 작업 UUID.
 */
class BulkOperationNotFoundException(val operationId: UUID) :
    RuntimeException("BulkOperation not found: id=$operationId")

/**
 * 일괄 작업(bulk-operations) 엔드포인트의 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.bulk.web` 으로 한정하여 다른 패키지의 예외를 잡지 않는다.
 *
 * 매핑 규칙.
 * - [IllegalArgumentException] → 400 + ISSUE_VALIDATION_FAILED (issueKeys 검증 실패 등)
 * - [BulkOperationNotFoundException] → 404 + ISSUE_BULK_NOT_FOUND (미존재와 타인 소유를 동일 응답으로)
 * - [MethodArgumentTypeMismatchException] → 400 + ISSUE_BULK_VALIDATION_FAILED (경로변수 UUID 형식 오류)
 * - [ResponseStatusException] → 예외가 지정한 상태 그대로 (미인증 401 이 대표 경로)
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.bulk.web"])
class BulkOperationExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * [MethodArgumentNotValidException] — `@Valid` Bean Validation 실패 — 400.
     *
     * 필수 필드 누락 등 Jakarta Validation 위반 시 Spring MVC 가 발생시킨다.
     * IssueExceptionHandler 와 동일한 ProblemDetail 계약 형태를 사용한다.
     *
     * @param ex 필드별 오류 목록을 포함하는 Spring MVC 예외.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") {
                "${it.field}: ${it.defaultMessage}"
            }
        log.info("ISSUE_BULK_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "bulk-validation-failed",
            title = "Bulk Validation Failed",
            errorCode = BulkErrorCodes.VALIDATION_FAILED,
            detail = fieldErrors.ifBlank { "요청 값 검증에 실패했습니다." },
        )
    }

    /**
     * [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패 — 400.
     *
     * 미허용 enum 값(`operationType:"GARBAGE"`) 또는 JSON 파싱 불가 시 발생한다.
     *
     * @param ex 역직렬화 실패를 나타내는 예외. 원인 메시지만 로그에 기록하고 상세 내용은 응답에 포함하지 않는다.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("ISSUE_BULK_400 message_not_readable cause='{}'", ex.cause?.message ?: ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "bulk-validation-failed",
            title = "Bulk Validation Failed",
            errorCode = BulkErrorCodes.VALIDATION_FAILED,
            detail = "요청 본문을 읽을 수 없습니다. JSON 형식 또는 필드 값을 확인해 주세요.",
        )
    }

    /**
     * [MethodArgumentTypeMismatchException] — 경로변수·쿼리 타입 변환 실패 — 400.
     *
     * `GET /api/v1/bulk-operations/{id}` 의 `id` 가 UUID 가 아닐 때 Spring 이 던진다.
     * 이 예외는 `TypeMismatchException`(→ `BeansException`) 계열이라 [IllegalArgumentException] 도
     * [ResponseStatusException] 도 **아니다** — 전용 핸들러가 없으면 catch-all 이 500 으로 바꾼다.
     *
     * @param ex 변환에 실패한 파라미터 이름을 담은 예외. 값 자체는 응답에 싣지 않는다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("ISSUE_BULK_400 type_mismatch parameter='{}'", ex.name)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "bulk-validation-failed",
            title = "Bulk Validation Failed",
            errorCode = BulkErrorCodes.VALIDATION_FAILED,
            detail = "경로 파라미터 형식이 올바르지 않습니다.",
        )
    }

    /**
     * [IllegalArgumentException] — 서비스 계층 검증 실패 — 400.
     *
     * issueKeys 비어있음, 1000 초과, operationType ↔ payload 불일치 등.
     *
     * @param ex 검증 실패 메시지를 포함하는 예외.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail {
        log.info("ISSUE_BULK_400 validation_failed message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "bulk-validation-failed",
            title = "Bulk Validation Failed",
            errorCode = BulkErrorCodes.VALIDATION_FAILED,
            detail = ex.message,
        )
    }

    // ── 404 BULK_NOT_FOUND ────────────────────────────────────────────────────

    /**
     * [BulkOperationNotFoundException] — 일괄 작업이 존재하지 않음 — 404.
     *
     * @param ex 조회한 operationId 를 포함하는 예외.
     */
    @ExceptionHandler(BulkOperationNotFoundException::class)
    fun handleNotFound(ex: BulkOperationNotFoundException): ProblemDetail {
        log.info("ISSUE_BULK_404 not_found operationId='{}'", ex.operationId)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "bulk-not-found",
            title = "Bulk Operation Not Found",
            errorCode = BulkErrorCodes.BULK_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── ResponseStatusException 상태 전파 (catch-all 변질 차단) ────────────────

    /**
     * [ResponseStatusException] — 컨트롤러/헬퍼가 명시한 HTTP 상태를 그대로 전파한다.
     *
     * [com.bts.issue.adapter.inbound.rest.CurrentActor.current] 가 미인증 시 던지는 401 이
     * catch-all [handleInternalError] 에 가로채여 500 으로 변질되던 문제를 차단한다.
     * `@RestControllerAdvice` 는 Spring 의 `ResponseStatusExceptionResolver` 보다 먼저 실행되므로,
     * [Exception] 보다 구체적인 이 핸들러를 등록해 Spring 이 우선 선택하도록 한다.
     * 형제 `IssueExceptionHandler` 가 FR-PM-06 PR-B B1 에서 같은 처방을 이미 쓰고 있다.
     *
     * 보안 — detail 에 `ex.reason` 등 내부 정보를 노출하지 않고 상태 코드 기반 일반 메시지를 쓴다.
     * 원본 사유는 로그에만 남긴다.
     *
     * @param ex 컨트롤러 계층에서 던진 상태 코드 보유 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        // HttpStatus.valueOf 를 쓰지 않는다 — 표준 목록에 없는 코드(예: 499)면 **핸들러 안에서** 던지고,
        // @ExceptionHandler 안의 예외는 다른 핸들러로 재분배되지 않아 RFC 7807 계약을 통째로 벗어난다.
        val code = ex.statusCode.value()
        val known = HttpStatus.resolve(code)
        log.info("ISSUE_BULK_{} response_status reason='{}'", code, ex.reason)
        val (errorCode, detail) =
            when (code) {
                HttpStatus.UNAUTHORIZED.value() ->
                    BulkErrorCodes.UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN.value() ->
                    BulkErrorCodes.BULK_FORBIDDEN to "이 일괄 작업에 접근할 권한이 없습니다."
                HttpStatus.BAD_REQUEST.value() ->
                    BulkErrorCodes.VALIDATION_FAILED to "요청 파라미터가 올바르지 않습니다."
                // 상태와 에러 코드가 어긋나면 클라이언트가 404 를 「서버 내부 오류」로 렌더한다.
                // 4xx 는 요청 문제, 5xx 만 내부 오류로 가른다.
                else ->
                    if (code < HttpStatus.INTERNAL_SERVER_ERROR.value()) {
                        BulkErrorCodes.VALIDATION_FAILED to "요청을 처리할 수 없습니다."
                    } else {
                        BulkErrorCodes.INTERNAL_ERROR to "서버 내부 오류가 발생했습니다."
                    }
            }
        return problem(
            status = ex.statusCode,
            type = "response-status",
            title = known?.reasonPhrase ?: "Error",
            errorCode = errorCode,
            detail = detail,
        )
    }

    // ── 500 INTERNAL_ERROR (catch-all) ────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * 더 구체적인 핸들러([MethodArgumentNotValidException], [HttpMessageNotReadableException],
     * [IllegalArgumentException], [MethodArgumentTypeMismatchException], [BulkOperationNotFoundException])
     * 가 먼저 처리하고, 여기에 도달한 예외는 내부 오류로 간주한다.
     * 스택트레이스는 서버 로그에만 기록하고 응답에는 포함하지 않는다.
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("ISSUE_BULK_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "bulk-internal-error",
            title = "Internal Server Error",
            errorCode = BulkErrorCodes.INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * Spring 6 의 [ProblemDetail] (RFC 7807) 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp` 를 추가한다.
     *
     * @param status HTTP 응답 상태 코드. [HttpStatus] 상수뿐 아니라 표준 목록에 없는 코드도 받는다.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([BulkErrorCodes]).
     * @param detail 이 특정 발생에 대한 상세 설명. null 이면 생략.
     * @return 완성된 [ProblemDetail] 인스턴스.
     */
    private fun problem(
        status: HttpStatusCode,
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
}

/**
 * 일괄 작업 BC 에러 코드 상수.
 *
 * `ISSUE_` prefix 정책에 따라 bulk 작업 전용 에러 코드를 정의한다.
 */
object BulkErrorCodes {
    const val VALIDATION_FAILED = "ISSUE_BULK_VALIDATION_FAILED"
    const val UNAUTHENTICATED = "ISSUE_BULK_UNAUTHENTICATED"
    const val BULK_FORBIDDEN = "ISSUE_BULK_FORBIDDEN"
    const val BULK_NOT_FOUND = "ISSUE_BULK_NOT_FOUND"
    const val INTERNAL_ERROR = "ISSUE_BULK_INTERNAL_ERROR"
}

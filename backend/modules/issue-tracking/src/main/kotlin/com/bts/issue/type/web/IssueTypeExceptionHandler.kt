// IssueTypeExceptionHandler — IssueType 도메인 예외를 RFC 7807 ProblemDetail 응답으로 변환

package com.bts.issue.type.web

import com.bts.issue.type.domain.IssueTypeInUseException
import com.bts.issue.type.domain.IssueTypeKeyDuplicateException
import com.bts.issue.type.domain.IssueTypeKeyInvalidException
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.domain.IssueTypeReassignTargetInvalidException
import com.bts.issue.type.domain.IssueTypeStandardImmutableException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * IssueType 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.type.web` 로 한정하여 다른 BC 의 예외를 잡지 않는다.
 *
 * 매핑 규칙.
 * - [MethodArgumentNotValidException] → 400 + [IssueTypeErrorCodes.VALIDATION_FAILED]
 * - [IssueTypeNotFoundException] → 404 + [IssueTypeErrorCodes.ISSUE_TYPE_NOT_FOUND]
 * - [IssueTypeStandardImmutableException] → 409 + [IssueTypeErrorCodes.ISSUE_TYPE_STANDARD_IMMUTABLE]
 * - [IssueTypeKeyDuplicateException] → 409 + [IssueTypeErrorCodes.ISSUE_TYPE_KEY_DUPLICATE]
 * - [IssueTypeKeyInvalidException] → 409 + [IssueTypeErrorCodes.ISSUE_TYPE_KEY_INVALID]
 * - [IssueTypeInUseException] → 409 + [IssueTypeErrorCodes.ISSUE_TYPE_IN_USE] (usageCount + schemeMappingCount 별도 노출)
 * - [IssueTypeReassignTargetInvalidException] → 409 + [IssueTypeErrorCodes.ISSUE_TYPE_REASSIGN_TARGET_INVALID]
 * - [Exception] (fallback) → 500 + [IssueTypeErrorCodes.INTERNAL_ERROR]
 */
@RestControllerAdvice(basePackages = ["com.bts.issue.type.web"])
class IssueTypeExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * Bean Validation (`@Valid`) 실패 — 400.
     *
     * @param ex Spring MVC 가 생성한 검증 실패 예외.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.info("ISSUE_TYPE_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "issue-type-validation-failed",
            title = "Validation Failed",
            errorCode = IssueTypeErrorCodes.VALIDATION_FAILED,
            detail = fieldErrors.ifBlank { "요청 값 검증에 실패했습니다." },
        )
    }

    // ── 404 ISSUE_TYPE_NOT_FOUND ──────────────────────────────────────────────

    /**
     * [IssueTypeNotFoundException] — 이슈 타입 미존재 — 404.
     *
     * @param ex 조회한 이슈 타입 id 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueTypeNotFoundException::class)
    fun handleNotFound(ex: IssueTypeNotFoundException): ProblemDetail {
        log.info("ISSUE_TYPE_404 not_found id='{}'", ex.id.value)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "issue-type-not-found",
            title = "Issue Type Not Found",
            errorCode = IssueTypeErrorCodes.ISSUE_TYPE_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 409 ISSUE_TYPE_STANDARD_IMMUTABLE ─────────────────────────────────────

    /**
     * [IssueTypeStandardImmutableException] — 표준 타입 불변 위반 — 409.
     *
     * @param ex 대상 타입 id/key 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueTypeStandardImmutableException::class)
    fun handleStandardImmutable(ex: IssueTypeStandardImmutableException): ProblemDetail {
        log.info("ISSUE_TYPE_409 standard_immutable id='{}' key='{}'", ex.typeId?.value, ex.key?.value)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "issue-type-standard-immutable",
            title = "Issue Type Standard Immutable",
            errorCode = IssueTypeErrorCodes.ISSUE_TYPE_STANDARD_IMMUTABLE,
            detail = ex.message,
        )
    }

    // ── 409 ISSUE_TYPE_KEY_DUPLICATE ──────────────────────────────────────────

    /**
     * [IssueTypeKeyDuplicateException] — 키 중복 — 409.
     *
     * @param ex 중복된 키 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueTypeKeyDuplicateException::class)
    fun handleKeyDuplicate(ex: IssueTypeKeyDuplicateException): ProblemDetail {
        log.info("ISSUE_TYPE_409 key_duplicate key='{}'", ex.key.value)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "issue-type-key-duplicate",
            title = "Issue Type Key Duplicate",
            errorCode = IssueTypeErrorCodes.ISSUE_TYPE_KEY_DUPLICATE,
            detail = ex.message,
        )
    }

    // ── 409 ISSUE_TYPE_KEY_INVALID ────────────────────────────────────────────

    /**
     * [IssueTypeKeyInvalidException] — 키 형식 위반 — 409.
     *
     * @param ex 유효하지 않은 키 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueTypeKeyInvalidException::class)
    fun handleKeyInvalid(ex: IssueTypeKeyInvalidException): ProblemDetail {
        log.info("ISSUE_TYPE_409 key_invalid key='{}'", ex.key.value)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "issue-type-key-invalid",
            title = "Issue Type Key Invalid",
            errorCode = IssueTypeErrorCodes.ISSUE_TYPE_KEY_INVALID,
            detail = ex.message,
        )
    }

    // ── 409 ISSUE_TYPE_IN_USE ─────────────────────────────────────────────────

    /**
     * [IssueTypeInUseException] — 사용 중인 타입 삭제 시도 — 409.
     *
     * `usageCount` / `schemeMappingCount` 를 ProblemDetail 프로퍼티로 별도 노출한다 (C3).
     * `schemeMappingCount > 0` 이면 reassignTo 로도 해소 불가임을 detail 에 명시한다.
     *
     * @param ex 이슈 사용수 + 스킴 매핑수를 포함하는 예외.
     */
    @ExceptionHandler(IssueTypeInUseException::class)
    fun handleInUse(ex: IssueTypeInUseException): ProblemDetail {
        log.info(
            "ISSUE_TYPE_409 in_use usageCount='{}' schemeMappingCount='{}'",
            ex.usageCount,
            ex.schemeMappingCount,
        )
        val detail = buildString {
            append("이슈 타입이 사용 중입니다 (이슈 수: ${ex.usageCount}, 스킴 매핑 수: ${ex.schemeMappingCount}).")
            if (ex.schemeMappingCount > 0) {
                append(" 워크플로우 스킴 매핑이 존재하므로 reassignTo 지정으로는 해소할 수 없습니다. 스킴 매핑을 먼저 제거해 주세요.")
            }
        }
        val pd = problem(
            status = HttpStatus.CONFLICT,
            type = "issue-type-in-use",
            title = "Issue Type In Use",
            errorCode = IssueTypeErrorCodes.ISSUE_TYPE_IN_USE,
            detail = detail,
        )
        pd.setProperty("usageCount", ex.usageCount)
        pd.setProperty("schemeMappingCount", ex.schemeMappingCount)
        return pd
    }

    // ── 409 ISSUE_TYPE_REASSIGN_TARGET_INVALID ────────────────────────────────

    /**
     * [IssueTypeReassignTargetInvalidException] — 재할당 대상 유효하지 않음 — 409.
     *
     * @param ex 재할당 대상 id + 이유를 포함하는 예외.
     */
    @ExceptionHandler(IssueTypeReassignTargetInvalidException::class)
    fun handleReassignTargetInvalid(ex: IssueTypeReassignTargetInvalidException): ProblemDetail {
        log.info("ISSUE_TYPE_409 reassign_target_invalid targetId='{}' reason='{}'", ex.targetId.value, ex.reason)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "issue-type-reassign-target-invalid",
            title = "Issue Type Reassign Target Invalid",
            errorCode = IssueTypeErrorCodes.ISSUE_TYPE_REASSIGN_TARGET_INVALID,
            detail = ex.message,
        )
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("ISSUE_TYPE_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "issue-type-internal-error",
            title = "Internal Server Error",
            errorCode = IssueTypeErrorCodes.INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp` 를 추가한다.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([IssueTypeErrorCodes]).
     * @param detail 이 특정 발생에 대한 상세 설명. null 이면 생략.
     * @return 완성된 [ProblemDetail] 인스턴스.
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
}

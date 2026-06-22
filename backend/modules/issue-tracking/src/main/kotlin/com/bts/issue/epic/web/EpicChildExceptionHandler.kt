// 에픽-자식 연결 컨트롤러 전용 예외 핸들러 — IssueEpicController 스코프 한정

package com.bts.issue.epic.web

import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.epic.domain.EpicChildAlreadyLinkedException
import com.bts.issue.epic.domain.EpicChildCrossProjectException
import com.bts.issue.epic.domain.EpicChildInvalidTypeException
import com.bts.issue.epic.domain.EpicChildNotFoundException
import com.bts.issue.epic.domain.EpicChildSelfReferenceException
import com.bts.issue.epic.domain.EpicTargetNotEpicException
import com.bts.issue.epic.web.EpicChildErrorCodes.ISSUE_EPIC_CHILD_ALREADY_LINKED
import com.bts.issue.epic.web.EpicChildErrorCodes.ISSUE_EPIC_CHILD_CROSS_PROJECT
import com.bts.issue.epic.web.EpicChildErrorCodes.ISSUE_EPIC_CHILD_INVALID_TYPE
import com.bts.issue.epic.web.EpicChildErrorCodes.ISSUE_EPIC_CHILD_SELF_REFERENCE
import com.bts.issue.epic.web.EpicChildErrorCodes.ISSUE_EPIC_OR_CHILD_NOT_FOUND
import com.bts.issue.epic.web.EpicChildErrorCodes.ISSUE_EPIC_TARGET_NOT_EPIC
import com.bts.issue.epic.web.EpicChildErrorCodes.ISSUE_EPIC_VALIDATION_FAILED
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 에픽-자식 연결 컨트롤러 예외 → HTTP 응답 변환 핸들러.
 *
 * [IssueEpicController] 에만 스코프를 한정한다 (`assignableTypes`).
 * [com.bts.issue.adapter.inbound.rest.IssueExceptionHandler] 의 basePackages 가
 * `com.bts.issue.adapter.inbound.rest` 로 고정되어 있어 `com.bts.issue.epic.web` 패키지를
 * 커버하지 않는다. 따라서 이 핸들러에서 공통 예외를 직접 처리한다.
 *
 * ## 핸들러 순서 (B1 — catch-all 401 삼킴 방지)
 * 1. [ResponseStatusException] — CurrentActor 401 등 프레임워크 예외 상태 코드 전파.
 * 2. [MethodArgumentTypeMismatchException] / [HttpMessageNotReadableException]
 *    / [MethodArgumentNotValidException] — 400.
 * 3. 도메인 예외 6종 (404/422/409).
 * 4. [IssueAccessDeniedException] — 403.
 * — catch-all 핸들러 없음 (스코프 한정이므로 불필요, 타 경로 예외 삼킴 방지).
 *
 * ## 보안
 * 응답 detail 에 내부 정보(존재 여부 / UUID 등)를 노출하지 않는다.
 */
@RestControllerAdvice(assignableTypes = [IssueEpicController::class])
class EpicChildExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── B1. ResponseStatusException (401·CurrentActor catch-all 변질 차단) ───

    /**
     * [ResponseStatusException] — 컨트롤러/헬퍼가 명시한 HTTP 상태를 그대로 전파한다.
     *
     * [com.bts.issue.adapter.inbound.rest.CurrentActor.current] 가 미인증 시 던지는 401
     * [ResponseStatusException] 이 도메인 예외 핸들러에 삼켜지는 것을 차단한다.
     *
     * @param ex 상태 코드 보유 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("EPIC_{} response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED -> "UNAUTHENTICATED" to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN -> "ISSUE_ACCESS_DENIED" to "이 작업을 수행할 권한이 없습니다."
                else -> "ISSUE_EPIC_INTERNAL_ERROR" to "요청을 처리할 수 없습니다."
            }
        return problem(
            status = status,
            type = "response-status",
            title = status.reasonPhrase,
            errorCode = errorCode,
            detail = detail,
        )
    }

    // ── 400 — 클라이언트 입력 오류 (경로 타입 불일치 / JSON 역직렬화 실패 / Bean Validation) ───

    /**
     * 클라이언트 입력 오류 400 통합 핸들러.
     *
     * - [MethodArgumentTypeMismatchException] — 경로 변수 타입 불일치.
     * - [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패.
     * - [MethodArgumentNotValidException] — Bean Validation(@field:NotBlank 등) 실패.
     *
     * 세 예외 모두 클라이언트 입력 오류이므로 동일한 400 응답을 반환한다.
     * 예외 종류는 로그에만 기록하며 응답 detail 에는 일반 메시지를 사용한다.
     *
     * @param ex 클라이언트 입력 오류 예외.
     */
    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class,
    )
    fun handleClientInputError(ex: Exception): ProblemDetail {
        log.info("EPIC_400 client_input_error type='{}' message='{}'", ex.javaClass.simpleName, ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "epic-validation-failed",
            title = "Bad Request",
            errorCode = ISSUE_EPIC_VALIDATION_FAILED,
            detail = "요청 입력이 유효하지 않습니다. 경로, 파라미터 또는 요청 본문을 확인해 주세요.",
        )
    }

    // ── 404 ISSUE_EPIC_OR_CHILD_NOT_FOUND ─────────────────────────────────────

    /**
     * 에픽 또는 자식 이슈 미존재·소프트 삭제 — 404.
     *
     * 보안 N1 — 미존재와 접근 불가를 동일하게 처리해 존재 여부를 노출하지 않는다.
     *
     * @param ex 도메인 예외.
     */
    @ExceptionHandler(EpicChildNotFoundException::class)
    fun handleEpicChildNotFound(ex: EpicChildNotFoundException): ProblemDetail {
        log.info("EPIC_404 not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "epic-or-child-not-found",
            title = "Not Found",
            errorCode = ISSUE_EPIC_OR_CHILD_NOT_FOUND,
            detail = "이슈를 찾을 수 없습니다.",
        )
    }

    // ── 422 — 불변식 위반 ─────────────────────────────────────────────────────

    /**
     * child 이슈 유형(hierarchyLevel)이 0 이 아닐 때 — 422.
     *
     * @param ex 도메인 예외.
     */
    @ExceptionHandler(EpicChildInvalidTypeException::class)
    fun handleEpicChildInvalidType(ex: EpicChildInvalidTypeException): ProblemDetail {
        log.info("EPIC_422 invalid_child_type message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "epic-child-invalid-type",
            title = "Unprocessable Entity",
            errorCode = ISSUE_EPIC_CHILD_INVALID_TYPE,
            detail = "서브태스크는 에픽에 직접 연결할 수 없습니다.",
        )
    }

    /**
     * epic 자리에 지정한 이슈가 에픽 유형이 아닐 때 — 422.
     *
     * @param ex 도메인 예외.
     */
    @ExceptionHandler(EpicTargetNotEpicException::class)
    fun handleEpicTargetNotEpic(ex: EpicTargetNotEpicException): ProblemDetail {
        log.info("EPIC_422 target_not_epic message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "epic-target-not-epic",
            title = "Unprocessable Entity",
            errorCode = ISSUE_EPIC_TARGET_NOT_EPIC,
            detail = "지정한 이슈가 에픽 유형이 아닙니다.",
        )
    }

    /**
     * child 와 epic 이 다른 프로젝트에 속할 때 — 422.
     *
     * @param ex 도메인 예외.
     */
    @ExceptionHandler(EpicChildCrossProjectException::class)
    fun handleEpicChildCrossProject(ex: EpicChildCrossProjectException): ProblemDetail {
        log.info("EPIC_422 cross_project message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "epic-child-cross-project",
            title = "Unprocessable Entity",
            errorCode = ISSUE_EPIC_CHILD_CROSS_PROJECT,
            detail = "에픽과 자식 이슈는 같은 프로젝트에 속해야 합니다.",
        )
    }

    /**
     * 이슈가 자기 자신의 에픽 자식으로 지정될 때 — 422.
     *
     * @param ex 도메인 예외.
     */
    @ExceptionHandler(EpicChildSelfReferenceException::class)
    fun handleEpicChildSelfReference(ex: EpicChildSelfReferenceException): ProblemDetail {
        log.info("EPIC_422 self_reference message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "epic-child-self-reference",
            title = "Unprocessable Entity",
            errorCode = ISSUE_EPIC_CHILD_SELF_REFERENCE,
            detail = "이슈는 자기 자신의 에픽이 될 수 없습니다.",
        )
    }

    // ── 409 ISSUE_EPIC_CHILD_ALREADY_LINKED ───────────────────────────────────

    /**
     * child 이슈가 이미 에픽에 연결되어 있을 때 — 409.
     *
     * @param ex 도메인 예외.
     */
    @ExceptionHandler(EpicChildAlreadyLinkedException::class)
    fun handleEpicChildAlreadyLinked(ex: EpicChildAlreadyLinkedException): ProblemDetail {
        log.info("EPIC_409 already_linked message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "epic-child-already-linked",
            title = "Conflict",
            errorCode = ISSUE_EPIC_CHILD_ALREADY_LINKED,
            detail = "이슈가 이미 에픽에 연결되어 있습니다. 먼저 기존 연결을 해제해 주세요.",
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * 권한 없음 — 403.
     *
     * 응답 detail 에 행위자 UUID, 권한명, 범위 등 내부 정보를 노출하지 않는다.
     *
     * @param ex 행위자/권한/범위 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueAccessDeniedException::class)
    fun handleAccessDenied(ex: IssueAccessDeniedException): ProblemDetail {
        log.info("EPIC_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "access-denied",
            title = "Forbidden",
            errorCode = "ISSUE_ACCESS_DENIED",
            detail = "이 작업을 수행할 권한이 없습니다.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * [ProblemDetail] (RFC 7807) 인스턴스를 생성하는 헬퍼.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type type suffix.
     * @param title 문제 유형 요약.
     * @param errorCode BTS 에러 코드.
     * @param detail 상세 설명.
     */
    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        errorCode: String,
        detail: String,
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/$type")
        pd.title = title
        pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }
}

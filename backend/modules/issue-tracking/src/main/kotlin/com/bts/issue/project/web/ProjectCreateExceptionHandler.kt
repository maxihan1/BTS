// 프로젝트 생성 예외 → RFC 7807 ProblemDetail 변환 핸들러 + 403 예외/에러코드 (FR-PJ-01 Task 7)

package com.bts.issue.project.web

import com.bts.issue.project.domain.ProjectKeyAlreadyExistsException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * CREATE_PROJECT 전역권한이 없는 actor 가 프로젝트 생성을 시도할 때 — 403.
 *
 * message 에는 로그 상관용 actorId 를 담지만 HTTP 응답 detail 에는 노출하지 않는다
 * ([ProjectCreateExceptionHandler.handleForbidden] 가 일반 메시지로 치환 — 메모리
 * guard-exception-message-http-leak). 이 예외는 web 레이어의 게이트 결과이므로 web 패키지에 둔다.
 *
 * @param actorId 권한이 거부된 행위자 UUID (로그 전용).
 */
class ProjectCreateForbiddenException(actorId: UUID) :
    RuntimeException("Actor $actorId lacks CREATE_PROJECT global permission")

/**
 * 프로젝트 생성 에러 코드 상수.
 *
 * 모든 코드는 `ISSUE_` 접두사를 사용한다 (BTS 에러 코드 규칙 §1.16).
 */
internal object ProjectCreateErrorCodes {
    /** Bean Validation 실패(key 정규식/blank) — 400. */
    const val VALIDATION_FAILED = "ISSUE_PROJECT_VALIDATION_FAILED"

    /** CREATE_PROJECT 권한 없음 — 403. noun-first(ISSUE_PROJECT_*) 로 형제 코드와 정합. */
    const val FORBIDDEN = "ISSUE_PROJECT_FORBIDDEN"

    /** key 중복 — 409. */
    const val KEY_ALREADY_EXISTS = "ISSUE_PROJECT_KEY_ALREADY_EXISTS"
}

/**
 * 프로젝트 생성 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * `assignableTypes = [ProjectCreateController::class]` 로 스코프를 좁혀 다른 컨트롤러의 예외를 잡지 않는다.
 * `@Order(Ordered.HIGHEST_PRECEDENCE)` — 같은 패키지 [ProjectLeadExceptionHandler] 의 catch-all
 * `Exception::class` fallback 이 이 컨트롤러의 401/403/409 를 500 으로 삼키는 것을 방지한다
 * ([ProjectRequire2faExceptionHandler] 선례, 메모리
 * catch-all-exceptionhandler-swallows-responsestatusexception).
 *
 * ## 매핑 규칙
 * - [ResponseStatusException] → 원 상태 코드 (미인증 401 등) 그대로 통과
 * - [MethodArgumentNotValidException] → 400 + [ProjectCreateErrorCodes.VALIDATION_FAILED] (Bean Validation)
 * - [HttpMessageNotReadableException] → 400 + [ProjectCreateErrorCodes.VALIDATION_FAILED] (필드 누락·malformed JSON)
 * - [ProjectCreateForbiddenException] → 403 + [ProjectCreateErrorCodes.FORBIDDEN] (내부구조 미노출)
 * - [ProjectKeyAlreadyExistsException] → 409 + [ProjectCreateErrorCodes.KEY_ALREADY_EXISTS]
 */
@RestControllerAdvice(assignableTypes = [ProjectCreateController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ProjectCreateExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── ResponseStatusException 재전파 — 401 프레임워크 예외 삼킴 방지 ─────────────

    /**
     * [ResponseStatusException] — 프레임워크 상태 코드 예외를 그대로 전달한다.
     *
     * [CurrentActor.current] 가 미인증 시 던지는 401 이 catch-all fallback 에 잡혀 500 으로
     * 변질되는 것을 막는다 (메모리 catch-all-exceptionhandler-swallows-responsestatusexception).
     *
     * @param ex 프레임워크가 생성한 ResponseStatusException.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(ex: ResponseStatusException): ProblemDetail {
        log.debug("PROJECT_CREATE ResponseStatusException status={} message='{}'", ex.statusCode, ex.message)
        val pd = ProblemDetail.forStatus(ex.statusCode.value())
        pd.detail = ex.reason
        return pd
    }

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * Bean Validation (`@Valid`) 실패(key 정규식/blank) — 400.
     *
     * detail 은 필드별 원인을 노출하지 않고 일반 메시지만 반환한다.
     *
     * @param ex Spring MVC 가 생성한 검증 실패 예외.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.info("PROJECT_CREATE_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "project-create-validation-failed",
            title = "Validation Failed",
            errorCode = ProjectCreateErrorCodes.VALIDATION_FAILED,
            detail = "프로젝트 생성 요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * [HttpMessageNotReadableException] — 요청 본문 역직렬화 실패 — 400.
     *
     * `CreateProjectRequest.key`/`name` 이 Kotlin non-null 이라 **필드 누락·null·malformed JSON** 은
     * Bean Validation([MethodArgumentNotValidException]) 이전에 Jackson 역직렬화 단계에서
     * [HttpMessageNotReadableException] 으로 실패한다(흔한 나쁜 입력 경로). 이 핸들러가 없으면 같은
     * 패키지 [ProjectLeadExceptionHandler]([basePackages] 스코프)가 잡아 이 엔드포인트가 `project-lead-*`
     * 에러 코드를 반환하게 된다 — 400 계약을 이 컨트롤러가 온전히 소유하도록 여기서 처리한다.
     *
     * @param ex 본문 역직렬화 실패 예외 (원인은 로그 전용).
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("PROJECT_CREATE_400 message_not_readable message='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "project-create-validation-failed",
            title = "Validation Failed",
            errorCode = ProjectCreateErrorCodes.VALIDATION_FAILED,
            detail = "프로젝트 생성 요청 본문을 읽을 수 없습니다.",
        )
    }

    // ── 403 FORBIDDEN ─────────────────────────────────────────────────────────

    /**
     * [ProjectCreateForbiddenException] — CREATE_PROJECT 권한 없음 — 403.
     *
     * HTTP 응답 detail 에 actorId 등 내부 상세를 노출하지 않는다
     * (메모리 guard-exception-message-http-leak).
     *
     * @param ex 권한 거부 예외 (message 에 actorId 포함 — 로그 전용).
     */
    @ExceptionHandler(ProjectCreateForbiddenException::class)
    fun handleForbidden(ex: ProjectCreateForbiddenException): ProblemDetail {
        log.warn("PROJECT_CREATE_403 forbidden message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "project-create-forbidden",
            title = "Forbidden",
            errorCode = ProjectCreateErrorCodes.FORBIDDEN,
            detail = "프로젝트를 생성할 권한이 없습니다.",
        )
    }

    // ── 409 KEY_ALREADY_EXISTS ────────────────────────────────────────────────

    /**
     * [ProjectKeyAlreadyExistsException] — key 중복 — 409.
     *
     * detail 은 일반 메시지만 반환한다(구체 key 는 로그 전용).
     *
     * @param ex 중복 key 정보를 포함하는 예외.
     */
    @ExceptionHandler(ProjectKeyAlreadyExistsException::class)
    fun handleKeyAlreadyExists(ex: ProjectKeyAlreadyExistsException): ProblemDetail {
        log.info("PROJECT_CREATE_409 key_already_exists message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "project-create-key-already-exists",
            title = "Conflict",
            errorCode = ProjectCreateErrorCodes.KEY_ALREADY_EXISTS,
            detail = "이미 사용 중인 프로젝트 key 입니다.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type URI type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([ProjectCreateErrorCodes]).
     * @param detail 이 특정 발생에 대한 상세 설명(내부구조 미노출 일반 메시지).
     * @return 완성된 [ProblemDetail] 인스턴스.
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

// IssueExceptionHandler — issue-tracking BC 도메인 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.domain.AssigneeNotFoundException
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueComponentNotFoundException
import com.bts.issue.domain.IssueKeyPrefixReservedException
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueSecurityLevelNotInSchemeException
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.resolution.domain.ResolutionNotFoundException
import com.bts.issue.type.domain.IssueTypeNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * issue-tracking BC 의 모든 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.adapter.inbound.rest` 로 한정하여 다른 BC 의 예외를 잡지 않는다.
 *
 * 매핑 규칙 (spec §6.1, 10건).
 * - [MethodArgumentNotValidException] → 400 + [IssueErrorCodes.VALIDATION_FAILED]
 * - [AuthenticationException] → 401 + [IssueErrorCodes.UNAUTHENTICATED]
 * - [IssueAccessDeniedException] → 403 + [IssueErrorCodes.ACCESS_DENIED]
 * - [IssueNotFoundException] → 404 + [IssueErrorCodes.ISSUE_NOT_FOUND]
 * - [IssueProjectNotFoundException] → 404 + [IssueErrorCodes.PROJECT_NOT_FOUND]
 * - [ResolutionNotFoundException] → 404 + [IssueErrorCodes.RESOLUTION_NOT_FOUND]
 * - [IssueKeyPrefixReservedException] → 409 + [IssueErrorCodes.KEY_PREFIX_RESERVED]
 * - [IssueVersionConflictException] → 409 + [IssueErrorCodes.VERSION_CONFLICT]
 * - [IssueTransitionNotAllowedException] → 409 + [IssueErrorCodes.TRANSITION_NOT_ALLOWED]
 * - [IssueWorkflowNotConfiguredException] → 422 + [IssueErrorCodes.WORKFLOW_NOT_CONFIGURED]
 * - [AssigneeNotFoundException] → 422 + [IssueErrorCodes.ASSIGNEE_NOT_FOUND]
 * - [IssueComponentNotFoundException] → 422 + [IssueErrorCodes.COMPONENT_NOT_FOUND]
 * - [Exception] (fallback) → 500 + [IssueErrorCodes.INTERNAL_ERROR]
 *
 * TooManyFunctions: 도메인 예외 종류(400/401/403/404/409/422/500) 각각에 @ExceptionHandler 가 필요하므로
 * 함수 수가 임계치(11)를 넘는다. RestControllerAdvice 의 책임(예외→HTTP 변환)은 분리 불가한 단일 관심사라
 * 클래스 단위로 억제한다. FR-IS-02 D6 에서 [IssueTypeNotFoundException] 핸들러가 추가됐다
 * (type.web 패키지 한정 핸들러가 못 잡는 예외를 rest 패키지에서 404 로 매핑).
 * FR-IS-03 Task 5 에서 [AssigneeNotFoundException] 핸들러가 추가됐다 (422 + ASSIGNEE_NOT_FOUND).
 * FR-IS-07 Task B6 에서 [ResolutionNotFoundException] 핸들러가 추가됐다 (404 + RESOLUTION_NOT_FOUND).
 * FR-CM-02 Task 5 에서 [IssueComponentNotFoundException] 핸들러가 추가됐다 (422 + COMPONENT_NOT_FOUND).
 */
@Suppress("TooManyFunctions")
@RestControllerAdvice(basePackages = ["com.bts.issue.adapter.inbound.rest"])
class IssueExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * Bean Validation (`@Valid`) 실패 — 400.
     *
     * @param ex Spring MVC 가 생성한 검증 실패 예외. 필드별 오류 목록을 포함한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") {
                "${it.field}: ${it.defaultMessage}"
            }
        log.info("ISSUE_400 validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "validation-failed",
            title = "Validation Failed",
            errorCode = IssueErrorCodes.VALIDATION_FAILED,
            detail = fieldErrors.ifBlank { "요청 값 검증에 실패했습니다." },
        )
    }

    // ── 401 UNAUTHENTICATED ───────────────────────────────────────────────────

    /**
     * 세션 만료 또는 미인증 — 401.
     *
     * @param ex Spring Security 인증 실패 예외.
     */
    @ExceptionHandler(AuthenticationException::class)
    fun handleUnauthenticated(ex: AuthenticationException): ProblemDetail {
        log.info("ISSUE_401 unauthenticated message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNAUTHORIZED,
            type = "unauthenticated",
            title = "Unauthenticated",
            errorCode = IssueErrorCodes.UNAUTHENTICATED,
            detail = "인증이 필요합니다. 세션이 만료되었을 수 있습니다.",
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * [IssueAccessDeniedException] — 이슈 또는 프로젝트에 대한 권한 없음 — 403.
     *
     * @param ex 행위자, 권한, 범위 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueAccessDeniedException::class)
    fun handleAccessDenied(ex: IssueAccessDeniedException): ProblemDetail {
        log.info("ISSUE_403 access_denied message='{}'", ex.message)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "access-denied",
            title = "Access Denied",
            errorCode = IssueErrorCodes.ACCESS_DENIED,
            detail = "이 작업을 수행할 권한이 없습니다.",
        )
    }

    // ── 404 ISSUE_NOT_FOUND ───────────────────────────────────────────────────

    /**
     * [IssueNotFoundException] — 이슈가 존재하지 않거나 소프트 삭제됨 — 404.
     *
     * @param ex 조회한 이슈 키 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueNotFoundException::class)
    fun handleIssueNotFound(ex: IssueNotFoundException): ProblemDetail {
        log.info("ISSUE_404 issue_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "issue-not-found",
            title = "Issue Not Found",
            errorCode = IssueErrorCodes.ISSUE_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 404 ISSUE_TYPE_NOT_FOUND ──────────────────────────────────────────────

    /**
     * [IssueTypeNotFoundException] — 이슈 타입이 존재하지 않거나 비활성 상태 — 404.
     *
     * [com.bts.issue.type.web.IssueTypeExceptionHandler] 는 `com.bts.issue.type.web` 패키지에만
     * 한정되어 있어 이 핸들러가 없으면 500 fallback 으로 떨어진다. PATCH /issues/{key} 에서
     * typeId 변경 요청 시 service 가 throw 한 예외를 이 핸들러가 잡아 404 로 응답한다.
     *
     * @param ex 존재하지 않는 IssueTypeId 를 포함하는 예외.
     */
    @ExceptionHandler(IssueTypeNotFoundException::class)
    fun handleIssueTypeNotFound(ex: IssueTypeNotFoundException): ProblemDetail {
        log.info("ISSUE_404 issue_type_not_found id='{}'", ex.id.value)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "issue-type-not-found",
            title = "Issue Type Not Found",
            errorCode = IssueErrorCodes.ISSUE_TYPE_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 404 PROJECT_NOT_FOUND ─────────────────────────────────────────────────

    /**
     * [IssueProjectNotFoundException] — 프로젝트 키가 존재하지 않음 — 404.
     *
     * @param ex 조회한 프로젝트 키 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueProjectNotFoundException::class)
    fun handleProjectNotFound(ex: IssueProjectNotFoundException): ProblemDetail {
        log.info("ISSUE_404 project_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "project-not-found",
            title = "Project Not Found",
            errorCode = IssueErrorCodes.PROJECT_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 409 KEY_PREFIX_RESERVED ───────────────────────────────────────────────

    /**
     * [IssueKeyPrefixReservedException] — 예약어 prefix 사용 시도 — 409.
     *
     * @param ex 시도한 예약 prefix 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueKeyPrefixReservedException::class)
    fun handleKeyPrefixReserved(ex: IssueKeyPrefixReservedException): ProblemDetail {
        log.info("ISSUE_409 key_prefix_reserved message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "key-prefix-reserved",
            title = "Key Prefix Reserved",
            errorCode = IssueErrorCodes.KEY_PREFIX_RESERVED,
            detail = ex.message,
        )
    }

    // ── 409 VERSION_CONFLICT ──────────────────────────────────────────────────

    /**
     * [IssueVersionConflictException] — 낙관적 잠금 충돌 — 409.
     *
     * @param ex 충돌 이슈 키와 현재 버전 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueVersionConflictException::class)
    fun handleVersionConflict(ex: IssueVersionConflictException): ProblemDetail {
        log.info("ISSUE_409 version_conflict message='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "version-conflict",
            title = "Version Conflict",
            errorCode = IssueErrorCodes.VERSION_CONFLICT,
            detail = ex.message,
        )
    }

    // ── 409 TRANSITION_NOT_ALLOWED ────────────────────────────────────────────

    /**
     * [IssueTransitionNotAllowedException] — 워크플로우 전이가 허용되지 않음 — 409.
     *
     * issue-tracking BC 의 `transitionIssue` 가 `WorkflowTransitionPort.plan()` 호출 후
     * [IssueTransitionNotAllowedException] 으로 감싸서 throw 한다.
     * project-workflow BC 예외가 어댑터 계층까지 누출되지 않는다.
     *
     * @param ex 이슈 키, 출발 상태, 도착 상태를 포함하는 예외.
     */
    @ExceptionHandler(IssueTransitionNotAllowedException::class)
    fun handleTransitionNotAllowed(ex: IssueTransitionNotAllowedException): ProblemDetail {
        log.info(
            "ISSUE_409 transition_not_allowed key='{}' from='{}' to='{}'",
            ex.issueKey.value,
            ex.fromStatus,
            ex.toStatus,
        )
        return problem(
            status = HttpStatus.CONFLICT,
            type = "transition-not-allowed",
            title = "Transition Not Allowed",
            errorCode = IssueErrorCodes.TRANSITION_NOT_ALLOWED,
            detail = ex.message,
        )
    }

    // ── 422 WORKFLOW_NOT_CONFIGURED ──────────────────────────────────────────

    /**
     * [IssueWorkflowNotConfiguredException] — 프로젝트에 기본 워크플로우 스킴이 없음 — 422.
     *
     * project-workflow BC 의 WorkflowSchemeNoDefaultException 을 issue-tracking BC 경계 내부에서
     * [IssueWorkflowNotConfiguredException] 으로 변환하여 도달한다.
     * 클라이언트에게 프로젝트 워크플로우 설정이 필요함을 알린다 (처리 불가 엔티티).
     *
     * @param ex 미설정 프로젝트 키와 이슈 타입 키 정보를 포함하는 예외.
     */
    @ExceptionHandler(IssueWorkflowNotConfiguredException::class)
    fun handleWorkflowNotConfigured(ex: IssueWorkflowNotConfiguredException): ProblemDetail {
        log.info("ISSUE_422 workflow_not_configured message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "workflow-not-configured",
            title = "Workflow Not Configured",
            errorCode = IssueErrorCodes.WORKFLOW_NOT_CONFIGURED,
            detail = ex.message,
        )
    }

    // ── 422 ASSIGNEE_NOT_FOUND ────────────────────────────────────────────────

    /**
     * [AssigneeNotFoundException] — assignee 로 지정한 사용자가 존재하지 않음 — 422.
     *
     * @param ex 존재하지 않는 assignee 의 사용자 ID 를 포함하는 예외.
     */
    @ExceptionHandler(AssigneeNotFoundException::class)
    fun handleAssigneeNotFound(ex: AssigneeNotFoundException): ProblemDetail {
        log.info("ISSUE_422 assignee_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "assignee-not-found",
            title = "Assignee Not Found",
            errorCode = IssueErrorCodes.ASSIGNEE_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 422 COMPONENT_NOT_FOUND ───────────────────────────────────────────────

    /**
     * [IssueComponentNotFoundException] — changeComponents 에서 비활성 또는 타 프로젝트 컴포넌트 지정 시 — 422.
     *
     * @param ex 존재하지 않는 컴포넌트 UUID 를 포함하는 예외.
     */
    @ExceptionHandler(IssueComponentNotFoundException::class)
    fun handleComponentNotFound(ex: IssueComponentNotFoundException): ProblemDetail {
        log.info("ISSUE_422 component_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "component-not-found",
            title = "Component Not Found",
            errorCode = IssueErrorCodes.COMPONENT_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 422 SECURITY_LEVEL_NOT_IN_SCHEME ──────────────────────────────────────

    /**
     * [IssueSecurityLevelNotInSchemeException] — 지정 보안 등급이 프로젝트 적용 스킴 미소속 — 422 (FR-PM-06).
     *
     * 보안 — detail 에 내부 식별자(levelId)를 노출하지 않는다(guard-exception 누출 방지).
     * levelId 는 로그에만 기록한다.
     *
     * @param ex 스킴 미소속 보안 등급 UUID 를 포함하는 예외.
     */
    @ExceptionHandler(IssueSecurityLevelNotInSchemeException::class)
    fun handleSecurityLevelNotInScheme(ex: IssueSecurityLevelNotInSchemeException): ProblemDetail {
        log.info("ISSUE_422 security_level_not_in_scheme levelId='{}'", ex.levelId)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "security-level-not-in-scheme",
            title = "Security Level Not In Scheme",
            errorCode = IssueErrorCodes.SECURITY_LEVEL_NOT_IN_SCHEME,
            detail = "지정한 보안 등급이 이 프로젝트의 적용 스킴에 속하지 않습니다.",
        )
    }

    // ── 404 RESOLUTION_NOT_FOUND ──────────────────────────────────────────────

    /**
     * [ResolutionNotFoundException] — 전이 요청에 포함된 resolutionId 가 존재하지 않음 — 404.
     *
     * [com.bts.issue.application.IssueApplicationService.transitionIssue] 에서
     * resolutionId 존재성 검증 실패 시 발생한다. 영속(applyTransition) 이전에 검증한다.
     *
     * @param ex 존재하지 않는 Resolution UUID 를 포함하는 예외.
     */
    @ExceptionHandler(ResolutionNotFoundException::class)
    fun handleResolutionNotFound(ex: ResolutionNotFoundException): ProblemDetail {
        log.info("ISSUE_404 resolution_not_found message='{}'", ex.message)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "resolution-not-found",
            title = "Resolution Not Found",
            errorCode = IssueErrorCodes.RESOLUTION_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * pgmq 발행 실패 등 인프라 오류가 롤백 후 이 핸들러로 도달한다.
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("ISSUE_500 internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "internal-error",
            title = "Internal Server Error",
            errorCode = IssueErrorCodes.INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * Spring 6 의 [ProblemDetail] (RFC 7807) 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp` 를 추가한다.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([IssueErrorCodes]).
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

/**
 * issue-tracking BC 에러 코드 상수.
 *
 * spec §6.1 의 10건 errorCode 를 한 곳에서 관리한다.
 * 모든 에러 코드는 `ISSUE_` 접두사 없이 정의되어 있으며,
 * 로그/응답에서는 그대로 사용한다.
 */
object IssueErrorCodes {
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
    const val UNAUTHENTICATED = "UNAUTHENTICATED"
    const val ACCESS_DENIED = "ACCESS_DENIED"
    const val ISSUE_NOT_FOUND = "ISSUE_NOT_FOUND"
    const val ISSUE_TYPE_NOT_FOUND = "ISSUE_TYPE_NOT_FOUND"
    const val PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND"
    const val KEY_PREFIX_RESERVED = "KEY_PREFIX_RESERVED"
    const val VERSION_CONFLICT = "VERSION_CONFLICT"
    const val TRANSITION_NOT_ALLOWED = "TRANSITION_NOT_ALLOWED"
    const val WORKFLOW_NOT_CONFIGURED = "WORKFLOW_NOT_CONFIGURED"
    const val ASSIGNEE_NOT_FOUND = "ASSIGNEE_NOT_FOUND"
    const val COMPONENT_NOT_FOUND = "COMPONENT_NOT_FOUND"
    const val SECURITY_LEVEL_NOT_IN_SCHEME = "SECURITY_LEVEL_NOT_IN_SCHEME"
    const val RESOLUTION_NOT_FOUND = "RESOLUTION_NOT_FOUND"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

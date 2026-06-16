// IssueExceptionHandler — issue-tracking BC 도메인 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.customfield.domain.CustomFieldValidationException
import com.bts.issue.domain.AssigneeNotFoundException
import com.bts.issue.domain.InvalidTargetMappingException
import com.bts.issue.domain.InvalidTargetStateException
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueComponentNotFoundException
import com.bts.issue.domain.IssueHasSubtasksException
import com.bts.issue.domain.IssueKeyPrefixReservedException
import com.bts.issue.domain.IssueLinkedVersionNotFoundException
import com.bts.issue.domain.IssueMovedException
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueSecurityLevelNotInSchemeException
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.domain.MoveSameProjectException
import com.bts.issue.domain.RequiredFieldMissingException
import com.bts.issue.resolution.domain.ResolutionNotFoundException
import com.bts.issue.type.domain.IssueTypeNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * issue-tracking BC 의 모든 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.issue.adapter.inbound.rest` 로 한정하여 다른 BC 의 예외를 잡지 않는다.
 *
 * 매핑 규칙 (spec §6.1).
 * - [MethodArgumentNotValidException] → 400 + [IssueErrorCodes.VALIDATION_FAILED]
 * - [HttpMessageNotReadableException] → 400 + [IssueErrorCodes.VALIDATION_FAILED]
 * - [MethodArgumentTypeMismatchException] → 400 + [IssueErrorCodes.VALIDATION_FAILED]
 * - [AuthenticationException] → 401 + [IssueErrorCodes.UNAUTHENTICATED]
 * - [IssueAccessDeniedException] → 403 + [IssueErrorCodes.ACCESS_DENIED]
 * - [IssueMovedException] → 308 Permanent Redirect + Location 헤더 (FR-MV-01, DATA.md §2)
 * - [IssueNotFoundException] → 404 + [IssueErrorCodes.ISSUE_NOT_FOUND]
 * - [IssueProjectNotFoundException] → 404 + [IssueErrorCodes.PROJECT_NOT_FOUND]
 * - [ResolutionNotFoundException] → 404 + [IssueErrorCodes.RESOLUTION_NOT_FOUND]
 * - [IssueKeyPrefixReservedException] → 409 + [IssueErrorCodes.KEY_PREFIX_RESERVED]
 * - [IssueVersionConflictException] → 409 + [IssueErrorCodes.VERSION_CONFLICT]
 * - [IssueTransitionNotAllowedException] → 409 + [IssueErrorCodes.TRANSITION_NOT_ALLOWED]
 * - [IssueWorkflowNotConfiguredException] → 422 + [IssueErrorCodes.WORKFLOW_NOT_CONFIGURED]
 * - [AssigneeNotFoundException] → 422 + [IssueErrorCodes.ASSIGNEE_NOT_FOUND]
 * - [IssueComponentNotFoundException] → 422 + [IssueErrorCodes.COMPONENT_NOT_FOUND]
 * - [IssueLinkedVersionNotFoundException] → 422 + [IssueErrorCodes.LINKED_VERSION_NOT_FOUND]
 * - [CustomFieldValidationException] → 422 + [IssueErrorCodes.CUSTOM_FIELD_VALIDATION_FAILED]
 * - [MoveSameProjectException] → 422 + [IssueErrorCodes.MOVE_SAME_PROJECT]
 * - [IssueHasSubtasksException] → 422 + [IssueErrorCodes.ISSUE_HAS_SUBTASKS]
 * - [InvalidTargetStateException] → 422 + [IssueErrorCodes.INVALID_TARGET_STATE]
 * - [InvalidTargetMappingException] → 422 + [IssueErrorCodes.INVALID_TARGET_MAPPING]
 * - [RequiredFieldMissingException] → 422 + [IssueErrorCodes.REQUIRED_FIELD_MISSING]
 * - [Exception] (fallback) → 500 + [IssueErrorCodes.INTERNAL_ERROR]
 *
 * TooManyFunctions: 도메인 예외 종류(400/401/403/404/409/422/500) 각각에 @ExceptionHandler 가 필요하므로
 * 함수 수가 임계치(11)를 넘는다. RestControllerAdvice 의 책임(예외→HTTP 변환)은 분리 불가한 단일 관심사라
 * 클래스 단위로 억제한다. FR-IS-02 D6 에서 [IssueTypeNotFoundException] 핸들러가 추가됐다
 * (type.web 패키지 한정 핸들러가 못 잡는 예외를 rest 패키지에서 404 로 매핑).
 * FR-IS-03 Task 5 에서 [AssigneeNotFoundException] 핸들러가 추가됐다 (422 + ASSIGNEE_NOT_FOUND).
 * FR-IS-07 Task B6 에서 [ResolutionNotFoundException] 핸들러가 추가됐다 (404 + RESOLUTION_NOT_FOUND).
 * FR-CM-02 Task 5 에서 [IssueComponentNotFoundException] 핸들러가 추가됐다 (422 + COMPONENT_NOT_FOUND).
 * FR-IS-10 BLOCKER 1 에서 [CustomFieldValidationException] 핸들러가 추가됐다 (422 + CUSTOM_FIELD_VALIDATION_FAILED).
 * FR-VR-03 Task 5 에서 [IssueLinkedVersionNotFoundException] 핸들러가 추가됐다 (422 + LINKED_VERSION_NOT_FOUND).
 * FR-MV-01 Task 4 에서 [IssueMovedException] 핸들러가 추가됐다 (308 Permanent Redirect + Location 헤더).
 * FR-MV-01 Task 8 에서 이동 도메인 예외 5종([MoveSameProjectException]/[IssueHasSubtasksException]/
 * [InvalidTargetStateException]/[InvalidTargetMappingException]/[RequiredFieldMissingException])
 * 및 [HttpMessageNotReadableException]/[MethodArgumentTypeMismatchException] 핸들러가 추가됐다 (모두 400/422).
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

    /**
     * 요청 본문 역직렬화 실패 — 400.
     *
     * JSON 형식 오류 또는 타입 불일치(UUID 필드에 정수 등) 시 Jackson 이 발생시킨다.
     * catch-all [handleInternalError] 가 이 예외를 500 으로 변질시키지 못하도록 명시 핸들러로 등록한다.
     * (FR-WT-01 WatcherExceptionHandler 동일 패턴 — 메모리 참조.)
     *
     * 보안 — 역직렬화 오류 상세를 응답에 포함하지 않고 일반 메시지만 반환한다. 원인은 로그에만 기록한다.
     *
     * @param ex 역직렬화 실패를 나타내는 Spring HTTP 메시지 변환 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("ISSUE_400 message_not_readable cause='{}'", ex.cause?.message ?: ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "validation-failed",
            title = "Validation Failed",
            errorCode = IssueErrorCodes.VALIDATION_FAILED,
            detail = "요청 본문을 읽을 수 없습니다. JSON 형식 또는 필드 값을 확인해 주세요.",
        )
    }

    /**
     * 경로 변수 또는 요청 파라미터 타입 불일치 — 400.
     *
     * 경로 변수가 UUID 타입이어야 할 때 올바르지 않은 값이 전달되면 Spring MVC 가 발생시킨다.
     * catch-all [handleInternalError] 가 이 예외를 500 으로 변질시키지 못하도록 명시 핸들러로 등록한다.
     * (FR-WT-01 WatcherExceptionHandler 동일 패턴 — 메모리 참조.)
     *
     * 보안 — 파라미터 이름·요청값 등 내부 정보를 응답에 포함하지 않는다. 로그에만 기록한다.
     *
     * @param ex 파라미터 이름·요청값·목표 타입 정보를 포함하는 예외.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("ISSUE_400 type_mismatch param='{}' value='{}'", ex.name, ex.value)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "validation-failed",
            title = "Validation Failed",
            errorCode = IssueErrorCodes.VALIDATION_FAILED,
            detail = "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
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

    // ── 308 ISSUE_MOVED (Permanent Redirect) ────────────────────────────────

    /**
     * [IssueMovedException] — 이슈가 다른 프로젝트로 이동되어 옛 키가 redirect 체인에 존재함 — 308.
     *
     * 308 Permanent Redirect 를 사용하는 이유 (DATA.md §2).
     * - 이슈 키는 Slack·이메일 등 외부에서 인용되므로 영속성이 보장돼야 한다 (이슈 키 영속성 §10).
     * - 301/302 는 POST 를 GET 으로 변경할 수 있어 PATCH/DELETE 시 의도치 않은 동작이 발생한다.
     * - 308 은 원본 HTTP 메서드를 그대로 유지하므로 클라이언트가 동일 메서드로 새 URL 에 재시도한다.
     *
     * 보안 — Location 헤더에 내부 식별자(issueId)를 노출하지 않고 새 키 경로만 사용한다.
     *
     * catch-all [handleInternalError] 보다 먼저 선택되도록 구체 예외 타입으로 등록한다.
     *
     * @param ex 이동 후 최종 이슈 키를 포함하는 예외.
     */
    @ExceptionHandler(IssueMovedException::class)
    fun handleIssueMoved(ex: IssueMovedException): ResponseEntity<Void> {
        log.info("ISSUE_308 issue_moved newKey='{}'", ex.newKey)
        val headers = HttpHeaders()
        headers.location = URI.create("/api/v1/issues/${ex.newKey}")
        return ResponseEntity<Void>(headers, HttpStatus.PERMANENT_REDIRECT)
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

    // ── 422 LINKED_VERSION_NOT_FOUND ─────────────────────────────────────────

    /**
     * [IssueLinkedVersionNotFoundException] — changeAffectsVersions / changeFixVersions 에서
     * 타 프로젝트 또는 소프트 삭제된 버전 지정 시 — 422 (FR-VR-03).
     *
     * 보안 — detail 에 내부 식별자(versionId)를 노출하지 않는다(guard-exception 누출 방지).
     * versionId 는 로그에만 기록한다.
     *
     * @param ex 존재하지 않는(또는 타 프로젝트/삭제된) 버전 UUID 를 포함하는 예외.
     */
    @ExceptionHandler(IssueLinkedVersionNotFoundException::class)
    fun handleLinkedVersionNotFound(ex: IssueLinkedVersionNotFoundException): ProblemDetail {
        log.info("ISSUE_422 linked_version_not_found versionId='{}'", ex.versionId)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "linked-version-not-found",
            title = "Linked Version Not Found",
            errorCode = IssueErrorCodes.LINKED_VERSION_NOT_FOUND,
            detail = "지정한 버전이 이 프로젝트에 존재하지 않거나 삭제되었습니다.",
        )
    }

    // ── 422 CUSTOM_FIELD_VALIDATION_FAILED ───────────────────────────────────

    /**
     * [CustomFieldValidationException] — 이슈 생성/수정 시 커스텀 필드 값 검증 실패 — 422.
     *
     * E1(미정의 키) / E2(required 누락) / E3(선택지 위반) / E4(타입 불일치) 모두 이 핸들러로 처리된다.
     * 보안 — detail 에 내부 사유(필드 키, 허용 옵션 목록 등)를 노출하지 않고 일반 메시지만 응답한다.
     * 원본 메시지는 로그에만 기록한다 (guard-exception 누출 방지).
     *
     * @param ex 검증 실패한 필드 키와 위반 사유를 포함하는 예외.
     */
    @ExceptionHandler(CustomFieldValidationException::class)
    fun handleCustomFieldValidation(ex: CustomFieldValidationException): ProblemDetail {
        log.info("ISSUE_422 custom_field_validation_failed message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "custom-field-validation-failed",
            title = "Custom Field Validation Failed",
            errorCode = IssueErrorCodes.CUSTOM_FIELD_VALIDATION_FAILED,
            detail = "커스텀 필드 값이 유효하지 않습니다.",
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

    // ── 422 MOVE_SAME_PROJECT (FR-MV-01) ─────────────────────────────────────

    /**
     * [MoveSameProjectException] — 원본과 대상이 동일한 프로젝트일 때 — 422 (EC1, FR-MV-01).
     *
     * @param ex 원본·대상이 같은 프로젝트 키를 포함하는 예외.
     */
    @ExceptionHandler(MoveSameProjectException::class)
    fun handleMoveSameProject(ex: MoveSameProjectException): ProblemDetail {
        log.info("ISSUE_422 move_same_project message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "move-same-project",
            title = "Move Same Project",
            errorCode = IssueErrorCodes.MOVE_SAME_PROJECT,
            detail = "같은 프로젝트 내로 이슈를 이동할 수 없습니다.",
        )
    }

    // ── 422 ISSUE_HAS_SUBTASKS (FR-MV-01) ────────────────────────────────────

    /**
     * [IssueHasSubtasksException] — 서브태스크를 보유한 이슈 이동 시도 — 422 (EC15, FR-MV-01).
     *
     * @param ex 서브태스크 존재로 이동이 거부된 예외.
     */
    @ExceptionHandler(IssueHasSubtasksException::class)
    fun handleIssueHasSubtasks(ex: IssueHasSubtasksException): ProblemDetail {
        log.info("ISSUE_422 issue_has_subtasks message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "issue-has-subtasks",
            title = "Issue Has Subtasks",
            errorCode = IssueErrorCodes.ISSUE_HAS_SUBTASKS,
            detail = "서브태스크가 있는 이슈는 이동할 수 없습니다.",
        )
    }

    // ── 422 INVALID_TARGET_STATE (FR-MV-01) ──────────────────────────────────

    /**
     * [InvalidTargetStateException] — 대상 프로젝트에서 유효한 상태를 결정할 수 없을 때 — 422 (EC7, FR-MV-01).
     *
     * @param ex 원본 상태 키와 지정한 대상 상태 키 정보를 포함하는 예외.
     */
    @ExceptionHandler(InvalidTargetStateException::class)
    fun handleInvalidTargetState(ex: InvalidTargetStateException): ProblemDetail {
        log.info("ISSUE_422 invalid_target_state message='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "invalid-target-state",
            title = "Invalid Target State",
            errorCode = IssueErrorCodes.INVALID_TARGET_STATE,
            detail = "대상 프로젝트에서 유효한 상태를 결정할 수 없습니다. targetStateKey 를 명시해 주세요.",
        )
    }

    // ── 422 INVALID_TARGET_MAPPING (FR-MV-01) ────────────────────────────────

    /**
     * [InvalidTargetMappingException] — 컴포넌트 또는 버전 매핑 대상 id 가 대상 프로젝트에 없을 때 — 422 (EC8, FR-MV-01).
     *
     * 보안 — 존재하지 않는 id 목록을 응답에 포함하지 않는다. 로그에만 기록한다.
     *
     * @param ex 매핑 종류(컴포넌트/버전)와 미존재 UUID 집합을 포함하는 예외.
     */
    @ExceptionHandler(InvalidTargetMappingException::class)
    fun handleInvalidTargetMapping(ex: InvalidTargetMappingException): ProblemDetail {
        log.info("ISSUE_422 invalid_target_mapping kind='{}' ids='{}'", ex.kind, ex.unknownIds)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "invalid-target-mapping",
            title = "Invalid Target Mapping",
            errorCode = IssueErrorCodes.INVALID_TARGET_MAPPING,
            detail = "지정한 ${ex.kind.name.lowercase()} 매핑 대상이 대상 프로젝트에 존재하지 않습니다.",
        )
    }

    // ── 422 REQUIRED_FIELD_MISSING (FR-MV-01) ────────────────────────────────

    /**
     * [RequiredFieldMissingException] — 대상 프로젝트 필수 커스텀필드가 제공되지 않았을 때 — 422 (EC9, FR-MV-01).
     *
     * 보안 — 누락된 필드 키 목록을 응답에 포함하지 않는다. 로그에만 기록한다.
     *
     * @param ex 누락된 필수 커스텀필드 키 집합을 포함하는 예외.
     */
    @ExceptionHandler(RequiredFieldMissingException::class)
    fun handleRequiredFieldMissing(ex: RequiredFieldMissingException): ProblemDetail {
        log.info("ISSUE_422 required_field_missing keys='{}'", ex.missingKeys)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "required-field-missing",
            title = "Required Field Missing",
            errorCode = IssueErrorCodes.REQUIRED_FIELD_MISSING,
            detail = "대상 프로젝트에서 필수인 커스텀 필드 값이 누락되었습니다.",
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

    // ── ResponseStatusException 상태 전파 (catch-all 변질 차단) ────────────────

    /**
     * [ResponseStatusException] — 컨트롤러/헬퍼가 명시한 HTTP 상태를 그대로 전파한다 (FR-PM-06 PR-B B1).
     *
     * [CurrentActor.current] 가 미인증 시 던지는 401 [ResponseStatusException] 이
     * catch-all [handleInternalError] 에 가로채여 500 으로 변질되던 문제를 차단한다.
     * `@RestControllerAdvice` 는 Spring 의 `ResponseStatusExceptionResolver` 보다 먼저 실행되므로,
     * [Exception] 보다 구체적인 이 핸들러를 등록해 Spring 이 우선 선택하도록 한다.
     *
     * 보안 — detail 에 `ex.reason` 등 내부 정보를 노출하지 않고 상태 코드 기반 일반 메시지를 사용한다
     * (guard-exception 누출 방지). 원본 사유는 로그에만 기록한다.
     *
     * @param ex 컨트롤러 계층에서 던진 상태 코드 보유 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("ISSUE_{} response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED ->
                    IssueErrorCodes.UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN ->
                    IssueErrorCodes.ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                else ->
                    IssueErrorCodes.INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(
            status = status,
            type = "response-status",
            title = status.reasonPhrase,
            errorCode = errorCode,
            detail = detail,
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
    const val LINKED_VERSION_NOT_FOUND = "ISSUE_LINKED_VERSION_NOT_FOUND"
    const val SECURITY_LEVEL_NOT_IN_SCHEME = "SECURITY_LEVEL_NOT_IN_SCHEME"
    const val RESOLUTION_NOT_FOUND = "RESOLUTION_NOT_FOUND"
    const val CUSTOM_FIELD_VALIDATION_FAILED = "CUSTOM_FIELD_VALIDATION_FAILED"
    const val MOVE_SAME_PROJECT = "MOVE_SAME_PROJECT"
    const val ISSUE_HAS_SUBTASKS = "ISSUE_HAS_SUBTASKS"
    const val INVALID_TARGET_STATE = "INVALID_TARGET_STATE"
    const val INVALID_TARGET_MAPPING = "INVALID_TARGET_MAPPING"
    const val REQUIRED_FIELD_MISSING = "REQUIRED_FIELD_MISSING"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

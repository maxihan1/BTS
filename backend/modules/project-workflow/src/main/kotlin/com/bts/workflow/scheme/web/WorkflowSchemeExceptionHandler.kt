// WorkflowSchemeExceptionHandler — 스킴 도메인 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환

package com.bts.workflow.scheme.web

import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.scheme.exception.IssueTypeNotFoundException
import com.bts.workflow.scheme.exception.MappingDefaultDuplicateException
import com.bts.workflow.scheme.exception.MappingDuplicateException
import com.bts.workflow.scheme.exception.SchemeInUseException
import com.bts.workflow.scheme.exception.SchemeKeyInvalidException
import com.bts.workflow.scheme.exception.SchemeStandardFieldLockedException
import com.bts.workflow.scheme.exception.SchemeStandardNotDeletableException
import com.bts.workflow.scheme.exception.TypeStandardNotDeletableException
import com.bts.workflow.scheme.exception.WorkflowSchemeNoDefaultException
import com.bts.workflow.scheme.exception.WorkflowSchemeNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI
import java.time.Instant

/**
 * project-workflow scheme BC 의 도메인 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [basePackages] 를 `com.bts.workflow.scheme` 으로 한정하여 다른 BC 예외를 잡지 않는다.
 * Spring @ControllerAdvice 우선순위 규칙에 의해 basePackages 를 지정한 이 핸들러가
 * 전역 [com.bts.workflow.web.WorkflowExceptionHandler] 보다 높은 우선순위를 가진다.
 * 따라서 scheme 패키지 컨트롤러에서 [WorkflowNotFoundException] 이 throw 되면
 * 이 핸들러의 [handleWorkflowNotFound] 가 먼저 처리하여 WORKFLOW_NOT_FOUND errorCode 를 반환한다.
 *
 * 매핑 규칙 (spec §4.5, 11건).
 * - [SchemeKeyInvalidException]             → 400 + SCHEME_KEY_INVALID
 * - [WorkflowSchemeNotFoundException]       → 404 + SCHEME_NOT_FOUND
 * - [SchemeStandardNotDeletableException]   → 403 + SCHEME_STANDARD_NOT_DELETABLE
 * - [SchemeInUseException]                  → 409 + SCHEME_IN_USE (usedByProjects 포함)
 * - [MappingDuplicateException]             → 409 + MAPPING_DUPLICATE
 * - [MappingDefaultDuplicateException]      → 409 + MAPPING_DEFAULT_DUPLICATE
 * - [WorkflowNotFoundException]             → 404 + WORKFLOW_NOT_FOUND
 * - [IssueTypeNotFoundException]            → 404 + ISSUE_TYPE_NOT_FOUND
 * - [TypeStandardNotDeletableException]     → 403 + TYPE_STANDARD_NOT_DELETABLE
 * - [WorkflowSchemeNoDefaultException]      → 500 + WORKFLOW_SCHEME_NO_DEFAULT (server invariant 위반)
 * - [SchemeStandardFieldLockedException]    → 403 + SCHEME_STANDARD_FIELD_LOCKED
 */
@RestControllerAdvice(basePackages = ["com.bts.workflow.scheme"])
class WorkflowSchemeExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 SCHEME_KEY_INVALID ────────────────────────────────────────────────

    /**
     * 스킴 키가 정규식 규칙을 위반 — 400.
     *
     * @param ex 유효하지 않은 키 정보를 담은 예외.
     */
    @ExceptionHandler(SchemeKeyInvalidException::class)
    fun handleSchemeKeyInvalid(ex: SchemeKeyInvalidException): ProblemDetail {
        log.info("SCHEME_400 scheme_key_invalid key='{}'", ex.key)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "scheme-key-invalid",
            title = "Scheme Key Invalid",
            errorCode = SchemeErrorCodes.SCHEME_KEY_INVALID,
            detail = ex.message,
        )
    }

    // ── 404 SCHEME_NOT_FOUND ──────────────────────────────────────────────────

    /**
     * 워크플로우 스킴이 존재하지 않음 — 404.
     *
     * @param ex 조회를 시도한 스킴 키를 담은 예외.
     */
    @ExceptionHandler(WorkflowSchemeNotFoundException::class)
    fun handleSchemeNotFound(ex: WorkflowSchemeNotFoundException): ProblemDetail {
        log.info("SCHEME_404 scheme_not_found key='{}'", ex.key)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "scheme-not-found",
            title = "Scheme Not Found",
            errorCode = SchemeErrorCodes.SCHEME_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 404 WORKFLOW_NOT_FOUND ────────────────────────────────────────────────

    /**
     * 워크플로우 키가 존재하지 않음 — 404.
     *
     * 매핑 추가 시 workflowKey 가 없을 때 발생한다.
     *
     * @param ex 조회를 시도한 워크플로우 키를 담은 예외.
     */
    @ExceptionHandler(WorkflowNotFoundException::class)
    fun handleWorkflowNotFound(ex: WorkflowNotFoundException): ProblemDetail {
        log.info("SCHEME_404 workflow_not_found key='{}'", ex.workflowKey)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "workflow-not-found",
            title = "Workflow Not Found",
            errorCode = SchemeErrorCodes.WORKFLOW_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 404 ISSUE_TYPE_NOT_FOUND ──────────────────────────────────────────────

    /**
     * 이슈 타입 키가 존재하지 않음 — 404.
     *
     * @param ex 조회를 시도한 이슈 타입 키를 담은 예외.
     */
    @ExceptionHandler(IssueTypeNotFoundException::class)
    fun handleIssueTypeNotFound(ex: IssueTypeNotFoundException): ProblemDetail {
        log.info("SCHEME_404 issue_type_not_found key='{}'", ex.issueTypeKey)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "issue-type-not-found",
            title = "Issue Type Not Found",
            errorCode = SchemeErrorCodes.ISSUE_TYPE_NOT_FOUND,
            detail = ex.message,
        )
    }

    // ── 403 SCHEME_STANDARD_NOT_DELETABLE ─────────────────────────────────────

    /**
     * 표준 스킴 삭제 시도 — 403.
     *
     * @param ex 삭제 시도된 표준 스킴 키를 담은 예외.
     */
    @ExceptionHandler(SchemeStandardNotDeletableException::class)
    fun handleSchemeStandardNotDeletable(ex: SchemeStandardNotDeletableException): ProblemDetail {
        log.info("SCHEME_403 scheme_standard_not_deletable key='{}'", ex.key)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "scheme-standard-not-deletable",
            title = "Scheme Standard Not Deletable",
            errorCode = SchemeErrorCodes.SCHEME_STANDARD_NOT_DELETABLE,
            detail = ex.message,
        )
    }

    // ── 403 TYPE_STANDARD_NOT_DELETABLE ───────────────────────────────────────

    /**
     * 표준 이슈 타입 삭제 시도 — 403.
     *
     * @param ex 삭제 시도된 표준 이슈 타입 키를 담은 예외.
     */
    @ExceptionHandler(TypeStandardNotDeletableException::class)
    fun handleTypeStandardNotDeletable(ex: TypeStandardNotDeletableException): ProblemDetail {
        log.info("SCHEME_403 type_standard_not_deletable key='{}'", ex.issueTypeKey)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "type-standard-not-deletable",
            title = "Type Standard Not Deletable",
            errorCode = SchemeErrorCodes.TYPE_STANDARD_NOT_DELETABLE,
            detail = ex.message,
        )
    }

    // ── 403 SCHEME_STANDARD_FIELD_LOCKED ─────────────────────────────────────

    /**
     * 표준 스킴의 잠긴 필드 변경 시도 — 403.
     *
     * @param ex 잠긴 필드 정보를 담은 예외.
     */
    @ExceptionHandler(SchemeStandardFieldLockedException::class)
    fun handleSchemeStandardFieldLocked(ex: SchemeStandardFieldLockedException): ProblemDetail {
        log.info("SCHEME_403 scheme_standard_field_locked key='{}' field='{}'", ex.key, ex.field)
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "scheme-standard-field-locked",
            title = "Scheme Standard Field Locked",
            errorCode = SchemeErrorCodes.SCHEME_STANDARD_FIELD_LOCKED,
            detail = ex.message,
        )
    }

    // ── 409 SCHEME_IN_USE ─────────────────────────────────────────────────────

    /**
     * 사용 중인 스킴 삭제 시도 — 409.
     *
     * 응답 body 에 `usedByProjects` 배열(프로젝트 ID 목록)을 포함한다.
     *
     * @param ex 해당 스킴을 참조하는 프로젝트 목록을 담은 예외.
     */
    @ExceptionHandler(SchemeInUseException::class)
    fun handleSchemeInUse(ex: SchemeInUseException): ProblemDetail {
        log.info("SCHEME_409 scheme_in_use projectCount={}", ex.usedByProjects.size)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "scheme-in-use",
            title = "Scheme In Use",
            errorCode = SchemeErrorCodes.SCHEME_IN_USE,
            detail = ex.message,
            additionalFields = mapOf("usedByProjects" to ex.usedByProjects),
        )
    }

    // ── 409 MAPPING_DUPLICATE ─────────────────────────────────────────────────

    /**
     * 동일 스킴+이슈타입 조합 매핑 중복 — 409.
     *
     * @param ex 중복 발생 스킴/이슈타입 키를 담은 예외.
     */
    @ExceptionHandler(MappingDuplicateException::class)
    fun handleMappingDuplicate(ex: MappingDuplicateException): ProblemDetail {
        log.info("SCHEME_409 mapping_duplicate scheme='{}' issueType='{}'", ex.schemeKey, ex.issueTypeKey)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "mapping-duplicate",
            title = "Mapping Duplicate",
            errorCode = SchemeErrorCodes.MAPPING_DUPLICATE,
            detail = ex.message,
        )
    }

    // ── 409 MAPPING_DEFAULT_DUPLICATE ─────────────────────────────────────────

    /**
     * 기본 매핑 중복 — 409.
     *
     * @param ex 기본 매핑 중복 발생 스킴 키를 담은 예외.
     */
    @ExceptionHandler(MappingDefaultDuplicateException::class)
    fun handleMappingDefaultDuplicate(ex: MappingDefaultDuplicateException): ProblemDetail {
        log.info("SCHEME_409 mapping_default_duplicate scheme='{}'", ex.schemeKey)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "mapping-default-duplicate",
            title = "Mapping Default Duplicate",
            errorCode = SchemeErrorCodes.MAPPING_DEFAULT_DUPLICATE,
            detail = ex.message,
        )
    }

    // ── 500 WORKFLOW_SCHEME_NO_DEFAULT ────────────────────────────────────────

    /**
     * 스킴에 기본 매핑이 없음 — 500 (서버 불변식 위반).
     *
     * 유효한 스킴은 반드시 기본 매핑을 가져야 한다. 이 예외는 데이터 무결성 문제를 나타낸다.
     *
     * @param ex 기본 매핑이 없는 스킴 키를 담은 예외.
     */
    @ExceptionHandler(WorkflowSchemeNoDefaultException::class)
    fun handleWorkflowSchemeNoDefault(ex: WorkflowSchemeNoDefaultException): ProblemDetail {
        log.error("SCHEME_500 workflow_scheme_no_default scheme='{}' — server invariant violated", ex.schemeKey)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "workflow-scheme-no-default",
            title = "Workflow Scheme No Default",
            errorCode = SchemeErrorCodes.WORKFLOW_SCHEME_NO_DEFAULT,
            detail = ex.message,
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * Spring 6 의 [ProblemDetail] (RFC 7807) 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp`, 그리고 선택적 추가 필드를 설정한다.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 ([SchemeErrorCodes]).
     * @param detail 이 특정 발생에 대한 상세 설명. null 이면 생략.
     * @param additionalFields ProblemDetail 에 추가할 커스텀 필드. 기본값 빈 맵.
     * @return 완성된 [ProblemDetail] 인스턴스.
     */
    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        errorCode: String,
        detail: String?,
        additionalFields: Map<String, Any> = emptyMap(),
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/$type")
        pd.title = title
        if (detail != null) pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        additionalFields.forEach { (k, v) -> pd.setProperty(k, v) }
        return pd
    }
}

/**
 * 워크플로우 스킴 BC 에러 코드 상수.
 *
 * spec §4.5 의 11건 errorCode 를 한 곳에서 관리한다.
 */
object SchemeErrorCodes {
    const val SCHEME_KEY_INVALID = "SCHEME_KEY_INVALID"
    const val SCHEME_NOT_FOUND = "SCHEME_NOT_FOUND"
    const val SCHEME_STANDARD_NOT_DELETABLE = "SCHEME_STANDARD_NOT_DELETABLE"
    const val SCHEME_IN_USE = "SCHEME_IN_USE"
    const val MAPPING_DUPLICATE = "MAPPING_DUPLICATE"
    const val MAPPING_DEFAULT_DUPLICATE = "MAPPING_DEFAULT_DUPLICATE"
    const val WORKFLOW_NOT_FOUND = "WORKFLOW_NOT_FOUND"
    const val ISSUE_TYPE_NOT_FOUND = "ISSUE_TYPE_NOT_FOUND"
    const val TYPE_STANDARD_NOT_DELETABLE = "TYPE_STANDARD_NOT_DELETABLE"
    const val WORKFLOW_SCHEME_NO_DEFAULT = "WORKFLOW_SCHEME_NO_DEFAULT"
    const val SCHEME_STANDARD_FIELD_LOCKED = "SCHEME_STANDARD_FIELD_LOCKED"
}

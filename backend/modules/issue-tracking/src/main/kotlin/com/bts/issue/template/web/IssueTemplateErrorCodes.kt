// 이슈 템플릿 BC 에러 코드 상수 — ISSUE_TEMPLATE_ 접두사 고정 (FR-TM-01 Task 6)

package com.bts.issue.template.web

/**
 * 이슈 템플릿 BC 에러 코드 상수.
 *
 * 모든 코드는 `ISSUE_TEMPLATE_` 접두사를 사용한다 (BTS 에러 코드 규칙 §1.16).
 * [IssueTemplateExceptionHandler] 에서 RFC 7807 ProblemDetail 의 `errorCode` 프로퍼티에 사용한다.
 */
object IssueTemplateErrorCodes {
    /** Bean Validation 실패 — 400. */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    /** 이슈 템플릿 미존재 — 404. */
    const val TEMPLATE_NOT_FOUND = "ISSUE_TEMPLATE_NOT_FOUND"

    /** 프로젝트 미존재 — 404. */
    const val PROJECT_NOT_FOUND = "ISSUE_TEMPLATE_PROJECT_NOT_FOUND"

    /** issueTypeId 에 해당하는 활성 이슈 타입 미존재 — 404. */
    const val ISSUE_TYPE_NOT_FOUND = "ISSUE_TEMPLATE_ISSUE_TYPE_NOT_FOUND"

    /** 동일 (project, issueType) 활성 템플릿 중복 — 409. */
    const val TEMPLATE_DUPLICATE = "ISSUE_TEMPLATE_DUPLICATE"

    /** 권한 없음 — 403. */
    const val ACCESS_DENIED = "ISSUE_TEMPLATE_ACCESS_DENIED"

    /** 도메인 불변식 위반(name/content blank 등) — 422. */
    const val TEMPLATE_INVALID = "ISSUE_TEMPLATE_INVALID"

    /** 분류되지 않은 내부 오류 — 500. */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

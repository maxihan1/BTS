// IssueType 에러 코드 상수 — ISSUE_TYPE_ 접두사 고정 (FR-IS-02)

package com.bts.issue.type.web

/**
 * IssueType 도메인 에러 코드 상수.
 *
 * 모든 코드는 `ISSUE_TYPE_` 접두사를 사용한다.
 * [IssueTypeExceptionHandler] 에서 RFC 7807 ProblemDetail 의 `errorCode` 프로퍼티에 사용한다.
 */
object IssueTypeErrorCodes {
    /** Bean Validation 실패 — 400. */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    /** 이슈 타입 미존재 — 404. */
    const val ISSUE_TYPE_NOT_FOUND = "ISSUE_TYPE_NOT_FOUND"

    /** 표준 타입 불변 위반 — 409. */
    const val ISSUE_TYPE_STANDARD_IMMUTABLE = "ISSUE_TYPE_STANDARD_IMMUTABLE"

    /** 키 중복 — 409. */
    const val ISSUE_TYPE_KEY_DUPLICATE = "ISSUE_TYPE_KEY_DUPLICATE"

    /** 키 형식 위반 — 409. */
    const val ISSUE_TYPE_KEY_INVALID = "ISSUE_TYPE_KEY_INVALID"

    /** 사용 중인 타입 삭제 시도 — 409. */
    const val ISSUE_TYPE_IN_USE = "ISSUE_TYPE_IN_USE"

    /** 재할당 대상 유효하지 않음 — 409. */
    const val ISSUE_TYPE_REASSIGN_TARGET_INVALID = "ISSUE_TYPE_REASSIGN_TARGET_INVALID"

    /** 분류되지 않은 내부 오류 — 500. */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

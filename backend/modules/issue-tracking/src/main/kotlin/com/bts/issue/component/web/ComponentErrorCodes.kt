// 컴포넌트 BC 에러 코드 상수 — COMPONENT_ 접두사 고정 (FR-CM-01)

package com.bts.issue.component.web

/**
 * 컴포넌트 BC 에러 코드 상수.
 *
 * 모든 코드는 `COMPONENT_` 접두사를 사용한다 (BTS 에러 코드 규칙 §1.16).
 * [ComponentExceptionHandler] 에서 RFC 7807 ProblemDetail 의 `errorCode` 프로퍼티에 사용한다.
 */
object ComponentErrorCodes {
    /** Bean Validation 실패 — 400. */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    /** 컴포넌트 미존재 — 404. */
    const val COMPONENT_NOT_FOUND = "COMPONENT_NOT_FOUND"

    /** 프로젝트 미존재 — 404. */
    const val PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND"

    /** 동일 프로젝트 내 이름 중복 — 409. */
    const val COMPONENT_NAME_DUPLICATE = "COMPONENT_NAME_DUPLICATE"

    /** 권한 없음 — 403. */
    const val ACCESS_DENIED = "COMPONENT_ACCESS_DENIED"

    /** 리드 사용자 미존재 — 422. */
    const val COMPONENT_LEAD_NOT_FOUND = "COMPONENT_LEAD_NOT_FOUND"

    /** 분류되지 않은 내부 오류 — 500. */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

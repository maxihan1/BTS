// 커스텀 필드 BC 에러 코드 상수 — CUSTOM_FIELD_ 접두사 고정 (FR-IS-10 Task 8)

package com.bts.issue.customfield.web

/**
 * 커스텀 필드 BC 에러 코드 상수.
 *
 * 모든 코드는 `CUSTOM_FIELD_` 접두사를 사용한다 (BTS 에러 코드 규칙 §1.16).
 * [CustomFieldExceptionHandler] 에서 RFC 7807 ProblemDetail 의 `errorCode` 프로퍼티에 사용한다.
 */
object CustomFieldErrorCodes {
    /** Bean Validation 실패 — 400. */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    /** 커스텀 필드 미존재 — 404. */
    const val CUSTOM_FIELD_NOT_FOUND = "CUSTOM_FIELD_NOT_FOUND"

    /** 프로젝트 미존재 — 404. */
    const val PROJECT_NOT_FOUND = "CUSTOM_FIELD_PROJECT_NOT_FOUND"

    /** 동일 프로젝트 내 key 중복 — 409. */
    const val CUSTOM_FIELD_KEY_DUPLICATE = "CUSTOM_FIELD_KEY_DUPLICATE"

    /** 권한 없음 — 403. */
    const val ACCESS_DENIED = "CUSTOM_FIELD_ACCESS_DENIED"

    /** 필드 정의 불변식 위반(key 형식, name 공백 등) — 422. */
    const val CUSTOM_FIELD_INVALID_DEFINITION = "CUSTOM_FIELD_INVALID_DEFINITION"

    /** 불변 속성(fieldType, key) 변경 시도 — 422. */
    const val CUSTOM_FIELD_IMMUTABLE_CHANGE = "CUSTOM_FIELD_IMMUTABLE_CHANGE"

    /** 분류되지 않은 내부 오류 — 500. */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

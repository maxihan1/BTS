// 버전 BC 에러 코드 상수 — VERSION_ 접두사 고정 (FR-VR-01 + FR-VR-02)

package com.bts.issue.version.web

/**
 * 버전 BC 에러 코드 상수.
 *
 * ACCESS_DENIED 만 `VERSION_ACCESS_DENIED` 접두사를 사용한다.
 * NOT_FOUND 류([VERSION_NOT_FOUND]), 중복([VERSION_NAME_DUPLICATE]) 도 `VERSION_` 접두사를 사용한다.
 * PROJECT_NOT_FOUND / VALIDATION_FAILED / INTERNAL_ERROR 는 ComponentErrorCodes 와 동일하게 접두사 없음.
 *
 * [VersionExceptionHandler] 에서 RFC 7807 ProblemDetail 의 `errorCode` 프로퍼티에 사용한다.
 */
object VersionErrorCodes {
    /** Bean Validation 실패 — 400. */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    /** 버전 미존재 — 404. */
    const val VERSION_NOT_FOUND = "VERSION_NOT_FOUND"

    /** 프로젝트 미존재 — 404. */
    const val PROJECT_NOT_FOUND = "PROJECT_NOT_FOUND"

    /** 동일 프로젝트 내 이름 중복 — 409. */
    const val VERSION_NAME_DUPLICATE = "VERSION_NAME_DUPLICATE"

    /** 허용되지 않는 상태 전이 또는 ARCHIVED 읽기전용 위반 — 409. */
    const val VERSION_TRANSITION_NOT_ALLOWED = "VERSION_TRANSITION_NOT_ALLOWED"

    /** 권한 없음 — 403. */
    const val ACCESS_DENIED = "VERSION_ACCESS_DENIED"

    /** 분류되지 않은 내부 오류 — 500. */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

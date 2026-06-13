// 이슈 링크 BC 에러 코드 상수 — 전부 대문자, LinkExceptionHandler 에서 사용한다.

package com.bts.issue.link.web

/**
 * 이슈 링크 BC 에러 코드 상수.
 *
 * [LinkExceptionHandler] 에서 RFC 7807 ProblemDetail 의 `errorCode` 프로퍼티에 사용한다.
 * 에러 코드 prefix는 `ISSUE_` (이슈 리소스 미존재) 및 `LINK_` / `PARENT_` (링크/부모 도메인)를 사용한다.
 */
object LinkErrorCodes {
    /** 이슈 미존재 — 404. source/target/base 이슈 모두 적용. */
    const val ISSUE_NOT_FOUND = "ISSUE_NOT_FOUND"

    /** 자기 자신 링크 — 422. */
    const val LINK_SELF_REFERENCE = "LINK_SELF_REFERENCE"

    /** 동일 조합 링크 중복 — 409. */
    const val DUPLICATE_LINK = "DUPLICATE_LINK"

    /** blocks 순환 탐지 — 409. */
    const val LINK_CYCLE = "LINK_CYCLE"

    /** 링크 미존재 — 404. */
    const val LINK_NOT_FOUND = "LINK_NOT_FOUND"

    /** 자기 자신을 부모로 지정 — 422. */
    const val PARENT_SELF_REFERENCE = "PARENT_SELF_REFERENCE"

    /** parent-child 순환 계층 — 409. */
    const val PARENT_CYCLE = "PARENT_CYCLE"

    /** 잘못된 linkType 코드 — 400. */
    const val INVALID_LINK_TYPE = "INVALID_LINK_TYPE"

    /** Bean Validation 실패 — 400. */
    const val VALIDATION_FAILED = "VALIDATION_FAILED"

    /** 유효하지 않은 graph depth — 400. */
    const val INVALID_DEPTH = "INVALID_DEPTH"

    /** 분류되지 않은 내부 오류 — 500. */
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}

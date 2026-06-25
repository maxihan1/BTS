// search BC API 에러 코드 상수 — SEARCH_ 접두사 (FR-SR-02 NFR-5)

package com.bts.search.web

/**
 * search-export-import BC 에러 코드 상수.
 *
 * 모든 코드는 `SEARCH_` 접두사를 사용한다(BTS 에러 코드 규칙 §6).
 * [SearchExceptionHandler]가 ProblemDetail 응답의 `errorCode` 필드에 설정한다.
 *
 * ### 코드 목록
 *
 * | 상수 | HTTP | 의미 |
 * |---|---|---|
 * | [SEARCH_SYNTAX_ERROR] | 400 | AQL 문법 오류. position 포함 |
 * | [SEARCH_UNKNOWN_FIELD] | 400 | 오타 등 인식할 수 없는 필드 |
 * | [SEARCH_FIELD_NOT_YET_SUPPORTED] | 400 | 후속 PR 지원 예정 필드 |
 * | [SEARCH_VALIDATION_FAILED] | 400 | DTO Bean Validation 실패 |
 * | [SEARCH_ACCESS_DENIED] | 403 | BROWSE 권한 없음 |
 * | [SEARCH_UNAUTHENTICATED] | 401 | 미인증 |
 * | [SEARCH_INTERNAL_ERROR] | 500 | 분류되지 않은 서버 오류 |
 */
object SearchErrorCodes {
    /** AQL 일반 문법 오류. 오류 위치(0-base 컬럼)가 RFC 7807 extension으로 제공된다. */
    const val SEARCH_SYNTAX_ERROR = "SEARCH_SYNTAX_ERROR"

    /** 인식할 수 없는 필드명(오타). 예: `foobar = 1`. */
    const val SEARCH_UNKNOWN_FIELD = "SEARCH_UNKNOWN_FIELD"

    /**
     * 현재 미지원이나 후속 PR에서 지원 예정인 필드.
     *
     * 예: `assignee`, `reporter`, `component`, `project`.
     * 사용자에게 "곧 지원 예정" 안내가 가능하도록 별도 코드로 구분한다.
     */
    const val SEARCH_FIELD_NOT_YET_SUPPORTED = "SEARCH_FIELD_NOT_YET_SUPPORTED"

    /** DTO Bean Validation(@field:NotBlank/@field:Max 등) 실패. */
    const val SEARCH_VALIDATION_FAILED = "SEARCH_VALIDATION_FAILED"

    /** BROWSE 권한 없음. 프로젝트 존재 여부를 노출하지 않기 위해 일반 메시지만 제공한다. */
    const val SEARCH_ACCESS_DENIED = "SEARCH_ACCESS_DENIED"

    /** 미인증. 세션 만료 등 인증 정보가 없는 경우. */
    const val SEARCH_UNAUTHENTICATED = "SEARCH_UNAUTHENTICATED"

    /** 분류되지 않은 서버 내부 오류. 상세는 서버 로그에만 기록한다. */
    const val SEARCH_INTERNAL_ERROR = "SEARCH_INTERNAL_ERROR"
}

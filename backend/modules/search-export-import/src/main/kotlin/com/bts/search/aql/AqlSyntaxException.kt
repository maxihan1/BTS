// AQL 구문 분석 오류 예외 — 오류 위치(컬럼)와 에러 코드를 포함

package com.bts.search.aql

/**
 * AQL 구문 분석 오류 코드.
 *
 * API 응답의 `errorCode` 필드에 사용되는 `SEARCH_` 접두사 코드 집합이다.
 * 두 종류의 필드 거부 코드를 구분해 클라이언트가 적절한 안내 메시지를 표시할 수 있도록 한다.
 */
enum class AqlErrorCode {
    /** 일반 문법 오류. 위치(컬럼) 정보가 함께 제공된다. */
    SEARCH_SYNTAX_ERROR,

    /** 인식할 수 없는 필드명(오타). 예: `foobar = 1`. */
    SEARCH_UNKNOWN_FIELD,

    /**
     * 현재는 지원하지 않고 후속 PR에서 지원 예정인 필드.
     *
     * 해당 필드가 존재함을 알리되, MVP 범위 밖임을 안내한다.
     * 예: `assignee`, `reporter`, `component`, `project`.
     */
    SEARCH_FIELD_NOT_YET_SUPPORTED,
}

/**
 * AQL 쿼리 구문 분석 오류 예외.
 *
 * 렉서([AqlLexer])가 아닌 파서([AqlParser]) 계층에서 발생하는 구문 오류를 나타낸다.
 * 오류가 발생한 입력 문자열 내 0-base 컬럼 인덱스([position])와
 * API 응답에 직접 노출되는 [errorCode]를 포함한다.
 *
 * ### 사용 예
 *
 * ```kotlin
 * throw AqlSyntaxException(
 *     message = "비어 있는 IN 목록은 허용되지 않습니다.",
 *     position = 10,
 *     errorCode = AqlErrorCode.SEARCH_SYNTAX_ERROR,
 * )
 * ```
 *
 * @property position 오류가 발생한 입력 문자열 내 0-base 컬럼 인덱스.
 *   토큰 기반이므로 첫 관련 토큰의 시작 위치를 사용한다.
 * @property errorCode API 응답 `errorCode` 필드에 노출되는 구분 코드.
 *   기본값은 [AqlErrorCode.SEARCH_SYNTAX_ERROR].
 */
class AqlSyntaxException(
    message: String,
    val position: Int,
    val errorCode: AqlErrorCode = AqlErrorCode.SEARCH_SYNTAX_ERROR,
) : RuntimeException(message)

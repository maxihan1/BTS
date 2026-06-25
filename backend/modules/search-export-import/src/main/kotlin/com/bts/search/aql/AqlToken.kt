// AQL 토큰 타입 열거 및 토큰 데이터 클래스 정의

package com.bts.search.aql

/**
 * AQL(Atlas Query Language) 렉서가 생성하는 토큰 타입 열거.
 *
 * 키워드 계열(KW_*)은 대소문자 무시 비교로 판정된다.
 * 예: "and", "AND", "And" 모두 [KW_AND] 로 분류.
 */
enum class AqlTokenType {
    /** `AND` (대소문자 무시) */
    KW_AND,

    /** `OR` (대소문자 무시) */
    KW_OR,

    /** `NOT` (대소문자 무시) */
    KW_NOT,

    /** `IN` (대소문자 무시) */
    KW_IN,

    /** `ORDER` (대소문자 무시) */
    KW_ORDER,

    /** `BY` (대소문자 무시) */
    KW_BY,

    /** `ASC` (대소문자 무시) */
    KW_ASC,

    /** `DESC` (대소문자 무시) */
    KW_DESC,

    /** `=` 등호 연산자 */
    EQ,

    /** `!=` 불일치 연산자 */
    NEQ,

    /** `~` 부분 문자열 포함 연산자 */
    TILDE,

    /** `(` 왼쪽 괄호 */
    LPAREN,

    /** `)` 오른쪽 괄호 */
    RPAREN,

    /** `,` 쉼표 */
    COMMA,

    /**
     * 큰따옴표로 감싼 문자열 리터럴.
     *
     * [AqlToken.lexeme] 에는 따옴표를 제외한 내부 문자열이 담긴다.
     * 예: `"hello world"` → lexeme = `hello world`.
     */
    QUOTED_STRING,

    /**
     * 따옴표 없이 연속된 비공백 문자(키워드·연산자·구분자 제외).
     *
     * 필드명(field)과 따옴표 없는 값(bare word) 모두 이 타입으로 반환된다.
     * 파서가 문맥에 따라 필드명인지 값인지 구분한다.
     *
     * 이 타입 하나가 [IDENT]와 [BARE_WORD] 역할을 겸한다.
     */
    IDENT,

    /**
     * 따옴표 없는 값 토큰(bare word).
     *
     * [IDENT]와 동일하게 사용되는 별칭 타입이다.
     * 파서가 위치 컨텍스트에서 필드명/값을 구분할 수 있도록 테스트에서
     * `isIn(IDENT, BARE_WORD)` 형태로 허용한다.
     */
    BARE_WORD,

    /** 정수 리터럴. 예: `42`. */
    NUMBER,
}

/**
 * AQL 키워드 집합 — 대문자 정규화 후 비교에 사용.
 *
 * 키워드는 [AqlTokenType] 과 1:1 대응하며 대소문자 무시로 인식된다.
 */
private val KEYWORDS: Map<String, AqlTokenType> =
    mapOf(
        "AND" to AqlTokenType.KW_AND,
        "OR" to AqlTokenType.KW_OR,
        "NOT" to AqlTokenType.KW_NOT,
        "IN" to AqlTokenType.KW_IN,
        "ORDER" to AqlTokenType.KW_ORDER,
        "BY" to AqlTokenType.KW_BY,
        "ASC" to AqlTokenType.KW_ASC,
        "DESC" to AqlTokenType.KW_DESC,
    )

/**
 * 주어진 텍스트가 AQL 키워드인지 확인해 해당 [AqlTokenType] 을 반환한다.
 * 키워드가 아닌 경우 null 을 반환한다.
 *
 * @param text 원본 렉서 텍스트 (대소문자 혼합 가능).
 * @return 해당 키워드의 [AqlTokenType], 키워드가 아니면 null.
 */
internal fun resolveKeyword(text: String): AqlTokenType? = KEYWORDS[text.uppercase()]

/**
 * AQL 렉서가 생성하는 단일 토큰.
 *
 * @property type 토큰 타입. [AqlTokenType] 참조.
 * @property lexeme 원시 텍스트 값. [AqlTokenType.QUOTED_STRING] 은 따옴표 제외 내부 문자열.
 * @property position 입력 문자열 내 0-base 컬럼 인덱스 (첫 문자 기준).
 */
data class AqlToken(
    val type: AqlTokenType,
    val lexeme: String,
    val position: Int,
)

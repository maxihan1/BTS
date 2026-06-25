// AQL(Atlas Query Language) 손수 작성 렉서 — 입력 문자열을 AqlToken 목록으로 변환

package com.bts.search.aql

import org.slf4j.LoggerFactory

/**
 * AQL 렉서 — 입력 문자열을 [AqlToken] 목록으로 변환한다.
 *
 * 외부 파서 라이브러리(ANTLR 등)를 사용하지 않는 순수 Kotlin 구현이다.
 *
 * ### 지원 토큰
 * - 키워드: AND / OR / NOT / IN / ORDER / BY / ASC / DESC (대소문자 무시)
 * - 식별자 / bare word: 영문자·한글·숫자·언더스코어로 이루어진 연속 문자
 * - 따옴표 문자열: `"..."` (내부 공백·특수문자 보존, 따옴표는 lexeme에서 제외)
 * - 숫자: 연속된 0..9
 * - 연산자: `=`, `!=`, `~`
 * - 구분자: `(`, `)`, `,`
 * - 공백(스페이스·탭·개행)은 무시
 *
 * ### 오류 처리
 * - 닫히지 않은 따옴표 → [AqlLexException](position = 여는 따옴표 컬럼 인덱스)
 * - `!` 뒤에 `=` 가 없음 → [AqlLexException](position = `!` 컬럼 인덱스)
 * - 그 외 인식 불가 문자 → [AqlLexException]
 *
 * @param input 렉싱할 AQL 쿼리 원문.
 */
class AqlLexer(private val input: String) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 현재 스캔 위치 (0-base 컬럼 인덱스). */
    private var pos: Int = 0

    /** 현재 위치의 문자를 반환한다. 입력 끝이면 null. */
    private fun current(): Char? = if (pos < input.length) input[pos] else null

    /** 현재 위치를 1 전진시킨다. */
    private fun advance() {
        pos++
    }

    /**
     * 입력 문자열 전체를 스캔해 [AqlToken] 목록을 반환한다.
     *
     * @return 인식된 토큰 목록. 빈 입력이면 빈 리스트.
     * @throws AqlLexException 닫히지 않은 따옴표, 인식 불가 문자 등 렉싱 오류.
     */
    fun tokenize(): List<AqlToken> {
        val tokens = mutableListOf<AqlToken>()
        while (pos < input.length) {
            val ch = current() ?: break
            val token = scanNext(ch)
            if (token != null) tokens.add(token)
        }
        return tokens
    }

    /**
     * 현재 문자를 기준으로 다음 토큰을 스캔한다.
     *
     * 공백이면 null 을 반환(무시). 그 외는 [AqlToken] 반환.
     *
     * @param ch 현재 위치의 문자.
     * @return 인식된 토큰, 또는 공백 소비 시 null.
     * @throws AqlLexException 인식 불가 문자인 경우.
     */
    private fun scanNext(ch: Char): AqlToken? {
        if (ch.isWhitespace()) {
            advance()
            return null
        }
        return when (ch) {
            '"' -> scanQuotedString()
            '(' -> singleCharToken(AqlTokenType.LPAREN, "(")
            ')' -> singleCharToken(AqlTokenType.RPAREN, ")")
            ',' -> singleCharToken(AqlTokenType.COMMA, ",")
            '=' -> singleCharToken(AqlTokenType.EQ, "=")
            '~' -> singleCharToken(AqlTokenType.TILDE, "~")
            '!' -> scanBang()
            else -> scanLiteralOrIdent(ch)
        }
    }

    /**
     * 단일 문자 토큰을 생성하고 위치를 전진한다.
     *
     * @param type 생성할 토큰 타입.
     * @param lexeme 단일 문자 원시 텍스트.
     */
    private fun singleCharToken(
        type: AqlTokenType,
        lexeme: String,
    ): AqlToken {
        val token = AqlToken(type, lexeme, pos)
        advance()
        return token
    }

    /**
     * 숫자 리터럴 또는 식별자를 스캔한다.
     *
     * @param ch 현재 위치의 문자.
     * @throws AqlLexException 숫자도 식별자도 아닌 문자인 경우.
     */
    private fun scanLiteralOrIdent(ch: Char): AqlToken =
        when {
            ch.isDigit() -> scanNumber()
            isIdentStart(ch) -> scanWord()
            else -> {
                log.warn("AQL 렉서 인식 불가 문자: '{}' at position {}", ch, pos)
                throw AqlLexException("인식할 수 없는 문자: '$ch'", pos)
            }
        }

    /**
     * 따옴표 문자열(`"..."`)을 스캔한다.
     *
     * 여는 따옴표 위치를 position으로 기록한다.
     * 닫는 따옴표 없이 입력이 끝나면 [AqlLexException] 을 던진다.
     */
    private fun scanQuotedString(): AqlToken {
        val start = pos
        advance() // 여는 '"' 소비
        val sb = StringBuilder()
        while (pos < input.length) {
            val ch = input[pos]
            if (ch == '"') {
                advance() // 닫는 '"' 소비
                return AqlToken(AqlTokenType.QUOTED_STRING, sb.toString(), start)
            }
            sb.append(ch)
            advance()
        }
        // 닫는 따옴표 없이 입력 끝
        throw AqlLexException(
            "닫히지 않은 따옴표 — 컬럼 $start 에서 시작한 문자열이 닫히지 않았습니다.",
            start,
        )
    }

    /**
     * `!` 문자를 스캔한다. `!=` 면 [AqlTokenType.NEQ], 그 외는 [AqlLexException].
     */
    private fun scanBang(): AqlToken {
        val start = pos
        advance() // '!' 소비
        if (current() == '=') {
            advance() // '=' 소비
            return AqlToken(AqlTokenType.NEQ, "!=", start)
        }
        throw AqlLexException("'!' 뒤에 '=' 가 없습니다. '!=' 만 지원합니다.", start)
    }

    /**
     * 숫자 리터럴을 스캔한다(연속된 0..9).
     */
    private fun scanNumber(): AqlToken {
        val start = pos
        val sb = StringBuilder()
        while (pos < input.length && input[pos].isDigit()) {
            sb.append(input[pos])
            advance()
        }
        return AqlToken(AqlTokenType.NUMBER, sb.toString(), start)
    }

    /**
     * 식별자 또는 키워드를 스캔한다.
     *
     * 영문자·한글·숫자·언더스코어로 이루어진 연속 문자를 읽은 뒤,
     * [resolveKeyword] 로 키워드 여부를 판단해 [AqlTokenType] 을 결정한다.
     */
    private fun scanWord(): AqlToken {
        val start = pos
        val sb = StringBuilder()
        while (pos < input.length && isIdentPart(input[pos])) {
            sb.append(input[pos])
            advance()
        }
        val text = sb.toString()
        val kwType = resolveKeyword(text)
        return if (kwType != null) {
            AqlToken(kwType, text, start)
        } else {
            AqlToken(AqlTokenType.IDENT, text, start)
        }
    }

    companion object {
        /** 식별자 첫 문자 조건 — 영문자·한글·언더스코어. */
        private fun isIdentStart(ch: Char): Boolean = ch.isLetter() || ch == '_'

        /**
         * 식별자 이후 문자 조건 — 영문자·한글·숫자·언더스코어·하이픈.
         *
         * 하이픈은 bare word 값(예: `high-priority`)을 지원하기 위해 허용한다.
         */
        private fun isIdentPart(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == '-'
    }
}

/**
 * AQL 렉싱 오류를 나타내는 예외.
 *
 * 오류 발생 위치를 [position](0-base 컬럼 인덱스)으로 제공해
 * API 응답의 `position` 필드에 직접 사용할 수 있다.
 *
 * @param message 사람이 읽을 수 있는 오류 설명.
 * @param position 오류가 발생한 입력 문자열 내 0-base 컬럼 인덱스.
 */
class AqlLexException(
    message: String,
    val position: Int,
) : RuntimeException(message)

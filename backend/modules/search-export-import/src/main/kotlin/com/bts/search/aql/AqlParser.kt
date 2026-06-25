// AQL(Atlas Query Language) 재귀하강 파서 — List<AqlToken> → AqlParseResult

package com.bts.search.aql

import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlSort
import com.bts.shared.search.AqlValue
import com.bts.shared.search.SortDirection
import org.slf4j.LoggerFactory

/**
 * AQL 재귀하강 파서.
 *
 * [AqlLexer] 가 생성한 [List]<[AqlToken]> 을 받아
 * [AqlParseResult] (AST 루트 노드 + 정렬 목록)를 반환한다.
 *
 * 외부 파서 라이브러리(ANTLR 등)를 사용하지 않는 순수 Kotlin 구현이다.
 *
 * ### 문법 우선순위 (낮은 것부터)
 *
 * ```
 * query      := orExpr (ORDER BY sortList)?
 * orExpr     := andExpr (OR andExpr)*
 * andExpr    := notExpr (AND notExpr)*
 * notExpr    := NOT notExpr | primary
 * primary    := '(' orExpr ')' | comparison
 * comparison := field op value
 *            |  field (IN | NOT IN) '(' valueList ')'
 * ```
 *
 * AND 는 OR 보다 우선순위가 높다(standard JQL/SQL 동일).
 *
 * ### DoS 방어
 *
 * - [MAX_DEPTH] — 괄호/NOT 중첩 한계. 초과 시 [AqlSyntaxException].
 * - [MAX_TOKEN_COUNT] — 토큰 수 한계. 초과 시 [AqlSyntaxException].
 *
 * ### 오류 처리
 *
 * 구문 오류는 항상 [AqlSyntaxException] 으로 던진다.
 * 렉서 오류([AqlLexException])는 파서 외부에서 처리한다.
 *
 * @param tokens [AqlLexer] 가 생성한 토큰 목록.
 * @throws AqlSyntaxException 구문 오류 시.
 */
@Suppress("TooManyFunctions") // 문법 규칙 1개당 함수 1개가 재귀하강 파서의 구조적 특성
class AqlParser(private val tokens: List<AqlToken>) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 현재 토큰 인덱스(0-base). */
    private var pos: Int = 0

    /** 현재 재귀 깊이 카운터 (DoS 방어). */
    private var depth: Int = 0

    // ── 진입점 ────────────────────────────────────────────────────────────────

    /**
     * 토큰 목록을 파싱해 [AqlParseResult] 를 반환한다.
     *
     * @return 파싱된 AST 루트 노드와 정렬 기준 목록.
     * @throws AqlSyntaxException 구문 오류 또는 DoS 방어 상한 초과 시.
     */
    fun parse(): AqlParseResult {
        guardTokenCount()
        if (tokens.isEmpty()) {
            throw AqlSyntaxException("빈 쿼리는 허용되지 않습니다.", position = 0)
        }
        val ast = parseOrExpr()
        val sort = parseOptionalOrderBy()
        expectEnd()
        log.debug("AQL 파싱 완료: ast={}, sort={}", ast::class.simpleName, sort.size)
        return AqlParseResult(ast = ast, sort = sort)
    }

    // ── 문법 규칙 ─────────────────────────────────────────────────────────────

    /**
     * `orExpr := andExpr (OR andExpr)*`
     *
     * OR 는 AND 보다 우선순위가 낮으므로 가장 바깥 표현식 단위다.
     * 여러 OR 를 왼쪽 결합으로 처리한다.
     */
    private fun parseOrExpr(): AqlNode {
        var left = parseAndExpr()
        while (peek()?.type == AqlTokenType.KW_OR) {
            advance()
            val right = parseAndExpr()
            left = AqlNode.Or(left, right)
        }
        return left
    }

    /**
     * `andExpr := notExpr (AND notExpr)*`
     *
     * AND 는 OR 보다 우선순위가 높다.
     * 여러 AND 를 왼쪽 결합으로 처리한다.
     */
    private fun parseAndExpr(): AqlNode {
        var left = parseNotExpr()
        while (peek()?.type == AqlTokenType.KW_AND) {
            advance()
            val right = parseNotExpr()
            left = AqlNode.And(left, right)
        }
        return left
    }

    /**
     * `notExpr := NOT notExpr | primary`
     *
     * NOT 은 재귀적으로 중첩 가능하다. 깊이 상한을 함께 검사한다.
     */
    private fun parseNotExpr(): AqlNode {
        if (peek()?.type == AqlTokenType.KW_NOT) {
            val notToken = advance()
            guardDepth(notToken.position)
            depth++
            val child = parseNotExpr()
            depth--
            return AqlNode.Not(child)
        }
        return parsePrimary()
    }

    /**
     * `primary := '(' orExpr ')' | comparison`
     *
     * 괄호로 묶인 하위 표현식 또는 단일 comparison 을 처리한다.
     * 괄호 중첩 시 깊이 상한을 함께 검사한다.
     */
    private fun parsePrimary(): AqlNode {
        val token =
            peek()
                ?: throw AqlSyntaxException("표현식이 예상되었지만 입력이 끝났습니다.", currentPosition())

        return when (token.type) {
            AqlTokenType.LPAREN -> {
                advance() // '(' 소비
                guardDepth(token.position)
                depth++
                val inner = parseOrExpr()
                depth--
                expectRparen()
                inner
            }
            AqlTokenType.IDENT, AqlTokenType.BARE_WORD -> parseComparison()
            else -> throw AqlSyntaxException(
                "필드명 또는 '(' 가 예상되었지만 '${token.lexeme}' 를 만났습니다.",
                token.position,
            )
        }
    }

    /**
     * `comparison := field op value | field (IN | NOT IN) '(' valueList ')'`
     *
     * 단일 필드에 대한 비교 표현식을 파싱한다.
     * 필드명 검증([AqlFields])과 연산자 제약도 여기서 수행한다.
     */
    private fun parseComparison(): AqlNode {
        val fieldToken = expectIdent("필드명")
        val fieldName = fieldToken.lexeme
        validateField(fieldName, fieldToken.position)

        val opToken =
            peek()
                ?: throw AqlSyntaxException("연산자가 예상되었지만 입력이 끝났습니다.", fieldToken.position)

        return when (opToken.type) {
            AqlTokenType.KW_NOT -> parseNotIn(fieldName, fieldToken.position)
            AqlTokenType.KW_IN -> parseIn(fieldName, fieldToken.position)
            AqlTokenType.EQ, AqlTokenType.NEQ, AqlTokenType.TILDE ->
                parseBinaryComparison(fieldName, fieldToken.position)
            else -> throw AqlSyntaxException(
                "연산자(=, !=, ~, IN, NOT IN)가 예상되었지만 '${opToken.lexeme}' 를 만났습니다.",
                opToken.position,
            )
        }
    }

    /**
     * 이진 비교(`field = value`, `field != value`, `field ~ value`)를 파싱한다.
     */
    private fun parseBinaryComparison(
        fieldName: String,
        fieldPos: Int,
    ): AqlNode.Comparison {
        val opToken = advance()
        val op = tokenToOperator(opToken)
        validateOperatorForField(fieldName, op, opToken.position)
        val value = parseValue(fieldPos)
        return AqlNode.Comparison(AqlField(fieldName), op, listOf(value))
    }

    /**
     * `field IN '(' valueList ')'` 를 파싱한다.
     */
    private fun parseIn(
        fieldName: String,
        fieldPos: Int,
    ): AqlNode.Comparison {
        advance() // IN 소비
        val values = parseValueList(fieldPos)
        return AqlNode.Comparison(AqlField(fieldName), AqlOperator.IN, values)
    }

    /**
     * `field NOT IN '(' valueList ')'` 를 파싱한다.
     */
    private fun parseNotIn(
        fieldName: String,
        fieldPos: Int,
    ): AqlNode.Comparison {
        advance() // NOT 소비
        expectKeyword(AqlTokenType.KW_IN, "'IN'", fieldPos)
        val values = parseValueList(fieldPos)
        return AqlNode.Comparison(AqlField(fieldName), AqlOperator.NOT_IN, values)
    }

    /**
     * `'(' valueList ')' — value (',' value)*` 를 파싱한다.
     *
     * 빈 목록(`()`)은 허용하지 않는다.
     */
    private fun parseValueList(fieldPos: Int): List<AqlValue> {
        expectLparen(fieldPos)

        val firstPeek = peek()
        if (firstPeek?.type == AqlTokenType.RPAREN) {
            throw AqlSyntaxException("IN 목록이 비어 있습니다. 최소 하나의 값이 필요합니다.", firstPeek.position)
        }

        val values = mutableListOf(parseValue(fieldPos))
        consumeCommaAndValues(values, fieldPos)

        expectRparenForValueList()
        return values
    }

    /** valueList 내 쉼표 구분 추가 값들을 소비한다. */
    private fun consumeCommaAndValues(
        values: MutableList<AqlValue>,
        fieldPos: Int,
    ) {
        while (peek()?.type == AqlTokenType.COMMA) {
            advance() // ',' 소비
            val afterComma =
                peek()
                    ?: throw AqlSyntaxException("',' 뒤에 값이 예상되었지만 입력이 끝났습니다.", currentPosition())
            if (afterComma.type == AqlTokenType.RPAREN) {
                throw AqlSyntaxException("',' 뒤에 값이 예상되었지만 ')' 를 만났습니다.", afterComma.position)
            }
            values.add(parseValue(fieldPos))
        }
    }

    /**
     * 단일 값 토큰을 파싱한다.
     *
     * 지원하는 값 형식: QUOTED_STRING, IDENT(bare word 포함), NUMBER.
     *
     * @param contextPos 오류 메시지용 컨텍스트 위치(현재 토큰이 없을 때 사용).
     */
    private fun parseValue(contextPos: Int): AqlValue {
        val token =
            peek()
                ?: throw AqlSyntaxException("값이 예상되었지만 입력이 끝났습니다.", contextPos)
        return when (token.type) {
            AqlTokenType.QUOTED_STRING, AqlTokenType.IDENT, AqlTokenType.BARE_WORD -> {
                advance()
                AqlValue.Str(token.lexeme)
            }
            AqlTokenType.NUMBER -> {
                advance()
                AqlValue.Num(token.lexeme.toInt())
            }
            else -> throw AqlSyntaxException(
                "값(문자열, 숫자, bare word)이 예상되었지만 '${token.lexeme}' 를 만났습니다.",
                token.position,
            )
        }
    }

    // ── ORDER BY ─────────────────────────────────────────────────────────────

    /**
     * 선택적 `ORDER BY sortList` 절을 파싱한다.
     *
     * ORDER BY 가 없으면 빈 목록을 반환한다.
     * ORDER BY 가 두 번 나오면 [AqlSyntaxException] 을 던진다.
     */
    private fun parseOptionalOrderBy(): List<AqlSort> {
        val orderToken = peek()
        if (orderToken == null || orderToken.type != AqlTokenType.KW_ORDER) return emptyList()

        advance() // ORDER 소비
        expectKeyword(AqlTokenType.KW_BY, "'BY'", orderToken.position)

        val sorts = parseSortList(orderToken.position)
        checkNoDuplicateOrderBy()
        return sorts
    }

    /** 두 번째 ORDER BY 가 있으면 예외를 던진다. */
    private fun checkNoDuplicateOrderBy() {
        val dupToken = peek()
        if (dupToken?.type == AqlTokenType.KW_ORDER) {
            throw AqlSyntaxException("ORDER BY 절이 중복되었습니다.", dupToken.position)
        }
    }

    /**
     * `sortList := field [ASC|DESC] (',' field [ASC|DESC])*` 를 파싱한다.
     */
    private fun parseSortList(orderPos: Int): List<AqlSort> {
        val fieldToken =
            peek()
                ?: throw AqlSyntaxException("ORDER BY 뒤에 정렬 필드가 예상됩니다.", orderPos)
        if (fieldToken.type != AqlTokenType.IDENT && fieldToken.type != AqlTokenType.BARE_WORD) {
            throw AqlSyntaxException(
                "정렬 필드명이 예상되었지만 '${fieldToken.lexeme}' 를 만났습니다.",
                fieldToken.position,
            )
        }

        val sorts = mutableListOf(parseSortItem())
        while (peek()?.type == AqlTokenType.COMMA) {
            advance() // ',' 소비
            sorts.add(parseSortItem())
        }
        return sorts
    }

    /**
     * 단일 정렬 항목 `field [ASC|DESC]` 를 파싱한다.
     *
     * 방향이 생략되면 기본값 [SortDirection.ASC] 를 사용한다.
     */
    private fun parseSortItem(): AqlSort {
        val fieldToken = expectIdent("정렬 필드명")
        val direction =
            when (peek()?.type) {
                AqlTokenType.KW_ASC -> {
                    advance()
                    SortDirection.ASC
                }
                AqlTokenType.KW_DESC -> {
                    advance()
                    SortDirection.DESC
                }
                else -> SortDirection.ASC
            }
        return AqlSort(field = AqlField(fieldToken.lexeme), direction = direction)
    }

    // ── 검증 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * 필드명을 [AqlFields] 화이트리스트로 검증한다.
     *
     * @param fieldName 검증할 필드명.
     * @param position 오류 위치 (토큰 컬럼 인덱스).
     * @throws AqlSyntaxException 지원하지 않거나 후속 예정인 필드.
     */
    private fun validateField(
        fieldName: String,
        position: Int,
    ) {
        when (AqlFields.classify(fieldName)) {
            AqlFields.FieldClassification.PLANNED ->
                throw AqlSyntaxException(
                    "필드 '$fieldName' 는 현재 지원하지 않습니다. 후속 버전에서 지원 예정입니다.",
                    position,
                    AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED,
                )
            AqlFields.FieldClassification.UNKNOWN ->
                throw AqlSyntaxException(
                    "알 수 없는 필드: '$fieldName'. 지원 필드: ${AqlFields.MVP_FIELDS.joinToString()}.",
                    position,
                    AqlErrorCode.SEARCH_UNKNOWN_FIELD,
                )
            AqlFields.FieldClassification.SUPPORTED -> Unit
        }
    }

    /**
     * 필드와 연산자 조합이 유효한지 검증한다.
     *
     * @param fieldName 필드명.
     * @param op 사용하려는 연산자.
     * @param position 오류 위치.
     * @throws AqlSyntaxException 허용되지 않는 연산자 조합.
     */
    private fun validateOperatorForField(
        fieldName: String,
        op: AqlOperator,
        position: Int,
    ) {
        if (AqlFields.isOperatorForbidden(fieldName, op)) {
            throw AqlSyntaxException(
                "필드 '$fieldName' 에는 연산자 '${op.name}' 를 사용할 수 없습니다.",
                position,
            )
        }
    }

    /**
     * DoS 방어: 현재 재귀 깊이가 [MAX_DEPTH] 를 초과하면 예외를 던진다.
     *
     * @param position 오류 위치.
     */
    private fun guardDepth(position: Int) {
        if (depth >= MAX_DEPTH) {
            throw AqlSyntaxException(
                "중첩 깊이가 최대 허용치($MAX_DEPTH)를 초과했습니다.",
                position,
            )
        }
    }

    /**
     * DoS 방어: 토큰 수가 [MAX_TOKEN_COUNT] 를 초과하면 예외를 던진다.
     */
    private fun guardTokenCount() {
        if (tokens.size > MAX_TOKEN_COUNT) {
            throw AqlSyntaxException(
                "쿼리 토큰 수가 최대 허용치($MAX_TOKEN_COUNT)를 초과했습니다.",
                position = 0,
            )
        }
    }

    // ── 토큰 소비 헬퍼 ───────────────────────────────────────────────────────

    /** 현재 위치의 토큰을 반환하고 위치를 한 칸 전진한다. */
    private fun advance(): AqlToken {
        val token = tokens[pos]
        pos++
        return token
    }

    /** 현재 위치의 토큰을 소비하지 않고 미리 본다. 끝이면 null. */
    private fun peek(): AqlToken? = tokens.getOrNull(pos)

    /** 현재 또는 마지막 토큰의 위치를 반환한다. */
    private fun currentPosition(): Int = tokens.getOrNull(pos)?.position ?: tokens.lastOrNull()?.position ?: 0

    /**
     * 식별자(IDENT 또는 BARE_WORD) 토큰을 소비하고 반환한다.
     *
     * @param what 오류 메시지에 사용할 기대 값 설명.
     */
    private fun expectIdent(what: String): AqlToken {
        val token =
            peek()
                ?: throw AqlSyntaxException("$what 이 예상되었지만 입력이 끝났습니다.", currentPosition())
        if (token.type != AqlTokenType.IDENT && token.type != AqlTokenType.BARE_WORD) {
            throw AqlSyntaxException(
                "$what 이 예상되었지만 '${token.lexeme}' 를 만났습니다.",
                token.position,
            )
        }
        return advance()
    }

    /**
     * 특정 키워드 토큰이 있으면 소비한다. 없으면 예외를 던진다.
     *
     * @param expected 기대하는 토큰 타입.
     * @param label 오류 메시지에 사용할 키워드 설명.
     * @param contextPos 현재 토큰이 없을 때 사용할 오류 위치.
     */
    private fun expectKeyword(
        expected: AqlTokenType,
        label: String,
        contextPos: Int,
    ) {
        val token =
            peek()
                ?: throw AqlSyntaxException("$label 이 예상되었지만 입력이 끝났습니다.", contextPos)
        if (token.type != expected) {
            throw AqlSyntaxException(
                "$label 이 예상되었지만 '${token.lexeme}' 를 만났습니다.",
                token.position,
            )
        }
        advance()
    }

    /** `(` 토큰을 소비한다. 없으면 예외를 던진다. */
    private fun expectLparen(contextPos: Int) {
        val token =
            peek()
                ?: throw AqlSyntaxException("'(' 가 예상되었지만 입력이 끝났습니다.", contextPos)
        if (token.type != AqlTokenType.LPAREN) {
            throw AqlSyntaxException(
                "'(' 가 예상되었지만 '${token.lexeme}' 를 만났습니다.",
                token.position,
            )
        }
        advance()
    }

    /** `)` 토큰을 소비한다. 없으면 예외를 던진다. */
    private fun expectRparen() {
        val token =
            peek()
                ?: throw AqlSyntaxException("')' 가 예상되었지만 입력이 끝났습니다.", currentPosition())
        if (token.type != AqlTokenType.RPAREN) {
            throw AqlSyntaxException(
                "')' 가 예상되었지만 '${token.lexeme}' 를 만났습니다.",
                token.position,
            )
        }
        advance()
    }

    /** valueList 의 닫는 `)` 를 소비한다. */
    private fun expectRparenForValueList() {
        val token =
            peek()
                ?: throw AqlSyntaxException("IN 목록의 ')' 가 예상되었지만 입력이 끝났습니다.", currentPosition())
        if (token.type != AqlTokenType.RPAREN) {
            throw AqlSyntaxException(
                "IN 목록의 ')' 가 예상되었지만 '${token.lexeme}' 를 만났습니다.",
                token.position,
            )
        }
        advance()
    }

    /** 모든 토큰이 소비되었는지 확인한다. 남은 토큰이 있으면 예외를 던진다. */
    private fun expectEnd() {
        val remaining = peek()
        if (remaining != null) {
            throw AqlSyntaxException(
                "예상치 않은 토큰: '${remaining.lexeme}'. 쿼리가 정상적으로 끝나야 합니다.",
                remaining.position,
            )
        }
    }

    /** 연산자 토큰을 [AqlOperator] 로 변환한다. */
    private fun tokenToOperator(token: AqlToken): AqlOperator =
        when (token.type) {
            AqlTokenType.EQ -> AqlOperator.EQ
            AqlTokenType.NEQ -> AqlOperator.NEQ
            AqlTokenType.TILDE -> AqlOperator.CONTAINS
            else -> throw AqlSyntaxException(
                "연산자(=, !=, ~)가 예상되었지만 '${token.lexeme}' 를 만났습니다.",
                token.position,
            )
        }

    companion object {
        /** 재귀 중첩 최대 깊이 (DoS 방어). */
        const val MAX_DEPTH: Int = 50

        /**
         * 최대 허용 토큰 수 (DoS 방어).
         *
         * 쿼리 문자열 2000자 상한(FR-4)에서 평균 토큰 길이 3자를 가정하면
         * 최대 약 667개 토큰이므로 1000개로 설정한다.
         */
        const val MAX_TOKEN_COUNT: Int = 1000
    }
}

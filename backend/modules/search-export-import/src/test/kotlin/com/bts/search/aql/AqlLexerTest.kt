// AqlLexer 단위 테스트 — 토큰화 케이스 전체 + 위치 정확성 + 오류 케이스

package com.bts.search.aql

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [AqlLexer] 렉서 단위 테스트.
 *
 * 검증 항목.
 * - 각 토큰 종류(키워드/식별자/따옴표 문자열/bare word/숫자/괄호/연산자/쉼표) 토큰화
 * - 키워드 대소문자 무시 (and → AND, Or → OR 등)
 * - 따옴표 문자열 내 공백 보존
 * - 연산자 != / ~ 토큰화
 * - 공백/탭/개행 무시
 * - 닫히지 않은 따옴표 → 컬럼 위치 포함 예외
 * - 각 토큰의 컬럼(position) 정확성
 */
class AqlLexerTest {

    private fun lex(input: String): List<AqlToken> = AqlLexer(input).tokenize()

    // ── 단일 토큰 종류 ───────────────────────────────────────────────────────────

    @Test
    fun `단일 식별자를 IDENT 토큰으로 인식한다`() {
        val tokens = lex("status")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.IDENT)
        assertThat(tokens[0].lexeme).isEqualTo("status")
        assertThat(tokens[0].position).isEqualTo(0)
    }

    @Test
    fun `숫자 리터럴을 NUMBER 토큰으로 인식한다`() {
        val tokens = lex("42")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.NUMBER)
        assertThat(tokens[0].lexeme).isEqualTo("42")
        assertThat(tokens[0].position).isEqualTo(0)
    }

    @Test
    fun `따옴표 문자열을 QUOTED_STRING 토큰으로 인식한다`() {
        val tokens = lex("\"hello world\"")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.QUOTED_STRING)
        assertThat(tokens[0].lexeme).isEqualTo("hello world")
        assertThat(tokens[0].position).isEqualTo(0)
    }

    @Test
    fun `bare word(따옴표 없는 값)를 BARE_WORD 토큰으로 인식한다`() {
        // 키워드가 아닌 연속 비공백 문자 — 필드 컨텍스트 밖에서 나타나는 값
        val tokens = lex("open")
        assertThat(tokens).hasSize(1)
        // open은 키워드가 아니므로 IDENT 또는 BARE_WORD 중 하나여야 한다.
        // 렉서는 키워드 여부를 판단해 IDENT로 반환하고, 파서가 필드/값을 구분한다.
        assertThat(tokens[0].type).isIn(AqlTokenType.IDENT, AqlTokenType.BARE_WORD)
        assertThat(tokens[0].lexeme).isEqualTo("open")
    }

    @Test
    fun `왼쪽 괄호를 LPAREN 토큰으로 인식한다`() {
        val tokens = lex("(")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.LPAREN)
        assertThat(tokens[0].position).isEqualTo(0)
    }

    @Test
    fun `오른쪽 괄호를 RPAREN 토큰으로 인식한다`() {
        val tokens = lex(")")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.RPAREN)
        assertThat(tokens[0].position).isEqualTo(0)
    }

    @Test
    fun `쉼표를 COMMA 토큰으로 인식한다`() {
        val tokens = lex(",")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.COMMA)
        assertThat(tokens[0].position).isEqualTo(0)
    }

    @Test
    fun `등호 연산자를 EQ 토큰으로 인식한다`() {
        val tokens = lex("=")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.EQ)
        assertThat(tokens[0].position).isEqualTo(0)
    }

    @Test
    fun `불일치 연산자 != 를 NEQ 토큰으로 인식한다`() {
        val tokens = lex("!=")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.NEQ)
        assertThat(tokens[0].lexeme).isEqualTo("!=")
        assertThat(tokens[0].position).isEqualTo(0)
    }

    @Test
    fun `물결 연산자 ~ 를 TILDE 토큰으로 인식한다`() {
        val tokens = lex("~")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.TILDE)
        assertThat(tokens[0].position).isEqualTo(0)
    }

    // ── 키워드 (대소문자 무시) ────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["AND", "and", "And", "aNd"])
    fun `AND 키워드를 대소문자 무관하게 KW_AND 토큰으로 인식한다`(input: String) {
        val tokens = lex(input)
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.KW_AND)
    }

    @ParameterizedTest
    @ValueSource(strings = ["OR", "or", "Or", "oR"])
    fun `OR 키워드를 대소문자 무관하게 KW_OR 토큰으로 인식한다`(input: String) {
        val tokens = lex(input)
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.KW_OR)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NOT", "not", "Not"])
    fun `NOT 키워드를 대소문자 무관하게 KW_NOT 토큰으로 인식한다`(input: String) {
        val tokens = lex(input)
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.KW_NOT)
    }

    @ParameterizedTest
    @ValueSource(strings = ["IN", "in", "In"])
    fun `IN 키워드를 대소문자 무관하게 KW_IN 토큰으로 인식한다`(input: String) {
        val tokens = lex(input)
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.KW_IN)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ORDER", "order", "Order"])
    fun `ORDER 키워드를 대소문자 무관하게 KW_ORDER 토큰으로 인식한다`(input: String) {
        val tokens = lex(input)
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.KW_ORDER)
    }

    @ParameterizedTest
    @ValueSource(strings = ["BY", "by", "By"])
    fun `BY 키워드를 대소문자 무관하게 KW_BY 토큰으로 인식한다`(input: String) {
        val tokens = lex(input)
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.KW_BY)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ASC", "asc", "Asc"])
    fun `ASC 키워드를 대소문자 무관하게 KW_ASC 토큰으로 인식한다`(input: String) {
        val tokens = lex(input)
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.KW_ASC)
    }

    @ParameterizedTest
    @ValueSource(strings = ["DESC", "desc", "Desc"])
    fun `DESC 키워드를 대소문자 무관하게 KW_DESC 토큰으로 인식한다`(input: String) {
        val tokens = lex(input)
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.KW_DESC)
    }

    // ── 공백 무시 ────────────────────────────────────────────────────────────────

    @Test
    fun `공백으로 분리된 토큰들을 올바르게 토큰화한다`() {
        val tokens = lex("status = open")
        assertThat(tokens).hasSize(3)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.IDENT)
        assertThat(tokens[0].lexeme).isEqualTo("status")
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.EQ)
        assertThat(tokens[2].lexeme).isEqualTo("open")
    }

    @Test
    fun `탭과 개행도 공백처럼 무시한다`() {
        val tokens = lex("status\t=\nopen")
        assertThat(tokens).hasSize(3)
        assertThat(tokens[0].lexeme).isEqualTo("status")
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.EQ)
        assertThat(tokens[2].lexeme).isEqualTo("open")
    }

    @Test
    fun `앞뒤 공백이 있어도 토큰을 올바르게 인식한다`() {
        val tokens = lex("  status  ")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].lexeme).isEqualTo("status")
    }

    // ── 따옴표 문자열 세부 ────────────────────────────────────────────────────────

    @Test
    fun `따옴표 문자열 내 공백을 보존한다`() {
        val tokens = lex("\"로그인 버그 수정\"")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.QUOTED_STRING)
        assertThat(tokens[0].lexeme).isEqualTo("로그인 버그 수정")
    }

    @Test
    fun `따옴표 문자열 내 특수문자를 보존한다`() {
        val tokens = lex("\"hello = world\"")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.QUOTED_STRING)
        assertThat(tokens[0].lexeme).isEqualTo("hello = world")
    }

    @Test
    fun `빈 따옴표 문자열을 QUOTED_STRING 토큰으로 인식한다`() {
        val tokens = lex("\"\"")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.QUOTED_STRING)
        assertThat(tokens[0].lexeme).isEqualTo("")
    }

    // ── 복합 쿼리 ────────────────────────────────────────────────────────────────

    @Test
    fun `전형적인 AQL 쿼리를 올바르게 토큰화한다`() {
        val tokens = lex("status = open AND priority = 1")
        assertThat(tokens).hasSize(7)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.IDENT)   // status
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.EQ)      // =
        assertThat(tokens[2].lexeme).isEqualTo("open")              // open
        assertThat(tokens[3].type).isEqualTo(AqlTokenType.KW_AND)  // AND
        assertThat(tokens[4].lexeme).isEqualTo("priority")          // priority
        assertThat(tokens[5].type).isEqualTo(AqlTokenType.EQ)      // =
        assertThat(tokens[6].type).isEqualTo(AqlTokenType.NUMBER)  // 1
        assertThat(tokens[6].lexeme).isEqualTo("1")
    }

    @Test
    fun `IN 절 쿼리를 올바르게 토큰화한다`() {
        val tokens = lex("status IN (open, closed)")
        assertThat(tokens).hasSize(7)
        assertThat(tokens[0].lexeme).isEqualTo("status")
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.KW_IN)
        assertThat(tokens[2].type).isEqualTo(AqlTokenType.LPAREN)
        assertThat(tokens[3].lexeme).isEqualTo("open")
        assertThat(tokens[4].type).isEqualTo(AqlTokenType.COMMA)
        assertThat(tokens[5].lexeme).isEqualTo("closed")
        assertThat(tokens[6].type).isEqualTo(AqlTokenType.RPAREN)
    }

    @Test
    fun `NOT IN 절 쿼리를 올바르게 토큰화한다`() {
        val tokens = lex("status NOT IN (closed)")
        assertThat(tokens).hasSize(5)
        assertThat(tokens[0].lexeme).isEqualTo("status")
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.KW_NOT)
        assertThat(tokens[2].type).isEqualTo(AqlTokenType.KW_IN)
        assertThat(tokens[3].type).isEqualTo(AqlTokenType.LPAREN)
        assertThat(tokens[4].lexeme).isEqualTo("closed")
    }

    @Test
    fun `느낌표로 시작하는 != 연산자를 NEQ 토큰으로 인식한다`() {
        val tokens = lex("status != closed")
        assertThat(tokens).hasSize(3)
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.NEQ)
        assertThat(tokens[1].lexeme).isEqualTo("!=")
    }

    @Test
    fun `물결(contains) 연산자를 포함한 쿼리를 토큰화한다`() {
        val tokens = lex("summary ~ \"로그인\"")
        assertThat(tokens).hasSize(3)
        assertThat(tokens[0].lexeme).isEqualTo("summary")
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.TILDE)
        assertThat(tokens[2].type).isEqualTo(AqlTokenType.QUOTED_STRING)
        assertThat(tokens[2].lexeme).isEqualTo("로그인")
    }

    @Test
    fun `ORDER BY 절을 올바르게 토큰화한다`() {
        val tokens = lex("status = open ORDER BY priority DESC")
        val types = tokens.map { it.type }
        assertThat(types).contains(AqlTokenType.KW_ORDER, AqlTokenType.KW_BY, AqlTokenType.KW_DESC)
    }

    // ── 컬럼(position) 정확성 ───────────────────────────────────────────────────

    @Test
    fun `첫 번째 토큰의 position이 0이다`() {
        val tokens = lex("status")
        assertThat(tokens[0].position).isEqualTo(0)
    }

    @Test
    fun `두 번째 토큰의 position이 공백 이후 첫 문자 인덱스다`() {
        // "status = open"
        //  0123456789...
        //  status → 0
        //  =      → 7
        //  open   → 9
        val tokens = lex("status = open")
        assertThat(tokens[0].position).isEqualTo(0)
        assertThat(tokens[1].position).isEqualTo(7)
        assertThat(tokens[2].position).isEqualTo(9)
    }

    @Test
    fun `따옴표 문자열 토큰의 position은 여는 따옴표 위치다`() {
        // "summary ~ \"bug\""
        //  0123456789012345
        //  summary → 0
        //  ~       → 8
        //  "bug"   → 10 (여는 따옴표 위치)
        val tokens = lex("summary ~ \"bug\"")
        assertThat(tokens[2].type).isEqualTo(AqlTokenType.QUOTED_STRING)
        assertThat(tokens[2].position).isEqualTo(10)
    }

    @Test
    fun `괄호 토큰의 position이 정확하다`() {
        // "IN (open)"
        //  0123456789
        //  IN   → 0
        //  (    → 3
        //  open → 4
        //  )    → 8
        val tokens = lex("IN (open)")
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.LPAREN)
        assertThat(tokens[1].position).isEqualTo(3)
        assertThat(tokens[3].type).isEqualTo(AqlTokenType.RPAREN)
        assertThat(tokens[3].position).isEqualTo(8)
    }

    @Test
    fun `숫자 토큰의 position이 정확하다`() {
        // "priority = 42"
        //  0123456789012
        //  priority → 0
        //  =        → 9
        //  42       → 11
        val tokens = lex("priority = 42")
        assertThat(tokens[2].type).isEqualTo(AqlTokenType.NUMBER)
        assertThat(tokens[2].position).isEqualTo(11)
    }

    @Test
    fun `느낌표(!) 단독 사용 시 예외를 발생시킨다`() {
        assertThatThrownBy { lex("status ! open") }
            .isInstanceOf(AqlLexException::class.java)
    }

    // ── 오류 케이스 ─────────────────────────────────────────────────────────────

    @Test
    fun `닫히지 않은 따옴표는 컬럼 위치를 포함한 예외를 던진다`() {
        // 컬럼 0에서 여는 따옴표가 닫히지 않음
        assertThatThrownBy { lex("\"닫히지 않은 문자열") }
            .isInstanceOf(AqlLexException::class.java)
            .satisfies { ex ->
                val lexEx = ex as AqlLexException
                assertThat(lexEx.position).isEqualTo(0)
            }
    }

    @Test
    fun `쿼리 중간에 닫히지 않은 따옴표는 해당 컬럼 위치를 예외에 담는다`() {
        // status = "열린 따옴표
        // 0123456789
        // "열린 따옴표는 인덱스 9에서 시작
        val input = "status = \"열린 따옴표"
        assertThatThrownBy { lex(input) }
            .isInstanceOf(AqlLexException::class.java)
            .satisfies { ex ->
                val lexEx = ex as AqlLexException
                assertThat(lexEx.position).isEqualTo(9)
            }
    }

    @Test
    fun `빈 입력은 빈 토큰 목록을 반환한다`() {
        val tokens = lex("")
        assertThat(tokens).isEmpty()
    }

    @Test
    fun `공백만 있는 입력은 빈 토큰 목록을 반환한다`() {
        val tokens = lex("   \t\n  ")
        assertThat(tokens).isEmpty()
    }

    @Test
    fun `느낌표 뒤에 등호가 없으면 예외를 던진다`() {
        assertThatThrownBy { lex("status ! open") }
            .isInstanceOf(AqlLexException::class.java)
            .satisfies { ex ->
                val lexEx = ex as AqlLexException
                // '!' 는 인덱스 7에 위치
                assertThat(lexEx.position).isEqualTo(7)
            }
    }

    @Test
    fun `다중 자릿수 숫자를 하나의 NUMBER 토큰으로 인식한다`() {
        val tokens = lex("1234")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.NUMBER)
        assertThat(tokens[0].lexeme).isEqualTo("1234")
    }

    @Test
    fun `키워드와 식별자가 혼재한 쿼리를 정확하게 구분한다`() {
        // "label = android" — label과 android는 식별자/bare word, =는 연산자
        val tokens = lex("label = android")
        assertThat(tokens).hasSize(3)
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.IDENT)
        assertThat(tokens[0].lexeme).isEqualTo("label")
        assertThat(tokens[2].lexeme).isEqualTo("android")
    }
}

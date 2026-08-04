// AqlLexer 단위 테스트 — 토큰화 케이스 전체 + 위치 정확성 + 오류 케이스

package com.bts.search.aql

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.InstanceOfAssertFactories
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
 * - 문자열 이스케이프(`\"` · `\\`) 및 프론트 `escapeAqlString` 과의 크로스 레이어 round-trip 계약
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
        assertThat(tokens[0].type).isEqualTo(AqlTokenType.IDENT) // status
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.EQ) // =
        assertThat(tokens[2].lexeme).isEqualTo("open") // open
        assertThat(tokens[3].type).isEqualTo(AqlTokenType.KW_AND) // AND
        assertThat(tokens[4].lexeme).isEqualTo("priority") // priority
        assertThat(tokens[5].type).isEqualTo(AqlTokenType.EQ) // =
        assertThat(tokens[6].type).isEqualTo(AqlTokenType.NUMBER) // 1
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
        // "status NOT IN (closed)" → 6개 토큰
        // status / NOT / IN / ( / closed / )
        val tokens = lex("status NOT IN (closed)")
        assertThat(tokens).hasSize(6)
        assertThat(tokens[0].lexeme).isEqualTo("status")
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.KW_NOT)
        assertThat(tokens[2].type).isEqualTo(AqlTokenType.KW_IN)
        assertThat(tokens[3].type).isEqualTo(AqlTokenType.LPAREN)
        assertThat(tokens[4].lexeme).isEqualTo("closed")
        assertThat(tokens[5].type).isEqualTo(AqlTokenType.RPAREN)
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
            .asInstanceOf(InstanceOfAssertFactories.type(AqlLexException::class.java))
            .extracting(AqlLexException::position)
            .isEqualTo(0)
    }

    @Test
    fun `쿼리 중간에 닫히지 않은 따옴표는 해당 컬럼 위치를 예외에 담는다`() {
        // status = "열린 따옴표
        // 0123456789
        // "열린 따옴표는 인덱스 9에서 시작
        val input = "status = \"열린 따옴표"
        assertThatThrownBy { lex(input) }
            .isInstanceOf(AqlLexException::class.java)
            .asInstanceOf(InstanceOfAssertFactories.type(AqlLexException::class.java))
            .extracting(AqlLexException::position)
            .isEqualTo(9)
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
        // '!' 는 인덱스 7에 위치
        assertThatThrownBy { lex("status ! open") }
            .isInstanceOf(AqlLexException::class.java)
            .asInstanceOf(InstanceOfAssertFactories.type(AqlLexException::class.java))
            .extracting(AqlLexException::position)
            .isEqualTo(7)
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

    // ── 문자열 이스케이프 — 프론트 escapeAqlString 과의 크로스 레이어 계약 ──────────────
    //
    // ★상대 층: apps/web/src/lib/aql-text-query.ts (escapeAqlString · buildTextQuery)
    //
    // 왜 이 계약이 필요한가.
    // 커맨드 팔레트(FR-UX-12 F4)는 사용자가 친 자유 텍스트를 `text ~ "<이스케이프>"` 로
    // **프로그램이 조립**해 백엔드로 보낸다. 프론트 규칙은 2단계다 —
    // 역슬래시를 먼저 `\\` 로, 그다음 큰따옴표를 `\"` 로 치환한다(순서를 바꾸면 이중 이스케이프).
    // 렉서가 그 문법을 모르면 사용자가 따옴표 한 글자만 쳐도 문자열이 조기에 닫혀 400 이 난다.
    //
    // 이 결함은 FR-UX-12 가 만든 것이 아니라 드러낸 것이다. AQL 검색 화면(FR-SR-02/04)에서
    // 사용자가 직접 `summary ~ "그가 말한 "버그""` 를 쳐도 동일하게 실패했다.
    // → search-export-import BC 선재 결함 hot-fix.
    //
    // 판정식은 **원문 복원(round-trip)** 이다. 사용자가 친 것이 검색어로 그대로 도달해야 한다.
    // 아래 각 테스트는 두 층을 함께 고정한다.
    //   (1) 프론트 규칙을 옮긴 미러 함수 == 박제한 쿼리 원문   → 프론트가 바뀌면 깨진다
    //   (2) 그 쿼리를 렉싱한 QUOTED_STRING lexeme == 사용자 원문 → 렉서가 바뀌면 깨진다

    /**
     * 프론트 `escapeAqlString`(apps/web/src/lib/aql-text-query.ts) 을 그대로 옮긴 미러.
     *
     * 역슬래시를 **먼저** 치환한다. 큰따옴표를 먼저 치환하면 그때 삽입한 역슬래시가
     * 다음 단계에서 다시 이스케이프돼 이중 이스케이프가 된다.
     */
    private fun escapeAqlStringMirror(raw: String): String = raw.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 프론트 `buildTextQuery`(같은 파일) 미러 — 자유 텍스트를 전문검색 쿼리로 감싼다. */
    private fun buildTextQueryMirror(raw: String): String = "text ~ \"" + escapeAqlStringMirror(raw) + "\""

    /**
     * 프론트가 조립한 쿼리를 렉싱하면 사용자 원문이 복원되는지 단언한다.
     *
     * @param userInput 사용자가 검색창에 친 원문.
     * @param expectedQuery 프론트가 보내는 쿼리 원문(박제값). 미러 함수 결과와 일치해야 한다.
     */
    private fun assertAqlEscapeRoundTrip(
        userInput: String,
        expectedQuery: String,
    ) {
        assertThat(buildTextQueryMirror(userInput))
            .describedAs("프론트 이스케이프 규칙이 바뀌었다 — apps/web/src/lib/aql-text-query.ts 확인")
            .isEqualTo(expectedQuery)

        val tokens = lex(expectedQuery)
        assertThat(tokens).hasSize(3)
        assertThat(tokens[0].lexeme).isEqualTo("text")
        assertThat(tokens[1].type).isEqualTo(AqlTokenType.TILDE)
        assertThat(tokens[2].type).isEqualTo(AqlTokenType.QUOTED_STRING)
        assertThat(tokens[2].lexeme)
            .describedAs("round-trip 실패 — 사용자가 친 것이 검색어로 그대로 도달해야 한다")
            .isEqualTo(userInput)
    }

    @Test
    fun `큰따옴표가 섞인 검색어가 원문 그대로 복원된다`() {
        // 사용자: 로그인"버그  →  쿼리: text ~ "로그인\"버그"
        assertAqlEscapeRoundTrip(
            userInput = "로그인\"버그",
            expectedQuery = "text ~ \"로그인\\\"버그\"",
        )
    }

    @Test
    fun `역슬래시가 섞인 검색어가 원문 그대로 복원된다`() {
        // 사용자: a\b  →  쿼리: text ~ "a\\b"
        assertAqlEscapeRoundTrip(
            userInput = "a\\b",
            expectedQuery = "text ~ \"a\\\\b\"",
        )
    }

    @Test
    fun `역슬래시와 큰따옴표가 연달아 섞인 검색어가 원문 그대로 복원된다`() {
        // 사용자: a\"b  →  쿼리: text ~ "a\\\"b"
        assertAqlEscapeRoundTrip(
            userInput = "a\\\"b",
            expectedQuery = "text ~ \"a\\\\\\\"b\"",
        )
    }

    @Test
    fun `윈도 경로처럼 역슬래시가 여러 개인 검색어가 원문 그대로 복원된다`() {
        // 사용자: C:\Users\temp  →  쿼리: text ~ "C:\\Users\\temp"
        assertAqlEscapeRoundTrip(
            userInput = "C:\\Users\\temp",
            expectedQuery = "text ~ \"C:\\\\Users\\\\temp\"",
        )
    }

    @Test
    fun `이스케이프된 따옴표를 소비한 뒤 다음 토큰의 position이 정확하다`() {
        // text ~ "a\"b" AND status = open
        // 0123456789...
        //   "  → 7 (여는 따옴표)   AND → 14   status → 18   = → 25   open → 27
        // 이스케이프는 2글자를 소비한다. 1글자만 전진하면 이후 모든 position 이 밀린다.
        val tokens = lex("text ~ \"a\\\"b\" AND status = open")
        assertThat(tokens).hasSize(7)
        assertThat(tokens[2].type).isEqualTo(AqlTokenType.QUOTED_STRING)
        assertThat(tokens[2].lexeme).isEqualTo("a\"b")
        assertThat(tokens[2].position).isEqualTo(7)
        assertThat(tokens[3].type).isEqualTo(AqlTokenType.KW_AND)
        assertThat(tokens[3].position).isEqualTo(14)
        assertThat(tokens[6].lexeme).isEqualTo("open")
        assertThat(tokens[6].position).isEqualTo(27)
    }

    @Test
    fun `이스케이프 대상이 아닌 역슬래시는 원문 두 글자를 그대로 보존한다`() {
        // 사용자가 AQL 화면에 직접 친 `text ~ "C:\temp"` — \t 는 정의된 이스케이프가 아니다.
        // 이스케이프 도입 전과 동일하게 `C:\temp` 로 남아야 한다(하위호환 · 검색어 원문 도달).
        val tokens = lex("text ~ \"C:\\temp\"")
        assertThat(tokens).hasSize(3)
        assertThat(tokens[2].type).isEqualTo(AqlTokenType.QUOTED_STRING)
        assertThat(tokens[2].lexeme).isEqualTo("C:\\temp")
    }

    @Test
    fun `역슬래시로 끝나고 닫히지 않은 문자열은 인덱스 초과 없이 예외를 던진다`() {
        // "abc\  — 마지막 역슬래시 뒤에 문자가 없다. 조용히 삼키거나 인덱스를 넘겨선 안 된다.
        assertThatThrownBy { lex("\"abc\\") }
            .isInstanceOf(AqlLexException::class.java)
            .asInstanceOf(InstanceOfAssertFactories.type(AqlLexException::class.java))
            .extracting(AqlLexException::position)
            .isEqualTo(0)
    }

    @Test
    fun `이스케이프된 따옴표로 끝나면 문자열이 닫히지 않은 것으로 본다`() {
        // text ~ "abc\"  — 끝의 \" 는 리터럴 따옴표이므로 닫는 따옴표가 없다.
        // 여는 따옴표는 인덱스 7.
        assertThatThrownBy { lex("text ~ \"abc\\\"") }
            .isInstanceOf(AqlLexException::class.java)
            .asInstanceOf(InstanceOfAssertFactories.type(AqlLexException::class.java))
            .extracting(AqlLexException::position)
            .isEqualTo(7)
    }
}

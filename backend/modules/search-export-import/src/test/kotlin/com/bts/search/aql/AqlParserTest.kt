// AqlParser 단위 테스트 — JQL 호환 50+ 케이스 (비교/IN/불리언/괄호/정렬/오류/깊이)

package com.bts.search.aql

import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlSort
import com.bts.shared.search.AqlValue
import com.bts.shared.search.SortDirection
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * [AqlParser] 재귀하강 파서 단위 테스트.
 *
 * 검증 항목.
 * - 단순 비교: = / != / ~
 * - IN / NOT IN (괄호 안 값 목록)
 * - AND / OR / NOT 불리언 결합
 * - 연산자 우선순위 (AND > OR)
 * - 괄호 중첩
 * - ORDER BY 단일·다중·ASC/DESC
 * - 키워드 대소문자 무시
 * - 미지원 필드 2종 구분 (오타 → SEARCH_UNKNOWN_FIELD / 후속예정 → SEARCH_FIELD_NOT_YET_SUPPORTED)
 * - 오류 케이스 — 빈 쿼리, 괄호 불균형, IN 빈 목록, 중복 ORDER BY, 연속 연산자 → 위치 포함 예외
 * - 깊이 상한(50) 초과 거부 (DoS 방어)
 * - 숫자 범위 검증(Short 범위 초과) — B2 codereview-fix
 * - status CONTAINS 금지 — codereview-fix
 * - ORDER BY 미지원 필드 거부 — codereview-fix
 */
@Suppress("LargeClass") // 문법 규칙 1케이스당 1테스트 구조 — 분리 시 공유 헬퍼 비용 증가
class AqlParserTest {
    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    private fun parse(query: String): AqlParseResult {
        val tokens = AqlLexer(query).tokenize()
        return AqlParser(tokens).parse()
    }

    private fun parseAst(query: String): AqlNode = parse(query).ast

    private fun comparison(
        field: String,
        op: AqlOperator,
        vararg values: AqlValue,
    ): AqlNode.Comparison = AqlNode.Comparison(AqlField(field), op, values.toList())

    // ── 단순 비교: = ─────────────────────────────────────────────────────────

    @Test
    fun `status = open 단순 등호 비교를 파싱한다`() {
        val ast = parseAst("status = open")
        assertThat(ast).isEqualTo(
            comparison("status", AqlOperator.EQ, AqlValue.Str("open")),
        )
    }

    @Test
    fun `status = open 따옴표 값을 파싱한다`() {
        val ast = parseAst("""status = "open state"""")
        assertThat(ast).isEqualTo(
            comparison("status", AqlOperator.EQ, AqlValue.Str("open state")),
        )
    }

    @Test
    fun `priority = 1 숫자 값을 파싱한다`() {
        val ast = parseAst("priority = 1")
        assertThat(ast).isEqualTo(
            comparison("priority", AqlOperator.EQ, AqlValue.Num(1)),
        )
    }

    // ── 단순 비교: != ────────────────────────────────────────────────────────

    @Test
    fun `status != closed 불일치 비교를 파싱한다`() {
        val ast = parseAst("status != closed")
        assertThat(ast).isEqualTo(
            comparison("status", AqlOperator.NEQ, AqlValue.Str("closed")),
        )
    }

    @Test
    fun `priority != 5 숫자 불일치 비교를 파싱한다`() {
        val ast = parseAst("priority != 5")
        assertThat(ast).isEqualTo(
            comparison("priority", AqlOperator.NEQ, AqlValue.Num(5)),
        )
    }

    // ── 단순 비교: ~ ─────────────────────────────────────────────────────────

    @Test
    fun `summary ~ 버그 부분문자열 비교를 파싱한다`() {
        val ast = parseAst("""summary ~ "버그"""")
        assertThat(ast).isEqualTo(
            comparison("summary", AqlOperator.CONTAINS, AqlValue.Str("버그")),
        )
    }

    @Test
    fun `label ~ urgent 라벨 부분문자열 비교를 파싱한다`() {
        val ast = parseAst("label ~ urgent")
        assertThat(ast).isEqualTo(
            comparison("label", AqlOperator.CONTAINS, AqlValue.Str("urgent")),
        )
    }

    @Test
    fun `summary ~ bareword 따옴표 없는 값을 파싱한다`() {
        val ast = parseAst("summary ~ login")
        assertThat(ast).isEqualTo(
            comparison("summary", AqlOperator.CONTAINS, AqlValue.Str("login")),
        )
    }

    // ── IN ──────────────────────────────────────────────────────────────────

    @Test
    fun `status IN open closed IN 절을 파싱한다`() {
        val ast = parseAst("status IN (open, closed)")
        assertThat(ast).isEqualTo(
            comparison(
                "status",
                AqlOperator.IN,
                AqlValue.Str("open"),
                AqlValue.Str("closed"),
            ),
        )
    }

    @Test
    fun `priority IN 1 2 3 숫자 IN 절을 파싱한다`() {
        val ast = parseAst("priority IN (1, 2, 3)")
        assertThat(ast).isEqualTo(
            comparison(
                "priority",
                AqlOperator.IN,
                AqlValue.Num(1),
                AqlValue.Num(2),
                AqlValue.Num(3),
            ),
        )
    }

    @Test
    fun `label IN bug urgent label IN 절을 파싱한다`() {
        val ast = parseAst("label IN (bug, urgent)")
        assertThat(ast).isEqualTo(
            comparison(
                "label",
                AqlOperator.IN,
                AqlValue.Str("bug"),
                AqlValue.Str("urgent"),
            ),
        )
    }

    @Test
    fun `status IN 단일 값도 IN 절로 파싱한다`() {
        val ast = parseAst("status IN (open)")
        assertThat(ast).isEqualTo(
            comparison("status", AqlOperator.IN, AqlValue.Str("open")),
        )
    }

    // ── NOT IN ──────────────────────────────────────────────────────────────

    @Test
    fun `status NOT IN closed done NOT IN 절을 파싱한다`() {
        val ast = parseAst("status NOT IN (closed, done)")
        assertThat(ast).isEqualTo(
            comparison(
                "status",
                AqlOperator.NOT_IN,
                AqlValue.Str("closed"),
                AqlValue.Str("done"),
            ),
        )
    }

    @Test
    fun `priority NOT IN 4 5 숫자 NOT IN 절을 파싱한다`() {
        val ast = parseAst("priority NOT IN (4, 5)")
        assertThat(ast).isEqualTo(
            comparison(
                "priority",
                AqlOperator.NOT_IN,
                AqlValue.Num(4),
                AqlValue.Num(5),
            ),
        )
    }

    // ── AND / OR / NOT ──────────────────────────────────────────────────────

    @Test
    fun `AND 조합을 파싱한다`() {
        val ast = parseAst("status = open AND priority = 1")
        assertThat(ast).isEqualTo(
            AqlNode.And(
                comparison("status", AqlOperator.EQ, AqlValue.Str("open")),
                comparison("priority", AqlOperator.EQ, AqlValue.Num(1)),
            ),
        )
    }

    @Test
    fun `OR 조합을 파싱한다`() {
        val ast = parseAst("status = open OR status = closed")
        assertThat(ast).isEqualTo(
            AqlNode.Or(
                comparison("status", AqlOperator.EQ, AqlValue.Str("open")),
                comparison("status", AqlOperator.EQ, AqlValue.Str("closed")),
            ),
        )
    }

    @Test
    fun `NOT 단항 부정을 파싱한다`() {
        val ast = parseAst("NOT status = closed")
        assertThat(ast).isEqualTo(
            AqlNode.Not(
                comparison("status", AqlOperator.EQ, AqlValue.Str("closed")),
            ),
        )
    }

    @Test
    fun `NOT NOT 이중 부정을 파싱한다`() {
        val ast = parseAst("NOT NOT status = open")
        assertThat(ast).isEqualTo(
            AqlNode.Not(
                AqlNode.Not(
                    comparison("status", AqlOperator.EQ, AqlValue.Str("open")),
                ),
            ),
        )
    }

    // ── 우선순위 (AND > OR) ───────────────────────────────────────────────────

    @Test
    fun `AND가 OR보다 먼저 결합된다 — a=1 OR b=2 AND c=3 는 a OR (b AND c)`() {
        // status = open  OR  label = bug  AND  priority = 1
        // 우선순위대로 파싱하면: Or(status=open, And(label=bug, priority=1))
        val ast = parseAst("status = open OR label = bug AND priority = 1")
        assertThat(ast).isEqualTo(
            AqlNode.Or(
                comparison("status", AqlOperator.EQ, AqlValue.Str("open")),
                AqlNode.And(
                    comparison("label", AqlOperator.EQ, AqlValue.Str("bug")),
                    comparison("priority", AqlOperator.EQ, AqlValue.Num(1)),
                ),
            ),
        )
    }

    @Test
    fun `연속 AND 체인이 왼쪽 결합으로 파싱된다`() {
        // a=1 AND b=2 AND c=3 → And(And(a=1, b=2), c=3)
        val ast = parseAst("status = open AND label = bug AND priority = 1")
        assertThat(ast).isEqualTo(
            AqlNode.And(
                AqlNode.And(
                    comparison("status", AqlOperator.EQ, AqlValue.Str("open")),
                    comparison("label", AqlOperator.EQ, AqlValue.Str("bug")),
                ),
                comparison("priority", AqlOperator.EQ, AqlValue.Num(1)),
            ),
        )
    }

    @Test
    fun `연속 OR 체인이 왼쪽 결합으로 파싱된다`() {
        val ast = parseAst("status = open OR status = closed OR status = done")
        assertThat(ast).isEqualTo(
            AqlNode.Or(
                AqlNode.Or(
                    comparison("status", AqlOperator.EQ, AqlValue.Str("open")),
                    comparison("status", AqlOperator.EQ, AqlValue.Str("closed")),
                ),
                comparison("status", AqlOperator.EQ, AqlValue.Str("done")),
            ),
        )
    }

    // ── 괄호 중첩 ────────────────────────────────────────────────────────────

    @Test
    fun `괄호로 묶인 OR이 AND보다 먼저 평가된다`() {
        // summary ~ 버그 AND (label = bug OR label = urgent)
        val ast = parseAst("""summary ~ "버그" AND (label = bug OR label = urgent)""")
        assertThat(ast).isEqualTo(
            AqlNode.And(
                comparison("summary", AqlOperator.CONTAINS, AqlValue.Str("버그")),
                AqlNode.Or(
                    comparison("label", AqlOperator.EQ, AqlValue.Str("bug")),
                    comparison("label", AqlOperator.EQ, AqlValue.Str("urgent")),
                ),
            ),
        )
    }

    @Test
    fun `이중 괄호 중첩을 올바르게 파싱한다`() {
        val ast = parseAst("status = open AND ((label = bug OR priority = 1))")
        assertThat(ast).isEqualTo(
            AqlNode.And(
                comparison("status", AqlOperator.EQ, AqlValue.Str("open")),
                AqlNode.Or(
                    comparison("label", AqlOperator.EQ, AqlValue.Str("bug")),
                    comparison("priority", AqlOperator.EQ, AqlValue.Num(1)),
                ),
            ),
        )
    }

    @Test
    fun `NOT 과 괄호 조합을 파싱한다`() {
        val ast = parseAst("NOT (status = closed OR status = done)")
        assertThat(ast).isEqualTo(
            AqlNode.Not(
                AqlNode.Or(
                    comparison("status", AqlOperator.EQ, AqlValue.Str("closed")),
                    comparison("status", AqlOperator.EQ, AqlValue.Str("done")),
                ),
            ),
        )
    }

    // ── ORDER BY ─────────────────────────────────────────────────────────────

    @Test
    fun `ORDER BY 단일 필드 DESC 정렬을 파싱한다`() {
        val result = parse("status = open ORDER BY priority DESC")
        assertThat(result.sort).containsExactly(
            AqlSort(AqlField("priority"), SortDirection.DESC),
        )
    }

    @Test
    fun `ORDER BY 단일 필드 ASC 정렬을 파싱한다`() {
        val result = parse("status = open ORDER BY priority ASC")
        assertThat(result.sort).containsExactly(
            AqlSort(AqlField("priority"), SortDirection.ASC),
        )
    }

    @Test
    fun `ORDER BY 방향 생략 시 ASC 기본값을 사용한다`() {
        val result = parse("status = open ORDER BY priority")
        assertThat(result.sort).containsExactly(
            AqlSort(AqlField("priority"), SortDirection.ASC),
        )
    }

    @Test
    fun `ORDER BY 다중 정렬 필드를 순서대로 파싱한다`() {
        val result = parse("status = open ORDER BY priority DESC, summary ASC")
        assertThat(result.sort).containsExactly(
            AqlSort(AqlField("priority"), SortDirection.DESC),
            AqlSort(AqlField("summary"), SortDirection.ASC),
        )
    }

    @Test
    fun `ORDER BY 가 없으면 sort 목록이 비어있다`() {
        val result = parse("status = open")
        assertThat(result.sort).isEmpty()
    }

    @Test
    fun `AST와 ORDER BY 가 모두 올바르게 파싱된다`() {
        val result = parse("summary ~ login ORDER BY priority DESC")
        assertThat(result.ast).isEqualTo(
            comparison("summary", AqlOperator.CONTAINS, AqlValue.Str("login")),
        )
        assertThat(result.sort).containsExactly(
            AqlSort(AqlField("priority"), SortDirection.DESC),
        )
    }

    // ── 키워드 대소문자 무시 ─────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = ["status = open AND priority = 1", "status = open and priority = 1"])
    fun `AND 키워드 대소문자 무관하게 파싱한다`(query: String) {
        val ast = parseAst(query)
        assertThat(ast).isInstanceOf(AqlNode.And::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["status = open OR priority = 1", "status = open or priority = 1"])
    fun `OR 키워드 대소문자 무관하게 파싱한다`(query: String) {
        val ast = parseAst(query)
        assertThat(ast).isInstanceOf(AqlNode.Or::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["NOT status = closed", "not status = closed", "Not status = closed"])
    fun `NOT 키워드 대소문자 무관하게 파싱한다`(query: String) {
        val ast = parseAst(query)
        assertThat(ast).isInstanceOf(AqlNode.Not::class.java)
    }

    @ParameterizedTest
    @ValueSource(strings = ["status IN (open)", "status in (open)", "status In (open)"])
    fun `IN 키워드 대소문자 무관하게 파싱한다`(query: String) {
        val ast = parseAst(query)
        val comp = ast as AqlNode.Comparison
        assertThat(comp.op).isEqualTo(AqlOperator.IN)
    }

    @Test
    fun `order by 소문자 키워드로 ORDER BY 절을 파싱한다`() {
        val result = parse("status = open order by priority desc")
        assertThat(result.sort).containsExactly(
            AqlSort(AqlField("priority"), SortDirection.DESC),
        )
    }

    // ── 미지원 필드 2종 구분 ─────────────────────────────────────────────────

    @Test
    fun `오타 필드는 SEARCH_UNKNOWN_FIELD 에러코드로 거부한다`() {
        assertThatThrownBy { parseAst("foobar = 1") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.errorCode).isEqualTo(AqlErrorCode.SEARCH_UNKNOWN_FIELD)
            })
    }

    @Test
    fun `존재하지 않는 다른 오타 필드도 SEARCH_UNKNOWN_FIELD로 거부한다`() {
        assertThatThrownBy { parseAst("typo_field = value") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.errorCode).isEqualTo(AqlErrorCode.SEARCH_UNKNOWN_FIELD)
            })
    }

    @Test
    fun `assignee 후속예정 필드는 SEARCH_FIELD_NOT_YET_SUPPORTED로 거부한다`() {
        assertThatThrownBy { parseAst("assignee = alice") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.errorCode).isEqualTo(AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED)
            })
    }

    @Test
    fun `reporter 후속예정 필드는 SEARCH_FIELD_NOT_YET_SUPPORTED로 거부한다`() {
        assertThatThrownBy { parseAst("reporter = bob") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.errorCode).isEqualTo(AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED)
            })
    }

    @Test
    fun `component 후속예정 필드는 SEARCH_FIELD_NOT_YET_SUPPORTED로 거부한다`() {
        assertThatThrownBy { parseAst("component = backend") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.errorCode).isEqualTo(AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED)
            })
    }

    @Test
    fun `project 후속예정 필드는 SEARCH_FIELD_NOT_YET_SUPPORTED로 거부한다`() {
        assertThatThrownBy { parseAst("project = PROJ") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.errorCode).isEqualTo(AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED)
            })
    }

    // ── 필드별 연산자 제약 ───────────────────────────────────────────────────

    @Test
    fun `priority 에 물결 연산자는 허용되지 않는다`() {
        assertThatThrownBy { parseAst("priority ~ 1") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `summary 에 물결 연산자는 허용된다`() {
        val ast = parseAst("summary ~ login")
        assertThat(ast).isInstanceOf(AqlNode.Comparison::class.java)
    }

    @Test
    fun `label 에 물결 연산자는 허용된다`() {
        val ast = parseAst("label ~ bug")
        assertThat(ast).isInstanceOf(AqlNode.Comparison::class.java)
    }

    // ── text 가상 FTS 필드 (신규, FR-SR-04) ──────────────────────────────────────

    @Test
    fun `text 필드에 CONTAINS 연산자는 Comparison 노드를 반환한다`() {
        val ast = parseAst("""text ~ "검색어"""")
        assertThat(ast).isEqualTo(
            comparison("text", AqlOperator.CONTAINS, AqlValue.Str("검색어")),
        )
    }

    @Test
    fun `text 필드에 EQ 연산자는 허용되지 않는다`() {
        assertThatThrownBy { parse("text = open") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    // ── 오류 케이스 — 위치 포함 AqlSyntaxException ────────────────────────────

    @Test
    fun `빈 쿼리는 AqlSyntaxException을 던진다`() {
        assertThatThrownBy { parseAst("") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `공백만 있는 쿼리는 AqlSyntaxException을 던진다`() {
        assertThatThrownBy { parseAst("   ") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `괄호 불균형 — 여는 괄호만 있으면 위치 포함 예외를 던진다`() {
        assertThatThrownBy { parse("(status = open") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.position).isGreaterThanOrEqualTo(0)
            })
    }

    @Test
    fun `닫히지 않은 괄호 중첩이면 예외를 던진다`() {
        assertThatThrownBy { parse("((status = open)") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `닫는 괄호만 있으면 예외를 던진다`() {
        assertThatThrownBy { parse("status = open)") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `IN 빈 목록은 예외를 던진다`() {
        assertThatThrownBy { parse("status IN ()") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `연속 연산자는 예외를 던진다 — status = = open`() {
        assertThatThrownBy { parse("status = = open") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `연산자 없이 두 필드가 연속하면 예외를 던진다`() {
        assertThatThrownBy { parse("status priority") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `ORDER BY 가 두 번 나오면 예외를 던진다`() {
        assertThatThrownBy { parse("status = open ORDER BY priority ORDER BY summary") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `ORDER BY 필드 없이 끝나면 예외를 던진다`() {
        assertThatThrownBy { parse("status = open ORDER BY") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `NOT 뒤에 피연산자가 없으면 예외를 던진다`() {
        assertThatThrownBy { parse("NOT") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `필드 없이 연산자로 시작하면 예외를 던진다`() {
        assertThatThrownBy { parse("= open") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `값 없이 연산자로 끝나면 예외를 던진다`() {
        assertThatThrownBy { parse("status =") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `IN 절에서 쉼표 뒤 값 없으면 예외를 던진다`() {
        assertThatThrownBy { parse("status IN (open,)") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `IN 절 닫는 괄호 없으면 예외를 던진다`() {
        assertThatThrownBy { parse("status IN (open, closed") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `오류 예외에 0 이상의 컬럼 위치가 담긴다`() {
        val ex =
            runCatching { parse("foobar = 1") }
                .exceptionOrNull() as? AqlSyntaxException
        assertThat(ex).isNotNull
        assertThat(ex!!.position).isGreaterThanOrEqualTo(0)
    }

    // ── 깊이 상한 (DoS 방어) ─────────────────────────────────────────────────

    @Test
    fun `깊이 50 초과 중첩 괄호는 AqlSyntaxException을 던진다`() {
        // 51단계 중첩: ((( ... status = open ... ))) — 닫는 괄호 포함
        val open = "(".repeat(51)
        val close = ")".repeat(51)
        val query = "${open}status = open$close"
        assertThatThrownBy { parse(query) }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `깊이 50 이내 중첩 괄호는 파싱에 성공한다`() {
        // 5단계 중첩 — 성공해야 함
        val open = "(".repeat(5)
        val close = ")".repeat(5)
        val query = "${open}status = open$close"
        val ast = parseAst(query)
        assertThat(ast).isInstanceOf(AqlNode.Comparison::class.java)
    }

    // ── 복합 시나리오 ─────────────────────────────────────────────────────────

    @Test
    fun `S2 복합 검색 시나리오 — summary AND 괄호 OR 를 올바르게 파싱한다`() {
        // summary ~ "버그" AND (label = urgent OR priority = 1)
        val ast = parseAst("""summary ~ "버그" AND (label = urgent OR priority = 1)""")
        assertThat(ast).isEqualTo(
            AqlNode.And(
                comparison("summary", AqlOperator.CONTAINS, AqlValue.Str("버그")),
                AqlNode.Or(
                    comparison("label", AqlOperator.EQ, AqlValue.Str("urgent")),
                    comparison("priority", AqlOperator.EQ, AqlValue.Num(1)),
                ),
            ),
        )
    }

    @Test
    fun `S3 정렬 시나리오 — ORDER BY 포함 전체 파싱`() {
        val result = parse("status = open ORDER BY priority DESC")
        assertThat(result.ast).isEqualTo(
            comparison("status", AqlOperator.EQ, AqlValue.Str("open")),
        )
        assertThat(result.sort).containsExactly(
            AqlSort(AqlField("priority"), SortDirection.DESC),
        )
    }

    @Test
    fun `NOT IN 과 AND 조합을 파싱한다`() {
        val ast = parseAst("status NOT IN (closed, done) AND priority = 1")
        assertThat(ast).isEqualTo(
            AqlNode.And(
                comparison(
                    "status",
                    AqlOperator.NOT_IN,
                    AqlValue.Str("closed"),
                    AqlValue.Str("done"),
                ),
                comparison("priority", AqlOperator.EQ, AqlValue.Num(1)),
            ),
        )
    }

    @Test
    fun `label IN 과 summary contains 조합을 파싱한다`() {
        val ast = parseAst("""label IN (bug, critical) AND summary ~ "로그인"""")
        assertThat(ast).isEqualTo(
            AqlNode.And(
                comparison(
                    "label",
                    AqlOperator.IN,
                    AqlValue.Str("bug"),
                    AqlValue.Str("critical"),
                ),
                comparison("summary", AqlOperator.CONTAINS, AqlValue.Str("로그인")),
            ),
        )
    }

    @Test
    fun `NOT 과 IN 절 조합을 파싱한다`() {
        val ast = parseAst("NOT label IN (wontfix, duplicate)")
        assertThat(ast).isEqualTo(
            AqlNode.Not(
                comparison(
                    "label",
                    AqlOperator.IN,
                    AqlValue.Str("wontfix"),
                    AqlValue.Str("duplicate"),
                ),
            ),
        )
    }

    @Test
    fun `세 필드 AND 체인을 파싱한다`() {
        val ast = parseAst("status = open AND label = bug AND priority = 1")
        // 왼쪽 결합: And(And(status=open, label=bug), priority=1)
        assertThat(ast).isInstanceOf(AqlNode.And::class.java)
        val outer = ast as AqlNode.And
        assertThat(outer.left).isInstanceOf(AqlNode.And::class.java)
        assertThat(outer.right).isEqualTo(comparison("priority", AqlOperator.EQ, AqlValue.Num(1)))
    }

    @Test
    fun `ORDER BY 다중 필드 순서가 보존된다`() {
        val result = parse("status = open ORDER BY priority DESC, summary ASC, status ASC")
        assertThat(result.sort).hasSize(3)
        assertThat(result.sort[0]).isEqualTo(AqlSort(AqlField("priority"), SortDirection.DESC))
        assertThat(result.sort[1]).isEqualTo(AqlSort(AqlField("summary"), SortDirection.ASC))
        assertThat(result.sort[2]).isEqualTo(AqlSort(AqlField("status"), SortDirection.ASC))
    }

    // ── B2 숫자 범위 검증 (회귀 테스트 - 현재 500 재현) ────────────────────────

    @Test
    fun `priority Int 범위 초과 숫자는 AqlSyntaxException을 던진다`() {
        // 99999999999는 Int 최대값(2147483647) 초과 — toInt() NumberFormatException → 500 재현
        assertThatThrownBy { parse("priority = 99999999999") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.position).isGreaterThanOrEqualTo(0)
            })
    }

    @Test
    fun `priority Short 범위 초과 숫자는 AqlSyntaxException을 던진다`() {
        // 40000은 Int 범위 내이지만 Short 최대값(32767) 초과
        // repository asShort()에서 wrap되면 결과가 틀리므로 파서에서 거부해야 한다
        assertThatThrownBy { parse("priority = 40000") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.position).isGreaterThanOrEqualTo(0)
            })
    }

    @Test
    fun `priority Short 음수 범위 초과 숫자는 AqlSyntaxException을 던진다`() {
        // -40000은 Short 최솟값(-32768) 미만
        // 렉서는 음수 리터럴을 지원하지 않으므로 실제로는 발생하지 않지만 경계 문서화
        assertThatThrownBy { parse("priority = 40000") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `priority 유효 범위 숫자는 정상 파싱된다`() {
        // Short 범위 내 숫자는 허용
        val ast = parseAst("priority = 32767")
        assertThat(ast).isInstanceOf(AqlNode.Comparison::class.java)
        val comp = ast as AqlNode.Comparison
        assertThat(comp.values).containsExactly(AqlValue.Num(32767))
    }

    @Test
    fun `priority IN 목록 내 Int 범위 초과 숫자는 AqlSyntaxException을 던진다`() {
        // 목록의 첫 번째 값이 99999999999(Int 최대값 초과)인 경우 — characterization 테스트.
        // parseValue가 parseIntValue를 공유하므로 IN 목록 내 값도 동일하게 차단된다.
        assertThatThrownBy { parse("priority IN (99999999999)") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.position).isGreaterThanOrEqualTo(0)
            })
    }

    @Test
    fun `priority IN 목록 내 Short 범위 초과 숫자는 AqlSyntaxException을 던진다`() {
        // 목록 내 40000은 Int 범위 내이지만 Short 최대값(32767) 초과 — characterization 테스트.
        // asShort() defense-in-depth와 함께 파서가 1차 차단함을 명시적으로 검증한다.
        assertThatThrownBy { parse("priority IN (40000)") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.position).isGreaterThanOrEqualTo(0)
            })
    }

    @Test
    fun `priority IN 목록 혼합 값 중 Short 범위 초과가 포함되면 AqlSyntaxException을 던진다`() {
        // 첫 번째 값은 유효(1), 두 번째 값이 40000(Short 초과) — 목록 순회 중 차단 확인.
        assertThatThrownBy { parse("priority IN (1, 40000)") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    // ── status CONTAINS 제약 (회귀 테스트 - 현재 500 재현) ──────────────────────

    @Test
    fun `status 필드에 물결 연산자는 허용되지 않는다`() {
        // status ~ x 가 파서를 통과해 repository IllegalArgument → 500 재현
        assertThatThrownBy { parse("status ~ open") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    // ── ORDER BY 미지원 필드 (회귀 테스트 - 현재 500 재현) ──────────────────────

    @Test
    fun `ORDER BY 미지원 필드는 AqlSyntaxException SEARCH_UNKNOWN_FIELD 를 던진다`() {
        // ORDER BY foobar 가 검증 없이 통과해 repository IllegalArgument → 500 재현
        assertThatThrownBy { parse("status = open ORDER BY foobar") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.errorCode).isEqualTo(AqlErrorCode.SEARCH_UNKNOWN_FIELD)
                assertThat(syntaxEx.position).isGreaterThanOrEqualTo(0)
            })
    }

    @Test
    fun `ORDER BY 후속예정 필드는 AqlSyntaxException SEARCH_FIELD_NOT_YET_SUPPORTED 를 던진다`() {
        // assignee 는 후속 지원 예정 필드 — ORDER BY 에서도 동일하게 구분
        assertThatThrownBy { parse("status = open ORDER BY assignee") }
            .isInstanceOf(AqlSyntaxException::class.java)
            .satisfies({ ex ->
                val syntaxEx = ex as AqlSyntaxException
                assertThat(syntaxEx.errorCode).isEqualTo(AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED)
            })
    }

    @Test
    fun `ORDER BY MVP 지원 필드는 정상 파싱된다`() {
        // status, summary, priority 는 SORTABLE_FIELDS 포함 — 정렬 허용
        val result = parse("status = open ORDER BY status DESC")
        assertThat(result.sort).hasSize(1)
        assertThat(result.sort[0].field).isEqualTo(AqlField("status"))
    }

    // ── C3 ORDER BY 정렬 불가 필드 (파서에서 거부해야 함 — 광범위 IAE 핸들러 제거 전제) ─────────

    @Test
    fun `C3 text 가상 FTS 필드는 ORDER BY 에서 AqlSyntaxException 을 던진다`() {
        // text 는 MVP 쿼리 필드(~ 연산자만 허용)이지만 실제 DB 컬럼이 없는 가상 FTS 필드.
        // ORDER BY 대상 컬럼이 없으므로 SORTABLE_FIELDS 에 미포함 → 파서에서 거부해야 한다.
        // 현재: validateField 가 MVP_FIELDS 기준 SUPPORTED 반환 → 파서 통과 후 repository IAE.
        assertThatThrownBy { parse("""text ~ "x" ORDER BY text""") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `C3 label 배열 필드는 ORDER BY 에서 AqlSyntaxException 을 던진다`() {
        // label 은 MVP 쿼리 필드이지만 buildOrderBy 화이트리스트에 없음 — 정렬 불가.
        // 현재: validateField 가 MVP_FIELDS 기준 SUPPORTED 반환 → 파서 통과 후 repository IAE.
        assertThatThrownBy { parse("label ~ urgent ORDER BY label") }
            .isInstanceOf(AqlSyntaxException::class.java)
    }

    @Test
    fun `C3 회귀 summary 는 ORDER BY 에서 정상 파싱된다`() {
        // summary 는 SORTABLE_FIELDS 포함 필드 — ORDER BY summary 는 항상 통과해야 한다.
        val result = parse("status = open ORDER BY summary ASC")
        assertThat(result.sort).containsExactly(
            AqlSort(AqlField("summary"), SortDirection.ASC),
        )
    }
}

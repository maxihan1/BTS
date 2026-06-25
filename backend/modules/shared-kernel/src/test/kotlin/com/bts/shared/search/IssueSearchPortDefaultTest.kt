// IssueSearchPort default(fail-safe) 동작 + AQL AST 데이터클래스 구조 단위 테스트

package com.bts.shared.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [IssueSearchPort] default 구현과 AQL AST 데이터클래스 구조를 검증하는 단위 테스트.
 *
 * ### 테스트 목적
 *
 * 1. 어댑터가 등록되지 않은 환경에서 [IssueSearchPort] 의 default 구현이
 *    데이터 누출 없이 빈 [IssueSearchPage] 를 반환하는지 확인한다.
 * 2. [AqlNode], [AqlSort], [IssueSearchQuery], [IssueSearchHit] 등 AST
 *    데이터클래스가 올바르게 조립·분해되는지 구조적으로 검증한다.
 */
class IssueSearchPortDefaultTest {
    // ──────────────────────────────────────────────────────────────────
    // 1. IssueSearchPort default: fail-safe 빈 페이지 반환
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `default implementation returns empty page without data leakage`() {
        // 어댑터 미등록 상황을 재현 — 인터페이스의 default 메서드만 사용
        val port: IssueSearchPort = object : IssueSearchPort {}
        val query =
            buildQuery(
                ast =
                    AqlNode.Comparison(
                        field = AqlField("status"),
                        op = AqlOperator.EQ,
                        values = listOf(AqlValue.Str("open")),
                    ),
            )

        val result = port.search(query)

        assertThat(result.items).isEmpty()
        assertThat(result.total).isEqualTo(0L)
        assertThat(result.page).isEqualTo(query.page)
        assertThat(result.size).isEqualTo(query.size)
    }

    @Test
    fun `default implementation preserves page and size from query`() {
        val port: IssueSearchPort = object : IssueSearchPort {}
        val query =
            buildQuery(
                ast =
                    AqlNode.Comparison(
                        field = AqlField("priority"),
                        op = AqlOperator.EQ,
                        values = listOf(AqlValue.Num(1)),
                    ),
                page = 3,
                size = 25,
            )

        val result = port.search(query)

        assertThat(result.page).isEqualTo(3)
        assertThat(result.size).isEqualTo(25)
    }

    @Test
    fun `IssueSearchPage empty companion returns zero total and empty items`() {
        val page = IssueSearchPage.empty(page = 0, size = 50)

        assertThat(page.items).isEmpty()
        assertThat(page.total).isEqualTo(0L)
        assertThat(page.page).isEqualTo(0)
        assertThat(page.size).isEqualTo(50)
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. AqlNode AST 구조 조립 검증
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `AqlNode Comparison is assembled correctly`() {
        val node =
            AqlNode.Comparison(
                field = AqlField("summary"),
                op = AqlOperator.CONTAINS,
                values = listOf(AqlValue.Str("버그")),
            )

        assertThat(node.field.value).isEqualTo("summary")
        assertThat(node.op).isEqualTo(AqlOperator.CONTAINS)
        assertThat(node.values).hasSize(1)
        assertThat((node.values[0] as AqlValue.Str).value).isEqualTo("버그")
    }

    @Test
    fun `AqlNode In comparison holds multiple values`() {
        val node =
            AqlNode.Comparison(
                field = AqlField("label"),
                op = AqlOperator.IN,
                values = listOf(AqlValue.Str("bug"), AqlValue.Str("urgent")),
            )

        assertThat(node.op).isEqualTo(AqlOperator.IN)
        assertThat(node.values).hasSize(2)
    }

    @Test
    fun `AqlNode And chains left and right subtrees`() {
        val left =
            AqlNode.Comparison(
                field = AqlField("status"),
                op = AqlOperator.EQ,
                values = listOf(AqlValue.Str("open")),
            )
        val right =
            AqlNode.Comparison(
                field = AqlField("priority"),
                op = AqlOperator.EQ,
                values = listOf(AqlValue.Num(1)),
            )
        val and = AqlNode.And(left = left, right = right)

        assertThat(and.left).isEqualTo(left)
        assertThat(and.right).isEqualTo(right)
    }

    @Test
    fun `AqlNode Or chains left and right subtrees`() {
        val left = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("bug")))
        val right = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("urgent")))
        val or = AqlNode.Or(left = left, right = right)

        assertThat(or.left).isEqualTo(left)
        assertThat(or.right).isEqualTo(right)
    }

    @Test
    fun `AqlNode Not wraps a child node`() {
        val inner = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("closed")))
        val not = AqlNode.Not(child = inner)

        assertThat(not.child).isEqualTo(inner)
    }

    @Test
    fun `AqlNode deep tree assembles without error`() {
        // AND(OR(A, B), NOT(C)) 형태의 복합 트리
        val a = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val b = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("bug")))
        val c = AqlNode.Comparison(AqlField("priority"), AqlOperator.IN, listOf(AqlValue.Num(1), AqlValue.Num(2)))

        val tree =
            AqlNode.And(
                left = AqlNode.Or(left = a, right = b),
                right = AqlNode.Not(child = c),
            )

        assertThat(tree).isInstanceOf(AqlNode.And::class.java)
        assertThat(tree.left).isInstanceOf(AqlNode.Or::class.java)
        assertThat(tree.right).isInstanceOf(AqlNode.Not::class.java)
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. AqlValue sealed 계층 — 문자열/정수 구분
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `AqlValue Str holds string literal`() {
        val v = AqlValue.Str("open")
        assertThat(v.value).isEqualTo("open")
    }

    @Test
    fun `AqlValue Num holds integer literal`() {
        val v = AqlValue.Num(42)
        assertThat(v.value).isEqualTo(42)
    }

    @Test
    fun `AqlOperator covers all required operators`() {
        val ops = AqlOperator.entries.map { it.name }.toSet()
        assertThat(ops).contains("EQ", "NEQ", "CONTAINS", "IN", "NOT_IN")
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. AqlSort 구조 검증
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `AqlSort holds field and direction`() {
        val sort = AqlSort(field = AqlField("priority"), direction = SortDirection.DESC)

        assertThat(sort.field.value).isEqualTo("priority")
        assertThat(sort.direction).isEqualTo(SortDirection.DESC)
    }

    @Test
    fun `SortDirection has ASC and DESC`() {
        assertThat(SortDirection.valueOf("ASC")).isEqualTo(SortDirection.ASC)
        assertThat(SortDirection.valueOf("DESC")).isEqualTo(SortDirection.DESC)
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. IssueSearchQuery 커맨드 객체 구조 검증
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `IssueSearchQuery holds all required fields`() {
        val viewerUserId = UUID.randomUUID()
        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val sort = listOf(AqlSort(AqlField("priority"), SortDirection.ASC))

        val query =
            IssueSearchQuery(
                projectKey = "PROJ",
                ast = ast,
                sort = sort,
                viewerUserId = viewerUserId,
                page = 0,
                size = 50,
            )

        assertThat(query.projectKey).isEqualTo("PROJ")
        assertThat(query.ast).isEqualTo(ast)
        assertThat(query.sort).isEqualTo(sort)
        assertThat(query.viewerUserId).isEqualTo(viewerUserId)
        assertThat(query.page).isEqualTo(0)
        assertThat(query.size).isEqualTo(50)
    }

    @Test
    fun `IssueSearchQuery supports empty sort list`() {
        val query =
            buildQuery(
                ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open"))),
            )
        assertThat(query.sort).isEmpty()
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. IssueSearchHit 구조 검증 — assigneeId nullable 포함
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `IssueSearchHit holds all required fields`() {
        val assigneeId = UUID.randomUUID()
        val now = Instant.now()
        val hit =
            IssueSearchHit(
                key = "PROJ-1",
                summary = "로그인 버그",
                typeKey = "bug",
                currentStateKey = "open",
                assigneeId = assigneeId,
                priority = 1,
                priorityName = "Critical",
                projectKey = "PROJ",
                updatedAt = now,
            )

        assertThat(hit.key).isEqualTo("PROJ-1")
        assertThat(hit.summary).isEqualTo("로그인 버그")
        assertThat(hit.typeKey).isEqualTo("bug")
        assertThat(hit.currentStateKey).isEqualTo("open")
        assertThat(hit.assigneeId).isEqualTo(assigneeId)
        assertThat(hit.priority).isEqualTo(1)
        assertThat(hit.priorityName).isEqualTo("Critical")
        assertThat(hit.projectKey).isEqualTo("PROJ")
        assertThat(hit.updatedAt).isEqualTo(now)
    }

    @Test
    fun `IssueSearchHit assigneeId is nullable`() {
        val hit =
            IssueSearchHit(
                key = "PROJ-2",
                summary = "미배정 이슈",
                typeKey = "task",
                currentStateKey = "open",
                assigneeId = null,
                priority = 3,
                priorityName = "Medium",
                projectKey = "PROJ",
                updatedAt = Instant.now(),
            )

        assertThat(hit.assigneeId).isNull()
    }

    // ──────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────

    private fun buildQuery(
        ast: AqlNode,
        page: Int = 0,
        size: Int = 50,
    ): IssueSearchQuery =
        IssueSearchQuery(
            projectKey = "TEST",
            ast = ast,
            sort = emptyList(),
            viewerUserId = UUID.randomUUID(),
            page = page,
            size = size,
        )
}

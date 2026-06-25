// IssueSearchPort default(fail-safe) 동작 + AQL AST 데이터클래스 구조 단위 테스트

package com.bts.shared.search

import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val query = buildQuery(
            ast = AqlNode.Comparison(
                field = AqlField("status"),
                op = AqlOperator.EQ,
                values = listOf(AqlValue.Str("open")),
            ),
        )

        val result = port.search(query)

        assertTrue(result.items.isEmpty(), "어댑터 미등록 시 이슈 목록이 비어야 한다")
        assertEquals(0L, result.total, "어댑터 미등록 시 total 이 0 이어야 한다")
        assertEquals(query.page, result.page, "요청 page 가 응답에 그대로 반영되어야 한다")
        assertEquals(query.size, result.size, "요청 size 가 응답에 그대로 반영되어야 한다")
    }

    @Test
    fun `default implementation preserves page and size from query`() {
        val port: IssueSearchPort = object : IssueSearchPort {}
        val query = buildQuery(
            ast = AqlNode.Comparison(
                field = AqlField("priority"),
                op = AqlOperator.EQ,
                values = listOf(AqlValue.Num(1)),
            ),
            page = 3,
            size = 25,
        )

        val result = port.search(query)

        assertEquals(3, result.page)
        assertEquals(25, result.size)
    }

    @Test
    fun `IssueSearchPage empty companion returns zero total and empty items`() {
        val page = IssueSearchPage.empty(page = 0, size = 50)

        assertTrue(page.items.isEmpty())
        assertEquals(0L, page.total)
        assertEquals(0, page.page)
        assertEquals(50, page.size)
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. AqlNode AST 구조 조립 검증
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `AqlNode Comparison is assembled correctly`() {
        val node = AqlNode.Comparison(
            field = AqlField("summary"),
            op = AqlOperator.CONTAINS,
            values = listOf(AqlValue.Str("버그")),
        )

        assertEquals("summary", node.field.value)
        assertEquals(AqlOperator.CONTAINS, node.op)
        assertEquals(1, node.values.size)
        assertEquals("버그", (node.values[0] as AqlValue.Str).value)
    }

    @Test
    fun `AqlNode In comparison holds multiple values`() {
        val node = AqlNode.Comparison(
            field = AqlField("label"),
            op = AqlOperator.IN,
            values = listOf(AqlValue.Str("bug"), AqlValue.Str("urgent")),
        )

        assertEquals(AqlOperator.IN, node.op)
        assertEquals(2, node.values.size)
    }

    @Test
    fun `AqlNode And chains left and right subtrees`() {
        val left = AqlNode.Comparison(
            field = AqlField("status"),
            op = AqlOperator.EQ,
            values = listOf(AqlValue.Str("open")),
        )
        val right = AqlNode.Comparison(
            field = AqlField("priority"),
            op = AqlOperator.EQ,
            values = listOf(AqlValue.Num(1)),
        )
        val and = AqlNode.And(left = left, right = right)

        assertEquals(left, and.left)
        assertEquals(right, and.right)
    }

    @Test
    fun `AqlNode Or chains left and right subtrees`() {
        val left = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("bug")))
        val right = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("urgent")))
        val or = AqlNode.Or(left = left, right = right)

        assertEquals(left, or.left)
        assertEquals(right, or.right)
    }

    @Test
    fun `AqlNode Not wraps a child node`() {
        val inner = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("closed")))
        val not = AqlNode.Not(child = inner)

        assertEquals(inner, not.child)
    }

    @Test
    fun `AqlNode deep tree assembles without error`() {
        // AND(OR(A, B), NOT(C)) 형태의 복합 트리
        val a = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val b = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("bug")))
        val c = AqlNode.Comparison(AqlField("priority"), AqlOperator.IN, listOf(AqlValue.Num(1), AqlValue.Num(2)))

        val tree = AqlNode.And(
            left = AqlNode.Or(left = a, right = b),
            right = AqlNode.Not(child = c),
        )

        // 타입 검증
        assertTrue(tree is AqlNode.And)
        assertTrue(tree.left is AqlNode.Or)
        assertTrue(tree.right is AqlNode.Not)
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. AqlValue sealed 계층 — 문자열/정수 구분
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `AqlValue Str holds string literal`() {
        val v = AqlValue.Str("open")
        assertEquals("open", v.value)
    }

    @Test
    fun `AqlValue Num holds integer literal`() {
        val v = AqlValue.Num(42)
        assertEquals(42, v.value)
    }

    @Test
    fun `AqlOperator covers all required operators`() {
        val ops = AqlOperator.entries.map { it.name }.toSet()
        assertTrue(ops.contains("EQ"))
        assertTrue(ops.contains("NEQ"))
        assertTrue(ops.contains("CONTAINS"))
        assertTrue(ops.contains("IN"))
        assertTrue(ops.contains("NOT_IN"))
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. AqlSort 구조 검증
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `AqlSort holds field and direction`() {
        val sort = AqlSort(field = AqlField("priority"), direction = SortDirection.DESC)

        assertEquals("priority", sort.field.value)
        assertEquals(SortDirection.DESC, sort.direction)
    }

    @Test
    fun `SortDirection has ASC and DESC`() {
        assertEquals(SortDirection.ASC, SortDirection.valueOf("ASC"))
        assertEquals(SortDirection.DESC, SortDirection.valueOf("DESC"))
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. IssueSearchQuery 커맨드 객체 구조 검증
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `IssueSearchQuery holds all required fields`() {
        val viewerUserId = UUID.randomUUID()
        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val sort = listOf(AqlSort(AqlField("priority"), SortDirection.ASC))

        val query = IssueSearchQuery(
            projectKey = "PROJ",
            ast = ast,
            sort = sort,
            viewerUserId = viewerUserId,
            page = 0,
            size = 50,
        )

        assertEquals("PROJ", query.projectKey)
        assertEquals(ast, query.ast)
        assertEquals(sort, query.sort)
        assertEquals(viewerUserId, query.viewerUserId)
        assertEquals(0, query.page)
        assertEquals(50, query.size)
    }

    @Test
    fun `IssueSearchQuery supports empty sort list`() {
        val query = buildQuery(ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open"))))
        assertTrue(query.sort.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. IssueSearchHit 구조 검증 — assigneeId nullable 포함
    // ──────────────────────────────────────────────────────────────────

    @Test
    fun `IssueSearchHit holds all required fields`() {
        val assigneeId = UUID.randomUUID()
        val now = Instant.now()
        val hit = IssueSearchHit(
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

        assertEquals("PROJ-1", hit.key)
        assertEquals("로그인 버그", hit.summary)
        assertEquals("bug", hit.typeKey)
        assertEquals("open", hit.currentStateKey)
        assertEquals(assigneeId, hit.assigneeId)
        assertEquals(1, hit.priority)
        assertEquals("Critical", hit.priorityName)
        assertEquals("PROJ", hit.projectKey)
        assertEquals(now, hit.updatedAt)
    }

    @Test
    fun `IssueSearchHit assigneeId is nullable`() {
        val hit = IssueSearchHit(
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

        assertNull(hit.assigneeId, "담당자 미배정 이슈는 assigneeId 가 null 이어야 한다")
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

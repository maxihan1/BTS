// IssueSearchAdapter 단위 테스트 — AST→Condition 매핑, BROWSE 게이트, visibility 우회 불가 검증

package com.bts.issue.adapter.outbound.search

import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlSort
import com.bts.shared.search.AqlValue
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchQuery
import com.bts.shared.search.SortDirection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [IssueSearchAdapter] 단위 테스트 (MockK).
 *
 * 검증 항목.
 * - BROWSE 게이트: BROWSE 없는 actor → [SecurityException] (probe 차단).
 * - AST→Condition 위임: 각 연산자/필드 변환이 [IssueRepository.searchByAql] 에 올바르게 위임되는지.
 * - AND/OR/NOT 재귀 AST 전달 확인.
 * - label ~ 연산자 허용 / priority ~ 연산자 거부.
 * - 미지원 필드 거부([IllegalArgumentException]).
 * - 빈 결과 정상 반환(누출 0 케이스 — 실 DB 검증은 통합 테스트에서).
 */
class IssueSearchAdapterTest {
    private val issueRepository: IssueRepository = mockk()
    private val securityDirectory: IssueSecurityDirectory = mockk()
    private val permissionResolver: IssuePermissionResolver = mockk()
    private val adapter = IssueSearchAdapter(issueRepository, securityDirectory, permissionResolver)

    private val actorId: UUID = UUID.randomUUID()
    private val projectKey = "PROJ"

    /** 기본 unrestricted 접근 — 보안 등급 필터 미적용 빠른경로. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /** 제한 있는 접근 — 보안 등급 필터 적용. */
    private val restrictedAccess =
        IssueSecurityAccess(
            unrestricted = false,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /** 빈 정렬 목록 + 페이지 기본값. */
    private val defaultSort = emptyList<AqlSort>()

    private fun buildQuery(ast: AqlNode): IssueSearchQuery =
        IssueSearchQuery(
            projectKey = projectKey,
            ast = ast,
            sort = defaultSort,
            viewerUserId = actorId,
            page = 0,
            size = 20,
        )

    private fun emptyPage(): IssueSearchPage = IssueSearchPage.empty(0, 20)

    // ── BROWSE 게이트 ────────────────────────────────────────────────────────

    @Test
    fun `BROWSE 권한 없는 actor 는 SecurityException 으로 차단된다`() {
        every {
            permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        } returns false

        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))

        assertThatThrownBy { adapter.search(buildQuery(ast)) }
            .isInstanceOf(SecurityException::class.java)
            .hasMessageContaining("BROWSE")
    }

    @Test
    fun `BROWSE 권한 없는 actor 가 probe 를 시도해도 이슈 존재 여부를 알 수 없다`() {
        // BROWSE 없으면 securityDirectory 나 issueRepository 에 접근하지 않아야 한다.
        every {
            permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        } returns false

        val ast = AqlNode.Comparison(AqlField("summary"), AqlOperator.CONTAINS, listOf(AqlValue.Str("secret")))

        assertThatThrownBy { adapter.search(buildQuery(ast)) }
            .isInstanceOf(SecurityException::class.java)

        // repository 나 securityDirectory 는 절대 호출되지 않아야 한다 (probe 차단).
        verify(exactly = 0) { securityDirectory.accessibleLevels(any(), any()) }
        verify(exactly = 0) { issueRepository.searchByAql(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── 정상 경로 — AST 전달 검증 ────────────────────────────────────────────

    @Test
    fun `BROWSE 있는 actor 의 status EQ 쿼리는 repository 에 AST 를 전달한다`() {
        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        val result = adapter.search(buildQuery(ast))

        assertThat(result).isEqualTo(emptyPage())
        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `summary CONTAINS 쿼리는 repository 에 위임된다`() {
        val ast = AqlNode.Comparison(AqlField("summary"), AqlOperator.CONTAINS, listOf(AqlValue.Str("버그")))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `label EQ 쿼리는 repository 에 위임된다`() {
        val ast = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("bug")))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `label CONTAINS 쿼리는 허용되어 repository 에 위임된다`() {
        val ast = AqlNode.Comparison(AqlField("label"), AqlOperator.CONTAINS, listOf(AqlValue.Str("bug")))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `priority EQ 쿼리는 허용된다`() {
        val ast = AqlNode.Comparison(AqlField("priority"), AqlOperator.EQ, listOf(AqlValue.Num(1)))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `priority IN 쿼리는 허용된다`() {
        val ast =
            AqlNode.Comparison(AqlField("priority"), AqlOperator.IN, listOf(AqlValue.Num(1), AqlValue.Num(2)))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    // ── 연산자 제약 ──────────────────────────────────────────────────────────

    @Test
    fun `priority CONTAINS 연산자는 거부된다`() {
        // priority 는 SMALLINT 이므로 ~ 연산자를 허용하지 않는다.
        val ast = AqlNode.Comparison(AqlField("priority"), AqlOperator.CONTAINS, listOf(AqlValue.Str("1")))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess

        assertThatThrownBy { adapter.search(buildQuery(ast)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("priority")
            .hasMessageContaining("~")
    }

    // ── 미지원 필드 거부 ──────────────────────────────────────────────────────

    @Test
    fun `미지원 필드 는 IllegalArgumentException 으로 거부된다`() {
        val ast = AqlNode.Comparison(AqlField("foobar"), AqlOperator.EQ, listOf(AqlValue.Str("x")))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess

        assertThatThrownBy { adapter.search(buildQuery(ast)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("foobar")
    }

    @Test
    fun `후속 지원 예정 필드(assignee)는 IllegalArgumentException 으로 거부된다`() {
        val ast = AqlNode.Comparison(AqlField("assignee"), AqlOperator.EQ, listOf(AqlValue.Str("alice")))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess

        assertThatThrownBy { adapter.search(buildQuery(ast)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("assignee")
    }

    // ── AND/OR/NOT 재귀 AST 전달 ──────────────────────────────────────────────

    @Test
    fun `AND 노드 AST 전체가 repository 에 전달된다`() {
        val left = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val right = AqlNode.Comparison(AqlField("priority"), AqlOperator.EQ, listOf(AqlValue.Num(1)))
        val ast = AqlNode.And(left, right)

        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `OR 노드 AST 전체가 repository 에 전달된다`() {
        val left = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("bug")))
        val right = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("urgent")))
        val ast = AqlNode.Or(left, right)

        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `NOT 노드 AST 전체가 repository 에 전달된다`() {
        val inner = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("closed")))
        val ast = AqlNode.Not(inner)

        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `AND AND OR 복합 중첩 AST 가 repository 에 전달된다`() {
        val labelBug = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("bug")))
        val labelUrgent = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("urgent")))
        val summaryNode =
            AqlNode.Comparison(AqlField("summary"), AqlOperator.CONTAINS, listOf(AqlValue.Str("로그인")))
        val ast = AqlNode.And(summaryNode, AqlNode.Or(labelBug, labelUrgent))

        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    // ── visibility + 보안 술어 위임 ──────────────────────────────────────────

    @Test
    fun `restricted access 는 그대로 repository 에 전달된다`() {
        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns restrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, restrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            // access 는 restrictedAccess 여야 한다 — unrestrictedAccess 가 아님.
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, restrictedAccess, 0, 20)
        }
    }

    @Test
    fun `sort 목록이 repository 에 그대로 전달된다`() {
        val sort = listOf(AqlSort(AqlField("priority"), SortDirection.DESC))
        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val query =
            IssueSearchQuery(
                projectKey = projectKey,
                ast = ast,
                sort = sort,
                viewerUserId = actorId,
                page = 0,
                size = 20,
            )

        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, sort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(query)

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, sort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `페이지와 크기가 repository 에 그대로 전달된다`() {
        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val query =
            IssueSearchQuery(
                projectKey = projectKey,
                ast = ast,
                sort = defaultSort,
                viewerUserId = actorId,
                page = 2,
                size = 50,
            )

        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 2, 50)
        } returns IssueSearchPage.empty(2, 50)

        val result = adapter.search(query)

        assertThat(result.page).isEqualTo(2)
        assertThat(result.size).isEqualTo(50)
        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 2, 50)
        }
    }

    // ── status IN/NOT_IN ───────────────────────────────────────────────────

    @Test
    fun `status IN 다중 값 쿼리가 repository 에 전달된다`() {
        val ast =
            AqlNode.Comparison(
                AqlField("status"),
                AqlOperator.IN,
                listOf(AqlValue.Str("open"), AqlValue.Str("in_progress")),
            )
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    @Test
    fun `status NOT_IN 쿼리가 repository 에 전달된다`() {
        val ast =
            AqlNode.Comparison(AqlField("status"), AqlOperator.NOT_IN, listOf(AqlValue.Str("closed")))
        stubBrowseAllow()
        every { securityDirectory.accessibleLevels(actorId, projectKey) } returns unrestrictedAccess
        every {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        } returns emptyPage()

        adapter.search(buildQuery(ast))

        verify(exactly = 1) {
            issueRepository.searchByAql(projectKey, ast, defaultSort, actorId, unrestrictedAccess, 0, 20)
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun stubBrowseAllow() {
        every {
            permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        } returns true
    }
}

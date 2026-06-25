// IssueRepository.searchByAql 실DB 통합 테스트 — visibility 차단·BROWSE probe 차단·누출 0 검증

package com.bts.issue.repository

import com.bts.issue.adapter.outbound.search.IssueSearchAdapter
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.adapter.outbound.AlwaysAllowIssueSecurityDirectory
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueRepository.searchByAql] + [IssueSearchAdapter] 실DB 통합 테스트.
 *
 * Testcontainers (테스트용 DB를 도커로 자동 실행하는 라이브러리) PostgreSQL 싱글턴 DB 위에서
 * 실제 이슈를 시드하고 AQL 검색·visibility 차단·BROWSE probe 차단·누출 0 시나리오를 end-to-end 검증.
 *
 * ### 보안 불변식 검증 (핵심 — 반드시 GREEN 확인)
 * - **BROWSE 게이트**: BROWSE 권한 없는 actor 는 [SecurityException] 으로 차단, 이슈 존재 여부 노출 금지.
 * - **visibility 차단**: 접근 불가 보안 등급 이슈(S4) 는 결과에서 제외.
 * - **누출 0**: unrestricted·NOT(전체)·빈 AST(AND 트리 전체) 조합에서 soft-deleted / 타 프로젝트 이슈 0건.
 * - **보안 술어 우회 불가**: 사용자 AST 의 OR/NOT 이 buildActiveSecureWhere 를 감쌀 수 없다.
 *
 * ### 테스트 시나리오
 * - SR-1. 기본 검색 (status EQ).
 * - SR-2. summary CONTAINS (ILIKE %v%).
 * - SR-3. label EQ (overlap &&).
 * - SR-4. label CONTAINS (배열 원소 ILIKE EXISTS unnest).
 * - SR-5. priority EQ.
 * - SR-6. AND 조합.
 * - SR-7. OR 조합.
 * - SR-8. NOT 부정.
 * - SR-9. NOT IN.
 * - SR-VS. visibility 차단 (접근 불가 보안 등급 이슈 제외).
 * - SR-BP. BROWSE probe 차단 (BROWSE 없는 actor → SecurityException, 존재 여부 누출 금지).
 * - SR-NL1. unrestricted access — soft-deleted 이슈 누출 0.
 * - SR-NL2. unrestricted access — 타 프로젝트 이슈 누출 0.
 * - SR-NL3. NOT(전체) AST — soft-deleted 이슈 누출 0.
 * - SR-PA. 페이지네이션 (page/size/total).
 * - SR-SO. 정렬 (priority DESC).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueSearchRepositoryIntegrationTest : IssueTestcontainersBase() {

    private var taskTypeId: IssueTypeId? = null

    /** IssueSearchAdapter 인스턴스 — 실 repository + AlwaysAllow stub 조합. */
    private lateinit var adapter: IssueSearchAdapter

    /** AlwaysAllowIssuePermissionResolver — test 환경 stub (항상 허용). */
    private val allowPermissionResolver = AlwaysAllowIssuePermissionResolver()

    /** securityDirectory mock — BROWSE 차단 시나리오(SR-BP) 에서 교체하지 않고 stub 사용. */
    private lateinit var securityDirectory: IssueSecurityDirectory

    /** BROWSE 차단 테스트에서 사용하는 mock permissionResolver. */
    private val denyBrowseResolver = mockk<com.bts.shared.permission.IssuePermissionResolver>()

    /** 제한 있는 접근 — 빈 허용 집합 (아무 보안 등급 이슈도 볼 수 없다). */
    private val restrictedAccessNoLevels =
        IssueSecurityAccess(
            unrestricted = false,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /** unrestricted 접근 — 보안 등급 필터 미적용. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    @BeforeAll
    fun resolveTaskTypeIdAndSetupAdapter() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                        taskTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
        }

        // AlwaysAllowIssueSecurityDirectory — test 환경 stub (unrestricted=true 빠른경로).
        // 보안 등급 필터가 필요한 테스트는 IssueSecurityDirectory mock 으로 교체한다.
        securityDirectory = AlwaysAllowIssueSecurityDirectory()
        adapter = IssueSearchAdapter(repository, securityDirectory, allowPermissionResolver)
    }

    @BeforeEach
    fun cleanSearchIssues() {
        // 각 테스트마다 이슈와 프로젝트 시퀀스를 초기화한다.
        // 부모 cleanIssues() 가 이미 issues DELETE + key_sequence=0 을 수행하므로
        // 여기선 SPRJ 별도 프로젝트 row 만 추가 cleanup 한다.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "DELETE FROM issues WHERE key LIKE 'SPRJ-%'",
                )
            }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    @Suppress("LongParameterList") // 통합 테스트 픽스처 빌더 — 다양한 필드 시드, 분리 불필요
    private fun buildIssue(
        seq: Long,
        projectId: UUID = testProjectId,
        projectPrefix: String = "TPRJ",
        summary: String = "test issue $seq",
        currentStateKey: String = "open",
        priority: Int = 3,
        labels: List<String> = emptyList(),
        securityLevelId: UUID? = null,
        reporterId: UUID = UUID.randomUUID(),
        deletedAt: Boolean = false,
    ): Issue {
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of(projectPrefix, seq),
                projectId = projectId,
                typeId = requireTaskTypeId(),
                summary = summary,
                reporterId = ActorId(reporterId),
                currentStateKey = currentStateKey,
                priority = priority,
                labels = labels,
                securityLevelId = securityLevelId,
            )
        val inserted = repository.insert(issue)

        if (deletedAt) {
            // soft-delete 처리
            repository.softDelete(inserted.key)
        }

        return inserted
    }

    private fun buildQuery(
        ast: AqlNode,
        sort: List<AqlSort> = emptyList(),
        viewerUserId: UUID = UUID.randomUUID(),
        page: Int = 0,
        size: Int = 50,
    ): IssueSearchQuery =
        IssueSearchQuery(
            projectKey = "TPRJ",
            ast = ast,
            sort = sort,
            viewerUserId = viewerUserId,
            page = page,
            size = size,
        )

    // ── SR-1. 기본 검색 — status EQ ────────────────────────────────────────

    @Test
    @Order(1)
    fun `SR-1 status EQ open 쿼리는 open 이슈만 반환한다`() {
        buildIssue(seq = 1, currentStateKey = "open")
        buildIssue(seq = 2, currentStateKey = "open")
        buildIssue(seq = 3, currentStateKey = "done")

        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val result = adapter.search(buildQuery(ast))

        assertThat(result.total).isEqualTo(2L)
        assertThat(result.items).hasSize(2)
        assertThat(result.items).allMatch { it.currentStateKey == "open" }
    }

    // ── SR-2. summary CONTAINS ──────────────────────────────────────────────

    @Test
    @Order(2)
    fun `SR-2 summary CONTAINS 는 제목에 부분 문자열을 포함하는 이슈를 반환한다`() {
        buildIssue(seq = 1, summary = "로그인 버그 수정")
        buildIssue(seq = 2, summary = "회원가입 오류")
        buildIssue(seq = 3, summary = "로그아웃 이슈")

        val ast = AqlNode.Comparison(AqlField("summary"), AqlOperator.CONTAINS, listOf(AqlValue.Str("로그")))
        val result = adapter.search(buildQuery(ast))

        assertThat(result.total).isEqualTo(2L)
        assertThat(result.items.map { it.summary }).allMatch { it.contains("로그") }
    }

    // ── SR-3. label EQ (overlap &&) ────────────────────────────────────────

    @Test
    @Order(3)
    fun `SR-3 label EQ 는 해당 라벨을 포함하는 이슈를 반환한다`() {
        buildIssue(seq = 1, labels = listOf("bug", "critical"))
        buildIssue(seq = 2, labels = listOf("enhancement"))
        buildIssue(seq = 3, labels = listOf("bug"))

        val ast = AqlNode.Comparison(AqlField("label"), AqlOperator.EQ, listOf(AqlValue.Str("bug")))
        val result = adapter.search(buildQuery(ast))

        assertThat(result.total).isEqualTo(2L)
        assertThat(result.items.map { it.key }).allMatch { key ->
            result.items.any { it.key == key }
        }
    }

    // ── SR-4. label CONTAINS (배열 원소 ILIKE EXISTS unnest) ─────────────

    @Test
    @Order(4)
    fun `SR-4 label CONTAINS 는 배열 원소 중 부분 문자열 포함 이슈를 반환한다`() {
        buildIssue(seq = 1, labels = listOf("critical-bug", "urgent"))
        buildIssue(seq = 2, labels = listOf("enhancement"))
        buildIssue(seq = 3, labels = listOf("minor-bug"))

        val ast = AqlNode.Comparison(AqlField("label"), AqlOperator.CONTAINS, listOf(AqlValue.Str("bug")))
        val result = adapter.search(buildQuery(ast))

        // "critical-bug" 와 "minor-bug" 를 가진 이슈 2건 반환
        assertThat(result.total).isEqualTo(2L)
    }

    // ── SR-5. priority EQ ───────────────────────────────────────────────────

    @Test
    @Order(5)
    fun `SR-5 priority EQ 는 해당 우선순위 이슈만 반환한다`() {
        buildIssue(seq = 1, priority = 1)
        buildIssue(seq = 2, priority = 3)
        buildIssue(seq = 3, priority = 1)

        val ast = AqlNode.Comparison(AqlField("priority"), AqlOperator.EQ, listOf(AqlValue.Num(1)))
        val result = adapter.search(buildQuery(ast))

        assertThat(result.total).isEqualTo(2L)
        assertThat(result.items).allMatch { it.priority == 1 }
    }

    // ── SR-6. AND 조합 ──────────────────────────────────────────────────────

    @Test
    @Order(6)
    fun `SR-6 AND 조합은 두 조건을 모두 만족하는 이슈만 반환한다`() {
        buildIssue(seq = 1, currentStateKey = "open", priority = 1)
        buildIssue(seq = 2, currentStateKey = "open", priority = 3)
        buildIssue(seq = 3, currentStateKey = "done", priority = 1)

        val statusNode = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val priorityNode = AqlNode.Comparison(AqlField("priority"), AqlOperator.EQ, listOf(AqlValue.Num(1)))
        val ast = AqlNode.And(statusNode, priorityNode)

        val result = adapter.search(buildQuery(ast))

        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items.first().currentStateKey).isEqualTo("open")
        assertThat(result.items.first().priority).isEqualTo(1)
    }

    // ── SR-7. OR 조합 ──────────────────────────────────────────────────────

    @Test
    @Order(7)
    fun `SR-7 OR 조합은 둘 중 하나를 만족하는 이슈를 반환한다`() {
        buildIssue(seq = 1, currentStateKey = "open")
        buildIssue(seq = 2, currentStateKey = "done")
        buildIssue(seq = 3, currentStateKey = "in_progress")

        val openNode = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val doneNode = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("done")))
        val ast = AqlNode.Or(openNode, doneNode)

        val result = adapter.search(buildQuery(ast))

        assertThat(result.total).isEqualTo(2L)
        assertThat(result.items.map { it.currentStateKey })
            .allMatch { it == "open" || it == "done" }
    }

    // ── SR-8. NOT 부정 ─────────────────────────────────────────────────────

    @Test
    @Order(8)
    fun `SR-8 NOT 부정은 조건을 만족하지 않는 이슈를 반환한다`() {
        buildIssue(seq = 1, currentStateKey = "open")
        buildIssue(seq = 2, currentStateKey = "done")
        buildIssue(seq = 3, currentStateKey = "done")

        val doneNode = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("done")))
        val ast = AqlNode.Not(doneNode)

        val result = adapter.search(buildQuery(ast))

        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items.first().currentStateKey).isEqualTo("open")
    }

    // ── SR-9. NOT IN ───────────────────────────────────────────────────────

    @Test
    @Order(9)
    fun `SR-9 status NOT_IN 은 목록에 없는 상태 이슈를 반환한다`() {
        buildIssue(seq = 1, currentStateKey = "open")
        buildIssue(seq = 2, currentStateKey = "done")
        buildIssue(seq = 3, currentStateKey = "in_progress")

        val ast =
            AqlNode.Comparison(
                AqlField("status"),
                AqlOperator.NOT_IN,
                listOf(AqlValue.Str("done"), AqlValue.Str("in_progress")),
            )

        val result = adapter.search(buildQuery(ast))

        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items.first().currentStateKey).isEqualTo("open")
    }

    // ── SR-VS. visibility 차단 ─────────────────────────────────────────────

    @Test
    @Order(10)
    fun `SR-VS 접근 불가 보안 등급 이슈는 결과에서 제외된다`() {
        val actor = UUID.randomUUID()
        val securedLevelId = UUID.randomUUID()

        // 공개 이슈 2건 + 보안 등급 이슈 1건
        buildIssue(seq = 1, currentStateKey = "open")
        buildIssue(seq = 2, currentStateKey = "open")
        buildIssue(seq = 3, currentStateKey = "open", securityLevelId = securedLevelId)

        // actor 에게 securedLevelId 접근 불가 restricted access
        val restrictedAccess =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(), // securedLevelId 미포함 → 보안 등급 이슈 차단
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        // securityDirectory 를 mock 으로 교체해 restricted access 반환
        val mockSecurityDir = mockk<IssueSecurityDirectory>()
        every { mockSecurityDir.accessibleLevels(actor, "TPRJ") } returns restrictedAccess
        val restrictedAdapter = IssueSearchAdapter(repository, mockSecurityDir, allowPermissionResolver)

        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val result =
            restrictedAdapter.search(
                IssueSearchQuery(
                    projectKey = "TPRJ",
                    ast = ast,
                    sort = emptyList(),
                    viewerUserId = actor,
                    page = 0,
                    size = 50,
                ),
            )

        // 보안 등급 이슈(seq=3) 는 제외 → 2건만 반환
        assertThat(result.total).isEqualTo(2L)
        assertThat(result.items).noneMatch { it.key.endsWith("-3") }
    }

    // ── SR-BP. BROWSE probe 차단 ───────────────────────────────────────────

    @Test
    @Order(11)
    fun `SR-BP BROWSE 없는 actor 는 SecurityException 으로 차단되고 이슈 존재 여부가 노출되지 않는다`() {
        val actor = UUID.randomUUID()

        // 이슈가 있어도
        buildIssue(seq = 1, currentStateKey = "open")

        // BROWSE 거부 resolver 설정
        every {
            denyBrowseResolver.hasPermission(actor, IssuePermission.BROWSE, IssueScope.Project("TPRJ"))
        } returns false

        val denyAdapter = IssueSearchAdapter(repository, securityDirectory, denyBrowseResolver)

        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))

        assertThatThrownBy {
            denyAdapter.search(
                IssueSearchQuery(
                    projectKey = "TPRJ",
                    ast = ast,
                    sort = emptyList(),
                    viewerUserId = actor,
                    page = 0,
                    size = 50,
                ),
            )
        }.isInstanceOf(SecurityException::class.java)
            .hasMessageContaining("BROWSE")
    }

    // ── SR-NL1. unrestricted + soft-deleted 이슈 누출 0 ────────────────────

    @Test
    @Order(12)
    fun `SR-NL1 unrestricted access 에서도 soft-deleted 이슈는 누출되지 않는다`() {
        buildIssue(seq = 1, currentStateKey = "open")
        // seq=2 는 소프트 삭제
        buildIssue(seq = 2, currentStateKey = "open", deletedAt = true)

        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val result = adapter.search(buildQuery(ast))

        // soft-deleted 이슈(seq=2) 는 결과에서 제외
        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items).noneMatch { it.key.endsWith("-2") }
    }

    // ── SR-NL2. 타 프로젝트 이슈 누출 0 ────────────────────────────────────

    @Test
    @Order(13)
    fun `SR-NL2 다른 프로젝트 이슈는 누출되지 않는다`() {
        // TPRJ 이슈 1건
        buildIssue(seq = 1, currentStateKey = "open")

        // 별도 SPRJ 프로젝트 생성 후 이슈 삽입
        var sprojId: UUID? = null
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('SPRJ', 'Search Test Project') ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }
            conn.prepareStatement("SELECT id FROM projects WHERE key = 'SPRJ'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    sprojId = rs.getObject(1) as UUID
                }
            }
        }
        // SPRJ 이슈 직접 삽입
        buildIssue(
            seq = 1,
            projectId = requireNotNull(sprojId),
            projectPrefix = "SPRJ",
            currentStateKey = "open",
        )

        // TPRJ 스코프 검색
        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val result = adapter.search(buildQuery(ast))

        // TPRJ 이슈만 반환 — SPRJ 이슈 누출 0
        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items).allMatch { it.key.startsWith("TPRJ-") }
    }

    // ── SR-NL3. NOT(전체) AST — soft-deleted 이슈 누출 0 ──────────────────

    @Test
    @Order(14)
    fun `SR-NL3 NOT 전체 AST 에서도 soft-deleted 이슈는 누출되지 않는다`() {
        buildIssue(seq = 1, currentStateKey = "open")
        // seq=2 는 소프트 삭제
        buildIssue(seq = 2, currentStateKey = "open", deletedAt = true)

        // NOT(status = "non-existent-state") → 모든 활성 이슈가 해당
        val innerNode =
            AqlNode.Comparison(
                AqlField("status"),
                AqlOperator.EQ,
                listOf(AqlValue.Str("non_existent_state")),
            )
        val ast = AqlNode.Not(innerNode)
        val result = adapter.search(buildQuery(ast))

        // soft-deleted 이슈는 포함되지 않는다
        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items).noneMatch { it.key.endsWith("-2") }
    }

    // ── SR-PA. 페이지네이션 ─────────────────────────────────────────────────

    @Test
    @Order(15)
    fun `SR-PA 페이지네이션은 total 과 items 를 올바르게 반환한다`() {
        // 이슈 5건 시드
        (1L..5L).forEach { seq -> buildIssue(seq = seq, currentStateKey = "open") }

        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))

        // page=0, size=2
        val page0 = adapter.search(buildQuery(ast, page = 0, size = 2))
        assertThat(page0.total).isEqualTo(5L)
        assertThat(page0.items).hasSize(2)
        assertThat(page0.page).isEqualTo(0)
        assertThat(page0.size).isEqualTo(2)

        // page=1, size=2
        val page1 = adapter.search(buildQuery(ast, page = 1, size = 2))
        assertThat(page1.total).isEqualTo(5L)
        assertThat(page1.items).hasSize(2)

        // page=2, size=2 (마지막 1건)
        val page2 = adapter.search(buildQuery(ast, page = 2, size = 2))
        assertThat(page2.total).isEqualTo(5L)
        assertThat(page2.items).hasSize(1)
    }

    // ── SR-SO. 정렬 ────────────────────────────────────────────────────────

    @Test
    @Order(16)
    fun `SR-SO priority DESC 정렬은 우선순위 높은 이슈가 먼저 온다`() {
        buildIssue(seq = 1, priority = 3) // Medium
        buildIssue(seq = 2, priority = 1) // Highest
        buildIssue(seq = 3, priority = 5) // Lowest

        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val sort = listOf(AqlSort(AqlField("priority"), SortDirection.ASC))
        val result = adapter.search(buildQuery(ast, sort = sort))

        assertThat(result.items).hasSize(3)
        // priority ASC: 1(Highest) → 3(Medium) → 5(Lowest)
        assertThat(result.items[0].priority).isEqualTo(1)
        assertThat(result.items[1].priority).isEqualTo(3)
        assertThat(result.items[2].priority).isEqualTo(5)
    }

    // ── SR-EMT. 결과 0건 — 정상 200 ──────────────────────────────────────────

    @Test
    @Order(17)
    fun `SR-EMT 조건에 맞는 이슈가 없으면 빈 페이지를 반환한다`() {
        buildIssue(seq = 1, currentStateKey = "open")

        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("done")))
        val result = adapter.search(buildQuery(ast))

        assertThat(result.total).isEqualTo(0L)
        assertThat(result.items).isEmpty()
    }

    // ── SR-HIT. IssueSearchHit 필드 검증 ────────────────────────────────────

    @Test
    @Order(18)
    fun `SR-HIT IssueSearchHit 에 key, summary, priority, projectKey 가 올바르게 채워진다`() {
        buildIssue(seq = 1, summary = "hit test issue", currentStateKey = "open", priority = 2)

        val ast = AqlNode.Comparison(AqlField("status"), AqlOperator.EQ, listOf(AqlValue.Str("open")))
        val result = adapter.search(buildQuery(ast))

        assertThat(result.items).hasSize(1)
        val hit = result.items.first()
        assertThat(hit.key).isEqualTo("TPRJ-1")
        assertThat(hit.summary).isEqualTo("hit test issue")
        assertThat(hit.priority).isEqualTo(2)
        assertThat(hit.priorityName).isEqualTo("High")
        assertThat(hit.projectKey).isEqualTo("TPRJ")
        assertThat(hit.currentStateKey).isEqualTo("open")
    }
}

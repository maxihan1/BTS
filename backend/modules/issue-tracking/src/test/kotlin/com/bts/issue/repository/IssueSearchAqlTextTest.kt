// FR-SR-04 Task 3 RED — text ~ AQL 필드의 FTS+trigram 변환 통합 테스트

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.search.AqlField
import com.bts.shared.search.AqlNode
import com.bts.shared.search.AqlOperator
import com.bts.shared.search.AqlValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * FR-SR-04 Task 3 — `text ~` AQL 필드의 FTS + trigram 변환 실DB 통합 테스트.
 *
 * Testcontainers (테스트용 DB를 도커로 자동 실행하는 라이브러리) JVM singleton을 재사용한다.
 * V032 마이그레이션(search_vector STORED generated column + GIN 인덱스)이 적용된 DB에서
 * [IssueRepository.searchByAql]의 `text` 필드 분기를 end-to-end 검증한다.
 *
 * ### 보안 불변식
 *
 * 보안 술어([IssueRepository.buildActiveSecureWhere])는 `text` 분기와 무관하게 자동 AND 결합된다.
 * 이 테스트는 `text` 필드 변환 로직에 집중하므로 `unrestricted=true` 접근권한을 사용한다.
 *
 * ### 테스트 시나리오
 *
 * - TX-1. 본문에만 "토큰 만료"가 있는 이슈 → `text ~ "토큰 만료"`로 매칭.
 * - TX-2. 제목에만 "토큰 만료"가 있는 이슈 → `text ~`로 매칭.
 * - TX-3. `text ~ ""`(빈 문자열) → 결과 0 (G5 정책).
 * - TX-4. `text ~ "   "`(공백만) → 결과 0.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueSearchAqlTextTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

    /** unrestricted=true 접근권한 — 보안등급 필터 미적용 빠른경로. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    taskTypeId = IssueTypeId(rs.getLong(1))
                }
            }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** 테스트용 이슈를 생성하고 DB에 저장하는 헬퍼. */
    private fun insertIssue(
        seq: Long,
        summary: String,
        description: String? = null,
    ): Issue {
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = summary,
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                description = description,
            )
        return repository.insert(issue)
    }

    /**
     * `text ~` AQL 조건으로 [IssueRepository.searchByAql]을 직접 호출하는 헬퍼.
     *
     * BROWSE 게이트는 어댑터 레이어에서 처리한다.
     * 이 테스트는 repository 변환 로직에 집중한다.
     */
    private fun searchByText(term: String): com.bts.shared.search.IssueSearchPage {
        val ast =
            AqlNode.Comparison(
                field = AqlField("text"),
                op = AqlOperator.CONTAINS,
                values = listOf(AqlValue.Str(term)),
            )
        return repository.searchByAql(
            projectKey = "TPRJ",
            ast = ast,
            sort = emptyList(),
            actor = UUID.randomUUID(),
            access = unrestrictedAccess,
            page = 0,
            size = 50,
        )
    }

    // ── TX-1. 본문에만 검색어가 있는 이슈 매칭 ──────────────────────────────

    @Test
    @Order(1)
    fun `TX-1 본문에만 '토큰 만료'가 있는 이슈는 text ~ '토큰 만료'로 매칭된다`() {
        insertIssue(seq = 1, summary = "일반 이슈 제목", description = "액세스 토큰 만료 처리가 필요합니다.")
        insertIssue(seq = 2, summary = "다른 이슈", description = "관련 없는 내용입니다.")

        val result = searchByText("토큰 만료")

        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items.first().summary).isEqualTo("일반 이슈 제목")
    }

    // ── TX-2. 제목에만 검색어가 있는 이슈 매칭 ──────────────────────────────

    @Test
    @Order(2)
    fun `TX-2 제목에만 '토큰 만료'가 있는 이슈는 text ~로 매칭된다`() {
        insertIssue(seq = 1, summary = "토큰 만료 버그 수정", description = "관련 없는 본문입니다.")
        insertIssue(seq = 2, summary = "로그인 이슈", description = "세션 문제가 있습니다.")

        val result = searchByText("토큰 만료")

        assertThat(result.total).isEqualTo(1L)
        assertThat(result.items.first().summary).isEqualTo("토큰 만료 버그 수정")
    }

    // ── TX-3. 빈 검색어 → 결과 0 (G5 정책) ─────────────────────────────────

    @Test
    @Order(3)
    fun `TX-3 빈 문자열 검색어는 결과 0을 반환한다`() {
        insertIssue(seq = 1, summary = "토큰 만료 이슈", description = "본문 있음")

        val result = searchByText("")

        assertThat(result.total).isEqualTo(0L)
        assertThat(result.items).isEmpty()
    }

    // ── TX-4. 공백만 있는 검색어 → 결과 0 ──────────────────────────────────

    @Test
    @Order(4)
    fun `TX-4 공백만 있는 검색어는 결과 0을 반환한다`() {
        insertIssue(seq = 1, summary = "토큰 만료 이슈", description = "본문 있음")

        val result = searchByText("   ")

        assertThat(result.total).isEqualTo(0L)
        assertThat(result.items).isEmpty()
    }
}

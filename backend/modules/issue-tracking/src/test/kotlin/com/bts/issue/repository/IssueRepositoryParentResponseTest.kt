// IssueRepository.findByKeyWithType / listWithType parent 노출 통합 테스트 — FR-LK-01 Task 1

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueRepository.findByKeyWithType] / [IssueRepository.listWithType] 에서
 * [com.bts.issue.adapter.inbound.rest.IssueResponse.parent] 필드가 올바르게 채워지는지 검증한다.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL + Flyway 마이그레이션을 재사용한다.
 *
 * 시나리오.
 * - T1. 부모가 있는 자식 이슈를 findByKeyWithType 으로 조회 → parent={key, summary} 채워짐.
 * - T2. 부모가 없는 이슈를 findByKeyWithType 으로 조회 → parent=null.
 * - T3. listWithType 으로 조회 → parent=null (목록 경로는 N+1/비용 회피로 미채움).
 */
class IssueRepositoryParentResponseTest : IssueTestcontainersBase() {
    /** V003 seed task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

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

    private fun requireTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 미초기화 — resolveTaskTypeId 확인" }

    /** 테스트용 이슈를 생성·삽입하고 DB 반환값을 돌려준다. */
    private fun insertIssue(
        seq: Long,
        summary: String = "테스트 이슈 $seq",
    ): Issue {
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = requireTypeId(),
                summary = summary,
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        return repository.insert(issue)
    }

    // ── T1. findByKeyWithType — 부모가 있는 자식 이슈 ────────────────────────────

    /**
     * Given  부모(P) 와 자식(C) 이슈가 존재하고, C.parentId == P.id 인 상태.
     * When   findByKeyWithType(C.key) 호출.
     * Then   IssueResponse.parent.key == P.key, parent.summary == P.summary.
     */
    @Test
    fun `T1 - findByKeyWithType - 부모가 있는 자식 조회 시 parent 필드가 채워진다`() {
        val parent = insertIssue(seq = 1L, summary = "부모 이슈")
        val child = insertIssue(seq = 2L, summary = "자식 이슈")

        repository.updateParent(issueId = child.id.value, parentId = parent.id.value)

        val response = repository.findByKeyWithType(child.key)

        assertThat(response).isNotNull
        assertThat(response!!.parent).isNotNull
        assertThat(response.parent!!.key).isEqualTo(parent.key.value)
        assertThat(response.parent!!.summary).isEqualTo("부모 이슈")
    }

    // ── T2. findByKeyWithType — 부모가 없는 이슈 ─────────────────────────────────

    /**
     * Given  부모가 없는 최상위 이슈.
     * When   findByKeyWithType(issue.key) 호출.
     * Then   IssueResponse.parent == null.
     */
    @Test
    fun `T2 - findByKeyWithType - 부모가 없는 이슈 조회 시 parent 가 null 이다`() {
        val issue = insertIssue(seq = 1L)

        val response = repository.findByKeyWithType(issue.key)

        assertThat(response).isNotNull
        assertThat(response!!.parent).isNull()
    }

    // ── T3. listWithType — 목록 경로 parent 미채움 ───────────────────────────────

    /**
     * Given  부모(P) 와 자식(C) 이슈가 존재하고, C.parentId == P.id 인 상태.
     * When   listWithType(projectKey, ...) 호출.
     * Then   모든 IssueResponse.parent == null (목록 경로는 N+1 회피로 parent 미채움).
     */
    @Test
    fun `T3 - listWithType - 목록 조회 시 parent 는 항상 null 이다`() {
        val parent = insertIssue(seq = 1L, summary = "부모 이슈")
        val child = insertIssue(seq = 2L, summary = "자식 이슈")

        repository.updateParent(issueId = child.id.value, parentId = parent.id.value)

        val page =
            repository.listWithType(
                projectKey = "TPRJ",
                pageable = PageRequest.of(0, 20),
                actor = UUID.randomUUID(),
            )

        assertThat(page.content).hasSize(2)
        assertThat(page.content.all { it.parent == null }).isTrue()
    }
}

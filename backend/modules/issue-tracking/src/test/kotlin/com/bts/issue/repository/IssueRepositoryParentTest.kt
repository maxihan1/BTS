// IssueRepository parent 메서드 통합 테스트 — updateParent, collectAncestors (FR-LK-01 Task 4)

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * IssueRepository.updateParent / collectAncestors 통합 테스트.
 *
 * IssueTestcontainersBase 를 상속해 JVM singleton PostgreSQL + Flyway 마이그레이션을 재사용한다.
 *
 * 시나리오.
 * - T1. updateParent — parent_id 설정 후 재조회 시 Issue.parentId 가 일치한다.
 * - T2. updateParent null — parent_id 해제 후 재조회 시 Issue.parentId 가 null 이다.
 * - T3. collectAncestors — A←B←C(C의 부모=B, B의 부모=A) 체인에서 collectAncestors(C) 가 [B.id, A.id] 를 포함한다.
 * - T4. collectAncestors root — 부모가 없는 최상위 이슈의 ancestors 는 빈 리스트다.
 */
class IssueRepositoryParentTest : IssueTestcontainersBase() {
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

    private fun requireTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 미초기화 — resolveTaskTypeId 확인" }

    /** 테스트용 이슈를 생성·삽입하고 DB 반환값을 돌려준다. */
    private fun insertIssue(seq: Long): Issue {
        val issue = Issue.create(
            id = IssueId(UUID.randomUUID()),
            key = IssueKey.of("TPRJ", seq),
            projectId = testProjectId,
            typeId = requireTypeId(),
            summary = "테스트 이슈 $seq",
            reporterId = ActorId(UUID.randomUUID()),
            currentStateKey = "open",
        )
        return repository.insert(issue)
    }

    // ── T1. updateParent — parent_id 설정 ──────────────────────────────────────

    /**
     * Given  두 이슈 A, B 가 존재하고 B.parentId 가 null 인 상태
     * When   updateParent(B.id, A.id) 호출
     * Then   findByKey(B.key).parentId == A.id
     */
    @Test
    fun `T1 - updateParent - parent_id 를 설정하면 재조회 시 parentId 가 일치한다`() {
        val a = insertIssue(1L)
        val b = insertIssue(2L)

        repository.updateParent(issueId = b.id.value, parentId = a.id.value)

        val found = repository.findByKey(b.key)
        assertThat(found).isNotNull
        assertThat(found!!.parentId).isEqualTo(a.id.value)
    }

    // ── T2. updateParent null — parent_id 해제 ────────────────────────────────

    /**
     * Given  B.parentId == A.id 인 상태
     * When   updateParent(B.id, null) 호출
     * Then   findByKey(B.key).parentId == null
     */
    @Test
    fun `T2 - updateParent null - parent_id 를 해제하면 재조회 시 parentId 가 null 이다`() {
        val a = insertIssue(1L)
        val b = insertIssue(2L)

        repository.updateParent(issueId = b.id.value, parentId = a.id.value)
        repository.updateParent(issueId = b.id.value, parentId = null)

        val found = repository.findByKey(b.key)
        assertThat(found).isNotNull
        assertThat(found!!.parentId).isNull()
    }

    // ── T3. collectAncestors — 체인 상향 수집 ────────────────────────────────

    /**
     * Given  A, B, C 세 이슈. B.parent=A, C.parent=B (C←B←A 체인)
     * When   collectAncestors(C.id)
     * Then   결과 set 에 B.id 와 A.id 가 모두 포함된다 (순서 무관).
     */
    @Test
    fun `T3 - collectAncestors - 부모 체인을 따라 조상 UUID 목록을 반환한다`() {
        val a = insertIssue(1L)
        val b = insertIssue(2L)
        val c = insertIssue(3L)

        repository.updateParent(issueId = b.id.value, parentId = a.id.value)
        repository.updateParent(issueId = c.id.value, parentId = b.id.value)

        val ancestors = repository.collectAncestors(c.id.value)

        assertThat(ancestors).containsExactlyInAnyOrder(b.id.value, a.id.value)
    }

    // ── T4. collectAncestors root — 최상위 이슈 ────────────────────────────────

    /**
     * Given  부모가 없는 최상위 이슈 A
     * When   collectAncestors(A.id)
     * Then   빈 리스트를 반환한다.
     */
    @Test
    fun `T4 - collectAncestors root - 부모가 없으면 빈 리스트를 반환한다`() {
        val a = insertIssue(1L)

        val ancestors = repository.collectAncestors(a.id.value)

        assertThat(ancestors).hasSize(0)
    }
}

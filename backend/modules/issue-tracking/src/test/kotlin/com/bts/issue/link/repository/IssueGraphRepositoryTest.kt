// IssueGraphRepositoryTest — IssueGraphRepository 부모/자식 조회 Testcontainers 통합 테스트 (FR-LK-02 Task 1)

package com.bts.issue.link.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueGraphRepository] 부모/자식 그래프 조회 Testcontainers 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * 테스트 시나리오 (FR-LK-02 Task 1).
 * - TG1-A. findParent — 부모가 설정된 자식 → 부모 row(id/key/summary/statusKey) 정확 반환.
 * - TG1-B. findParent — parent_id NULL 인 이슈 → null.
 * - TG1-C. findParent — 부모가 소프트삭제(deleted_at set) → null.
 * - TG1-D. findChildren — 두 자식 중 하나가 소프트삭제 → 남은 자식만, key ASC.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueGraphRepositoryTest : IssueTestcontainersBase() {
    /** V003 seed task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var graphRepository: IssueGraphRepository

    // ── setup ─────────────────────────────────────────────────────────────────

    /**
     * 부모 [IssueTestcontainersBase.bootstrap] 이 @BeforeAll 로 먼저 실행된다.
     * 각 테스트 전 IssueGraphRepository 초기화 + taskTypeId 조회.
     */
    @BeforeEach
    fun setupGraphRepository() {
        graphRepository = IssueGraphRepository(dsl)
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    /**
     * V003 시드에서 'task' 이슈 타입 id 를 조회한다.
     */
    @Suppress("NestedBlockDepth")
    private fun loadTaskTypeId(): IssueTypeId =
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    IssueTypeId(rs.getLong(1))
                }
            }
        }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** 이슈 1건을 삽입하고 반환한다. */
    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "그래프 통합 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    /**
     * issues 테이블에서 parent_id 컬럼을 직접 업데이트한다.
     *
     * @param childId 자식 이슈 UUID.
     * @param parentId 설정할 부모 이슈 UUID.
     */
    private fun setParent(childId: UUID, parentId: UUID) {
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement("UPDATE issues SET parent_id = ? WHERE id = ?").use { stmt ->
                stmt.setObject(1, parentId)
                stmt.setObject(2, childId)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * issues 행의 deleted_at 을 현재 시각으로 설정한다 (소프트삭제).
     *
     * @param issueId 소프트삭제할 이슈 UUID.
     */
    private fun softDelete(issueId: UUID) {
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE id = ?").use { stmt ->
                stmt.setObject(1, issueId)
                stmt.executeUpdate()
            }
        }
    }

    // ── TG1-A. findParent — 부모가 설정된 자식 → 부모 row 반환 ───────────────

    /**
     * Given  부모 이슈 P, 자식 이슈 C 존재 + C.parent_id = P.id
     * When   findParent(C.id)
     * Then   부모 GraphNeighborRow(id/key/summary/statusKey) 정확 반환.
     */
    @Test
    @Order(1)
    fun `TG1-A - findParent - 부모가 설정된 자식 조회 시 부모 row 반환`() {
        val parent = insertIssue(1L)
        val child = insertIssue(2L)
        setParent(childId = child.id.value, parentId = parent.id.value)

        val result = graphRepository.findParent(childId = child.id.value)

        assertThat(result).isNotNull
        assertThat(result!!.id).isEqualTo(parent.id.value)
        assertThat(result.key).isEqualTo(parent.key.value)
        assertThat(result.summary).isEqualTo(parent.summary)
        assertThat(result.statusKey).isEqualTo(parent.currentStateKey)
    }

    // ── TG1-B. findParent — parent_id NULL 이면 null ─────────────────────────

    /**
     * Given  parent_id 가 NULL 인 이슈 A 존재
     * When   findParent(A.id)
     * Then   null 반환.
     */
    @Test
    @Order(2)
    fun `TG1-B - findParent - parent_id NULL 이슈 조회 시 null 반환`() {
        val issue = insertIssue(1L)
        // parent_id 를 설정하지 않음 → NULL

        val result = graphRepository.findParent(childId = issue.id.value)

        assertThat(result).isNull()
    }

    // ── TG1-C. findParent — 부모 소프트삭제 시 null ──────────────────────────

    /**
     * Given  부모 이슈 P(소프트삭제됨), 자식 이슈 C + C.parent_id = P.id
     * When   findParent(C.id)
     * Then   null 반환 (부모 deleted_at IS NOT NULL).
     */
    @Test
    @Order(3)
    fun `TG1-C - findParent - 부모 소프트삭제 시 null 반환`() {
        val parent = insertIssue(1L)
        val child = insertIssue(2L)
        setParent(childId = child.id.value, parentId = parent.id.value)
        softDelete(parent.id.value)

        val result = graphRepository.findParent(childId = child.id.value)

        assertThat(result).isNull()
    }

    // ── TG1-D. findChildren — 소프트삭제 제외 + key ASC ────────────────────────

    /**
     * Given  부모 P, 자식 C1(key ASC 정렬 기준 첫 번째), C2(소프트삭제) 존재
     * When   findChildren(P.id)
     * Then   C1 만 반환, key ASC 정렬.
     */
    @Test
    @Order(4)
    fun `TG1-D - findChildren - 소프트삭제 자식 제외하고 key ASC 반환`() {
        val parent = insertIssue(1L)
        val child1 = insertIssue(2L)
        val child2 = insertIssue(3L)
        setParent(childId = child1.id.value, parentId = parent.id.value)
        setParent(childId = child2.id.value, parentId = parent.id.value)
        softDelete(child2.id.value)

        val results = graphRepository.findChildren(parentId = parent.id.value)

        assertThat(results).hasSize(1)
        val row = results[0]
        assertThat(row.id).isEqualTo(child1.id.value)
        assertThat(row.key).isEqualTo(child1.key.value)
        assertThat(row.summary).isEqualTo(child1.summary)
        assertThat(row.statusKey).isEqualTo(child1.currentStateKey)
    }
}

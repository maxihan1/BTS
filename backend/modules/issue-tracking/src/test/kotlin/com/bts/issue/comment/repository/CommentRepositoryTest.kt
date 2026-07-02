// CommentRepository insert + listByIssue(created_at ASC) + 소프트 삭제 제외 Testcontainers 통합 테스트 (FR-IM-01 PR3 Task 2)

package com.bts.issue.comment.repository

import com.bts.issue.comment.domain.Comment
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
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * [CommentRepository] insert + listByIssue(`created_at` ASC 정렬) + 소프트 삭제 제외
 * Testcontainers 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 * `comments.issue_id` 가 `issues(id)` FK 이므로 각 테스트 전 부모 이슈를 시드한다.
 *
 * 테스트 시나리오 (FR-IM-01 PR3 Task 2).
 * - T2-A. insert 여러 건 후 listByIssue — created_at ASC 정렬로 반환.
 * - T2-B. 소프트 삭제(deleted_at IS NOT NULL) 행은 listByIssue 에서 제외.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CommentRepositoryTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var commentRepository: CommentRepository

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupRepository() {
        commentRepository = CommentRepository(dsl)
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    /** 각 테스트 전 comments 전체 삭제. issues 는 부모 cleanIssues() 가 처리. */
    @BeforeEach
    fun cleanComments() {
        withJdbcConnection { conn -> conn.createStatement().use { it.execute("DELETE FROM comments") } }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Testcontainers PostgreSQL 에 직접 JDBC 연결해 [block] 을 실행한다.
     *
     * `IssueTestcontainersBase.postgres` 의 JDBC 접속 정보를 매번 반복하지 않도록 추출한 공통 헬퍼
     * ([cleanComments], [loadTaskTypeId], [softDeleteComment] 가 공유).
     */
    private fun <T> withJdbcConnection(block: (Connection) -> T): T =
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use(block)

    @Suppress("NestedBlockDepth")
    private fun loadTaskTypeId(): IssueTypeId =
        withJdbcConnection { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    IssueTypeId(rs.getLong(1))
                }
            }
        }

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** 이슈 1건을 삽입하고 반환한다. seqNum 은 이슈 키 시퀀스 번호. */
    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "댓글 통합 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    /** 댓글 도메인 객체 생성 헬퍼. */
    private fun buildComment(
        issueId: UUID,
        body: String = "테스트 댓글",
        createdAt: Instant = Instant.now(),
    ): Comment =
        Comment(
            id = UUID.randomUUID(),
            issueId = issueId,
            authorId = UUID.randomUUID(),
            body = body,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    /** 지정한 id 의 댓글을 직접 소프트 삭제한다 (CommentRepository 는 이 Task 범위에서 softDelete 미제공). */
    private fun softDeleteComment(id: UUID) {
        withJdbcConnection { conn ->
            conn.prepareStatement("UPDATE comments SET deleted_at = now() WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeUpdate()
            }
        }
    }

    // ── T2-A. insert + listByIssue (created_at ASC) ──────────────────────────

    /**
     * Given  부모 이슈 존재
     * When   댓글 2건 insert (createdAt 다름, 역순 삽입) 후 listByIssue
     * Then   2건 반환, created_at ASC 정렬 (가장 이른 댓글이 첫 번째).
     */
    @Test
    @Order(1)
    fun `T2-A - insert 후 listByIssue 에 반영되고 created_at ASC 정렬`() {
        val issue = insertIssue(1L)
        val earlier = Instant.parse("2024-01-01T09:00:00Z")
        val later = Instant.parse("2024-01-02T09:00:00Z")

        // 나중 시각 댓글을 먼저 insert 해도 정렬은 created_at 기준이어야 한다.
        val c2 = buildComment(issue.id.value, body = "나중 댓글", createdAt = later)
        val c1 = buildComment(issue.id.value, body = "먼저 댓글", createdAt = earlier)
        commentRepository.insert(c2)
        commentRepository.insert(c1)

        val result = commentRepository.listByIssue(issue.id.value)

        assertThat(result).hasSize(2)
        assertThat(result[0].id).isEqualTo(c1.id)
        assertThat(result[1].id).isEqualTo(c2.id)
        assertThat(result[0].createdAt).isBeforeOrEqualTo(result[1].createdAt)
    }

    // ── T2-B. 소프트 삭제 제외 ────────────────────────────────────────────────

    /**
     * Given  댓글 2건 insert
     * When   1건 소프트 삭제(deleted_at 설정)
     * Then   listByIssue 결과에서 해당 건 제외 (1건만 조회).
     */
    @Test
    @Order(2)
    fun `T2-B - 소프트 삭제된 댓글은 listByIssue 에서 제외`() {
        val issue = insertIssue(1L)
        val c1 = buildComment(issue.id.value, body = "삭제될 댓글")
        val c2 = buildComment(issue.id.value, body = "남을 댓글")
        commentRepository.insert(c1)
        commentRepository.insert(c2)

        softDeleteComment(c1.id)

        val result = commentRepository.listByIssue(issue.id.value)

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(c2.id)
    }
}

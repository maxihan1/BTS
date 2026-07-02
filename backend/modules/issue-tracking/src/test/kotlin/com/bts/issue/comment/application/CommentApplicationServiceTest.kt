// CommentApplicationService create(UPDATE 게이트)·list(VIEW+IssueScope.Issue 게이트+렌더링+정렬) Testcontainers 통합 테스트 (FR-IM-01 PR3 Task 3)

package com.bts.issue.comment.application

import com.bts.issue.comment.domain.Comment
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * [CommentApplicationService] Testcontainers 통합 테스트 (FR-IM-01 PR3 Task 3).
 *
 * mockk 단위 테스트 대신 [IssueTestcontainersBase] 실 PostgreSQL 트랜잭션으로 검증한다 —
 * 권한 게이트·DB insert·정렬·렌더링을 실 DB 위에서 확인해 mockk default+any() 가짜그린을 회피한다
 * (learnings: mockk 가짜그린 회피, CommentRepositoryTest 와 동일 기반 클래스 재사용).
 *
 * 시나리오.
 * - T3-A. create — UPDATE 권한 없는 actor → [IssueAccessDeniedException] (403).
 * - T3-B. create — 권한 보유 시 저장된 comment.authorId == 주입 authorId (actor 와 다름).
 * - T3-C. list — VIEW 권한 없는 actor → [IssueAccessDeniedException] (403), scope=[IssueScope.Issue].
 * - T3-D. list — bodyHtml 은 [com.bts.issue.markdown.MarkdownRenderer.renderSafe] 로 렌더링됨.
 * - T3-E. list — created_at ASC 정렬.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CommentApplicationServiceTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id. value class 는 lateinit 불가 → nullable var (CommentRepositoryTest 선례). */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var commentRepository: CommentRepository
    private lateinit var resolver: RecordingPermissionResolver
    private lateinit var service: CommentApplicationService

    private val actorUuid: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val authorUuid: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val actor: ActorId = ActorId(actorUuid)

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupService() {
        commentRepository = CommentRepository(dsl)
        resolver = RecordingPermissionResolver()
        service = CommentApplicationService(commentRepository, repository, resolver)
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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
                summary = "댓글 서비스 테스트 이슈 $seqNum",
                reporterId = actor,
                currentStateKey = "open",
            ),
        )

    // ── T3-A. create — UPDATE 권한 게이트 ─────────────────────────────────────

    /**
     * Given  UPDATE 권한이 없는 actor
     * When   create 호출
     * Then   [IssueAccessDeniedException] (403) — 이슈 조회/저장 전 권한 게이트에서 차단.
     */
    @Test
    @Order(1)
    fun `T3-A - create UPDATE 권한 없으면 403`() {
        val issue = insertIssue(1L)
        resolver.deniedPermission = IssuePermission.UPDATE

        assertThatThrownBy {
            service.create(actor, issue.key, "본문", ActorId(authorUuid))
        }.isInstanceOf(IssueAccessDeniedException::class.java)
    }

    // ── T3-B. create — authorId 는 주입값 그대로 저장 ─────────────────────────

    /**
     * Given  UPDATE 권한 보유 actor
     * When   actor 와 다른 authorId 로 create 호출
     * Then   반환/저장된 comment.authorId == 주입 authorId (actor 아님).
     */
    @Test
    @Order(2)
    fun `T3-B - create 는 authorId 를 주입값 그대로 저장한다 (actor 아님)`() {
        val issue = insertIssue(2L)

        val comment = service.create(actor, issue.key, "본문", ActorId(authorUuid))

        assertThat(comment.authorId).isEqualTo(authorUuid)
        assertThat(comment.authorId).isNotEqualTo(actorUuid)

        val stored = commentRepository.listByIssue(issue.id.value)
        assertThat(stored).hasSize(1)
        assertThat(stored[0].authorId).isEqualTo(authorUuid)
    }

    // ── T3-C. list — VIEW + IssueScope.Issue 게이트 ───────────────────────────

    /**
     * Given  VIEW 권한이 없는 actor
     * When   list 호출
     * Then   [IssueAccessDeniedException] (403) — 평가 scope 는 [IssueScope.Issue] (Project 아님).
     */
    @Test
    @Order(3)
    fun `T3-C - list VIEW 권한 없으면 403 이고 scope 는 IssueScope Issue`() {
        val issue = insertIssue(3L)
        resolver.deniedPermission = IssuePermission.VIEW

        assertThatThrownBy {
            service.list(actor, issue.key)
        }.isInstanceOf(IssueAccessDeniedException::class.java)

        val lastCall = resolver.calls.last()
        assertThat(lastCall.second).isEqualTo(IssuePermission.VIEW)
        assertThat(lastCall.third).isEqualTo(IssueScope.Issue(issue.key.value))
    }

    // ── T3-D. list — bodyHtml 렌더링 ──────────────────────────────────────────

    /**
     * Given  "**bold**" 본문 댓글
     * When   list 호출
     * Then   bodyHtml 은 renderSafe 결과("<strong>bold</strong>" 포함), body 는 원문 유지.
     */
    @Test
    @Order(4)
    fun `T3-D - list 는 bodyHtml 을 renderSafe 로 렌더링한다`() {
        val issue = insertIssue(4L)
        service.create(actor, issue.key, "**bold**", ActorId(authorUuid))

        val result = service.list(actor, issue.key)

        assertThat(result).hasSize(1)
        assertThat(result[0].body).isEqualTo("**bold**")
        assertThat(result[0].bodyHtml).contains("<strong>bold</strong>")
    }

    // ── T3-E. list — created_at ASC 정렬 ──────────────────────────────────────

    /**
     * Given  나중 시각 댓글을 먼저 insert (역순 삽입)
     * When   list 호출
     * Then   created_at ASC 정렬로 반환 (가장 이른 댓글이 첫 번째).
     */
    @Test
    @Order(5)
    fun `T3-E - list 는 created_at ASC 정렬로 반환한다`() {
        val issue = insertIssue(5L)
        val earlier = Instant.parse("2024-01-01T09:00:00Z")
        val later = Instant.parse("2024-01-02T09:00:00Z")

        commentRepository.insert(buildComment(issue.id.value, body = "나중 댓글", createdAt = later))
        commentRepository.insert(buildComment(issue.id.value, body = "먼저 댓글", createdAt = earlier))

        val result = service.list(actor, issue.key)

        assertThat(result).hasSize(2)
        assertThat(result[0].body).isEqualTo("먼저 댓글")
        assertThat(result[1].body).isEqualTo("나중 댓글")
    }

    /** 댓글 도메인 객체 생성 헬퍼 (직접 insert 용 — createdAt 제어 목적). */
    private fun buildComment(
        issueId: UUID,
        body: String,
        createdAt: Instant,
    ): Comment =
        Comment(
            id = UUID.randomUUID(),
            issueId = issueId,
            authorId = authorUuid,
            body = body,
            createdAt = createdAt,
            updatedAt = createdAt,
        )
}

/**
 * 테스트 전용 [IssuePermissionResolver] — 특정 권한 하나만 거부하고 나머지는 모두 허용한다.
 *
 * 모든 호출을 [calls] 에 기록해 permission/scope 인자를 검증할 수 있게 한다.
 * (actorId, permission, scope) 순서의 [Triple] 로 기록한다.
 */
private class RecordingPermissionResolver : IssuePermissionResolver {
    var deniedPermission: IssuePermission? = null
    val calls: MutableList<Triple<UUID, IssuePermission, IssueScope>> = mutableListOf()

    override fun hasPermission(
        actorId: UUID,
        permission: IssuePermission,
        scope: IssueScope,
    ): Boolean {
        calls.add(Triple(actorId, permission, scope))
        return permission != deniedPermission
    }
}

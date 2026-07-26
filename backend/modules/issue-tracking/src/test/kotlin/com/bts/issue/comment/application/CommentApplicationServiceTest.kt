// CommentApplicationService 통합 테스트 — create(actor 강제)·createImported(원본 보존)·list 게이트/렌더링/정렬 (FR-CO-01)

package com.bts.issue.comment.application

import com.bts.issue.comment.application.CommentApplicationService.Companion.MAX_BODY_LENGTH
import com.bts.issue.comment.domain.Comment
import com.bts.issue.comment.domain.CommentBodyBlankException
import com.bts.issue.comment.domain.CommentBodyTooLongException
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueCommented
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
 * - T3-B. create — 저장된 comment.authorId == **actor** (FR-CO-01 D4 — 저작자 위조 차단).
 * - T3-C. list — VIEW 권한 없는 actor → [IssueAccessDeniedException] (403), scope=[IssueScope.Issue].
 * - T3-D. list — bodyHtml 은 [com.bts.issue.markdown.MarkdownRenderer.renderSafe] 로 렌더링됨.
 * - T3-E. list — created_at ASC 정렬.
 * - T3-H. create — [IssueEventPublisher.publish] 가 [IssueCommented] 이벤트로 호출됨 (FR-AT-01 Task 10,
 *   mockk — [IssueEventPublisher] 는 pgmq 발행 아웃바운드 어댑터라 실 DB 검증 대상이 아니다).
 *
 * ## FR-CO-01 신규 시나리오
 * - CO-1/CO-2. 본문 길이 경계 — 32,000자 통과 / 32,001자 [CommentBodyTooLongException].
 * - CO-3. 공백만 본문 → [CommentBodyBlankException].
 * - CO-4. [CommentApplicationService.createImported] — 원본 authorId·createdAt 보존 (Import 전용).
 * - CO-5. createImported 는 길이 상한 **면제** (원본 데이터 충실성, ADR D7 잔여위험 등재분).
 *
 * ## ★ 이관 기록 (FR-CO-01 D4)
 * 기존 T3-B("create 는 authorId 를 주입값 그대로 저장한다")와 T3-F("create 는 createdAt 인자를
 * 그대로 저장한다")는 **지금 제거되는 동작**을 단정하고 있었다. 두 단정은 [CommentApplicationService.createImported]
 * 로 **이관**했다(CO-4). 단순 시그니처 교체가 아니라 의미의 소유자가 바뀐 것이다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CommentApplicationServiceTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id. value class 는 lateinit 불가 → nullable var (CommentRepositoryTest 선례). */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var commentRepository: CommentRepository
    private lateinit var resolver: RecordingPermissionResolver
    private lateinit var eventPublisher: IssueEventPublisher
    private lateinit var archiveGuard: ProjectArchiveGuard
    private lateinit var service: CommentApplicationService

    private val actorUuid: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val authorUuid: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val actor: ActorId = ActorId(actorUuid)

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupService() {
        commentRepository = CommentRepository(dsl)
        resolver = RecordingPermissionResolver()
        eventPublisher = mockk(relaxed = true)
        archiveGuard = ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))
        service = CommentApplicationService(commentRepository, repository, resolver, eventPublisher, archiveGuard)
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

    /**
     * 아카이브된 프로젝트("CARCH") + 그 소속 이슈 1건을 삽입한다 (FR-PJ-04 PR-4 Task 9 전용).
     *
     * @return 삽입된 이슈.
     */
    private fun insertIssueInArchivedProject(seqNum: Long): Issue {
        val archivedProjectId =
            withJdbcConnection { conn ->
                conn.prepareStatement(
                    "INSERT INTO projects (key, name, archived_at) VALUES ('CARCH', 'Comment Archived', NOW()) " +
                        "ON CONFLICT (key) DO UPDATE SET archived_at = NOW()",
                ).use { it.executeUpdate() }
                conn.prepareStatement("SELECT id FROM projects WHERE key = 'CARCH'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            }
        return repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("CARCH", seqNum),
                projectId = archivedProjectId,
                typeId = requireTaskTypeId(),
                summary = "아카이브 잠금 테스트 이슈 $seqNum",
                reporterId = actor,
                currentStateKey = "open",
            ),
        )
    }

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
            service.create(actor, issue.key, "본문")
        }.isInstanceOf(IssueAccessDeniedException::class.java)
    }

    // ── T3-B. create — authorId 는 actor 로 강제 (FR-CO-01 D4) ────────────────

    /**
     * Given  UPDATE 권한 보유 actor
     * When   create 호출 (저작자를 지정할 창구가 없다)
     * Then   반환/저장된 comment.authorId == **actor**.
     *
     * 이 단정이 저작자 위조 차단의 본체다 — 시그니처에 `authorId` 파라미터가 없어야
     * 컴파일 자체가 위조를 막는다. 원본 저작자 보존이 필요한 Import 는 CO-4 참조.
     */
    @Test
    @Order(2)
    fun `T3-B - create 는 authorId 를 actor 로 강제한다`() {
        val issue = insertIssue(2L)

        val comment = service.create(actor, issue.key, "본문")

        assertThat(comment.authorId).isEqualTo(actorUuid)

        val stored = commentRepository.listByIssue(issue.id.value)
        assertThat(stored).hasSize(1)
        assertThat(stored[0].authorId).isEqualTo(actorUuid)
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
        service.create(actor, issue.key, "**bold**")

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

    // ── CO-4. createImported — 원본 authorId·createdAt 보존 (T3-B/T3-F 이관분) ─

    /**
     * Given  Import 시나리오 — 원작성자 authorUuid, 원 작성시각 2019-05-01
     * When   createImported 호출
     * Then   저장된 comment 의 authorId 는 **주입값**(actor 아님), createdAt·updatedAt 은 주입값 그대로.
     *
     * 기존 T3-B/T3-F 가 `create` 에 대해 단정하던 내용의 **이관 대상**이다 (FR-CO-01 D4).
     * 이 경로만 원본을 보존할 수 있어야 하고, REST 로는 재현 불가능해야 한다(스펙 S10).
     */
    @Test
    @Order(6)
    fun `CO-4 - createImported 는 원본 authorId 와 createdAt 을 보존한다`() {
        val issue = insertIssue(6L)
        val importedAt = Instant.parse("2019-05-01T12:00:00Z")

        val comment =
            service.createImported(actor, issue.key, "본문", ActorId(authorUuid), createdAt = importedAt)

        assertThat(comment.authorId).isEqualTo(authorUuid)
        assertThat(comment.authorId).isNotEqualTo(actorUuid)
        assertThat(comment.createdAt).isEqualTo(importedAt)
        assertThat(comment.updatedAt).isEqualTo(importedAt)

        val stored = commentRepository.listByIssue(issue.id.value)
        assertThat(stored).hasSize(1)
        assertThat(stored[0].authorId).isEqualTo(authorUuid)
        assertThat(stored[0].createdAt).isEqualTo(importedAt)
        assertThat(stored[0].updatedAt).isEqualTo(importedAt)
    }

    /**
     * Given  createdAt 인자를 지정하지 않은 기존 호출부(일반 사용자 댓글 작성)
     * When   create 호출
     * Then   comment.createdAt/updatedAt 은 [clock] 기준 현재 시각 — 기존 동작 무회귀.
     */
    @Test
    @Order(7)
    fun `T3-G - create 는 createdAt 인자가 없으면 clock 기준 현재 시각을 사용한다 (회귀 없음)`() {
        val issue = insertIssue(7L)
        val fixedInstant = Instant.parse("2024-06-15T10:30:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val fixedClockService =
            CommentApplicationService(commentRepository, repository, resolver, eventPublisher, archiveGuard, fixedClock)

        val comment = fixedClockService.create(actor, issue.key, "본문")

        assertThat(comment.createdAt).isEqualTo(fixedInstant)
        assertThat(comment.updatedAt).isEqualTo(fixedInstant)
    }

    // ── T3-H. create — IssueCommented 이벤트 발행 (FR-AT-01 Task 10) ──────────

    /**
     * Given  UPDATE 권한 보유 actor
     * When   create 호출
     * Then   [IssueEventPublisher.publish] 가 [IssueCommented] 이벤트 1건으로 호출된다
     *        (issueKey/projectKey/commentId/actorId/occurredAt 필드 정합).
     */
    @Test
    @Order(8)
    fun `T3-H - create 는 IssueCommented 이벤트를 발행한다`() {
        val issue = insertIssue(8L)
        val fixedInstant = Instant.parse("2024-06-15T10:30:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val fixedClockService =
            CommentApplicationService(commentRepository, repository, resolver, eventPublisher, archiveGuard, fixedClock)

        val comment = fixedClockService.create(actor, issue.key, "본문")

        verify(exactly = 1) {
            eventPublisher.publish(
                match {
                    it is IssueCommented &&
                        it.issueKey == issue.key &&
                        it.projectKey == issue.key.projectPrefix &&
                        it.commentId == comment.id &&
                        it.actorId == actor &&
                        it.occurredAt == fixedInstant
                },
            )
        }
    }

    // ── 아카이브 잠금 (FR-PJ-04 PR-4 Task 9) ─────────────────────────────────

    /**
     * Given  UPDATE 권한 보유 actor, 이슈가 아카이브된 프로젝트("CARCH") 소속
     * When   create 호출
     * Then   ProjectArchivedException(409) — checkPermission 통과 후 archiveGuard.checkByIssue 가 던진다.
     */
    @Test
    @Order(9)
    fun `아카이브 잠금 - create 는 아카이브된 프로젝트 이슈에 409(ProjectArchivedException)`() {
        val issue = insertIssueInArchivedProject(9L)

        assertThatThrownBy {
            service.create(actor, issue.key, "본문")
        }.isInstanceOf(ProjectArchivedException::class.java)
    }

    /**
     * Given  UPDATE 권한 보유 actor, 이슈가 활성 프로젝트("TPRJ") 소속
     * When   create 호출
     * Then   2xx 통과 — 판별자 baseline.
     */
    @Test
    @Order(10)
    fun `아카이브 잠금 - create 는 활성 프로젝트 이슈에 2xx (판별자 baseline)`() {
        val issue = insertIssue(10L)

        val comment = service.create(actor, issue.key, "본문")

        assertThat(comment.body).isEqualTo("본문")
    }

    // ── CO-1/CO-2/CO-3. 본문 검증 — 길이 상한 + 공백 (FR-CO-01 D5/D7) ─────────

    /**
     * Given  정확히 [MAX_BODY_LENGTH] 자 본문
     * When   create 호출
     * Then   통과 — 경계 안쪽은 허용한다.
     *
     * 경계 양쪽을 모두 테스트한다(CO-2 와 쌍). 한쪽만 두면 off-by-one 이 잡히지 않는다.
     */
    @Test
    @Order(11)
    fun `CO-1 - create 는 32000자 본문을 허용한다`() {
        val issue = insertIssue(11L)
        val body = "가".repeat(MAX_BODY_LENGTH)

        val comment = service.create(actor, issue.key, body)

        assertThat(comment.body).hasSize(MAX_BODY_LENGTH)
    }

    /**
     * Given  [MAX_BODY_LENGTH] + 1 자 본문
     * When   create 호출
     * Then   [CommentBodyTooLongException] — 도메인 예외다.
     *
     * ★ `ResponseStatusException`(웹 관심사)이 아니어야 한다. 이 서비스는 REST 뿐 아니라
     * automation `AddCommentAction` 도 호출하며, 그 경로는 HTTP 를 모른다 (리뷰 C2).
     */
    @Test
    @Order(12)
    fun `CO-2 - create 는 32001자 본문에 CommentBodyTooLongException 을 던진다`() {
        val issue = insertIssue(12L)
        val body = "가".repeat(MAX_BODY_LENGTH + 1)

        assertThatThrownBy {
            service.create(actor, issue.key, body)
        }.isInstanceOf(CommentBodyTooLongException::class.java)

        assertThat(commentRepository.listByIssue(issue.id.value)).isEmpty()
    }

    /**
     * Given  공백·개행만으로 이루어진 본문
     * When   create 호출
     * Then   [CommentBodyBlankException] — 빈 댓글은 만들 수 없다.
     */
    @Test
    @Order(13)
    fun `CO-3 - create 는 공백만인 본문에 CommentBodyBlankException 을 던진다`() {
        val issue = insertIssue(13L)

        assertThatThrownBy {
            service.create(actor, issue.key, "   \n\t  ")
        }.isInstanceOf(CommentBodyBlankException::class.java)

        assertThat(commentRepository.listByIssue(issue.id.value)).isEmpty()
    }

    /**
     * Given  [MAX_BODY_LENGTH] + 1 자 원본 댓글 (Jira 등 외부 시스템)
     * When   createImported 호출
     * Then   통과 — Import 는 길이 상한을 **면제**한다.
     *
     * 근거. Import 는 `authorId`·`createdAt` 도 면제하는 원본 보존 계약이다(ADR D7).
     * 이 면제는 ADR 잔여위험 표에 등재돼 있다 — 조용한 면제가 아니라 기록된 면제다.
     */
    @Test
    @Order(14)
    fun `CO-5 - createImported 는 32001자 본문을 허용한다 (상한 면제)`() {
        val issue = insertIssue(14L)
        val body = "가".repeat(MAX_BODY_LENGTH + 1)

        val comment =
            service.createImported(
                actor,
                issue.key,
                body,
                ActorId(authorUuid),
                createdAt = Instant.parse("2019-01-01T00:00:00Z"),
            )

        assertThat(comment.body).hasSize(MAX_BODY_LENGTH + 1)
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

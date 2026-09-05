// 댓글 멘션 알림 + 자동 watcher 통합 테스트 (FR-MN-03) — 본체가 LargeClass 임계를 넘어 분리

package com.bts.issue.comment.application

import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueDomainEvent
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueMentioned
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.mention.MentionSource
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.watcher.repository.IssueWatcherRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * FR-MN-03 — 댓글 작성·수정의 `@멘션` 이 알림 이벤트와 자동 watcher 로 이어지는지.
 *
 * `CommentApplicationServiceTest` 에서 분리했다 — 8건을 더하자 detekt `LargeClass`(600줄)를
 * 넘겼고, **임계를 올리는 대신 관심사로 쪼갰다**(`IssueApplicationServiceMentionTest` 선례).
 *
 * 실 DB(Testcontainers)를 쓴다 — watcher 등록은 `ON CONFLICT DO NOTHING` 이라
 * 목으로는 멱등이 진짜인지 확인되지 않는다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CommentApplicationServiceMentionTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

    private lateinit var commentRepository: CommentRepository
    private lateinit var resolver: IssuePermissionResolver
    private lateinit var eventPublisher: IssueEventPublisher
    private lateinit var archiveGuard: ProjectArchiveGuard
    private lateinit var historyRecorder: IssueHistoryRecorder

    private val actorUuid: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val actor: ActorId = ActorId(actorUuid)

    @BeforeEach
    fun setupService() {
        commentRepository = CommentRepository(dsl)
        resolver =
            object : IssuePermissionResolver {
                override fun hasPermission(
                    actorId: UUID,
                    permission: IssuePermission,
                    scope: IssueScope,
                ): Boolean = true
            }
        eventPublisher = mockk(relaxed = true)
        archiveGuard = ProjectArchiveGuard(ProjectArchiveStateRepository(dsl))
        historyRecorder = mockk(relaxed = true)
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    private fun loadTaskTypeId(): IssueTypeId =
        DriverManager.getConnection(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        ).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "task 타입 seed 가 없습니다." }
                    IssueTypeId(rs.getLong("id"))
                }
            }
        }

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId)

    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "멘션 테스트 이슈 $seqNum",
                reporterId = actor,
                currentStateKey = "open",
            ),
        )

    // ── FR-MN-03 — 댓글 멘션 알림 + 자동 watcher ────────────────────────────

    private val bobUuid: UUID = UUID.fromString("bb000000-0000-4000-8000-0000000000b1")
    private val carolUuid: UUID = UUID.fromString("cc000000-0000-4000-8000-0000000000c1")

    /** bob·carol·me(=actor) 만 해석하는 포트. 그 외 username 은 미존재로 드롭된다. */
    private fun mentionLookupPort(): UserLookupPort =
        object : UserLookupPort {
            override fun exists(userId: UUID): Boolean = true

            override fun findIdsByUsernames(usernames: Set<String>): Map<String, UUID> {
                return mapOf("bob" to bobUuid, "carol" to carolUuid, "me" to actorUuid)
                    .filterKeys { it in usernames }
            }
        }

    /** 멘션 협력자를 붙인 서비스. 기존 sut 은 두 인자가 null 이라 멘션 경로를 타지 않는다. */
    private fun mentionService(watcherRepo: IssueWatcherRepository): CommentApplicationService =
        CommentApplicationService(
            commentRepository,
            repository,
            resolver,
            eventPublisher,
            archiveGuard,
            historyRecorder,
            Clock.systemUTC(),
            mentionLookupPort(),
            watcherRepo,
        )

    @Test
    @Order(40)
    fun `MN3-1 댓글 작성 시 본문 멘션 대상에게 IssueMentioned 를 발행한다`() {
        val issue = insertIssue(940)
        val watcherRepo = IssueWatcherRepository(dsl)
        val events = mutableListOf<IssueDomainEvent>()
        every { eventPublisher.publish(capture(events)) } returns Unit

        val comment = mentionService(watcherRepo).create(actor, issue.key, "@bob 확인 부탁")

        val mentioned = events.filterIsInstance<IssueMentioned>().single()
        mentioned.mentionedUserIds shouldBe listOf(bobUuid)
        mentioned.sourceField shouldBe MentionSource.COMMENT
        mentioned.commentId shouldBe comment.id
    }

    @Test
    @Order(41)
    fun `MN3-2 댓글 멘션 대상이 자동 watcher 로 등록된다`() {
        val issue = insertIssue(941)
        val watcherRepo = IssueWatcherRepository(dsl)

        mentionService(watcherRepo).create(actor, issue.key, "@bob @carol 보세요")

        val watchers = watcherRepo.listByIssue(issue.id.value).map { it.userId }
        watchers shouldContainAll listOf(bobUuid, carolUuid)
    }

    @Test
    @Order(42)
    fun `MN3-3 멘션 0건 댓글은 이벤트도 watcher 도 만들지 않는다`() {
        val issue = insertIssue(942)
        val watcherRepo = IssueWatcherRepository(dsl)
        val events = mutableListOf<IssueDomainEvent>()
        every { eventPublisher.publish(capture(events)) } returns Unit

        mentionService(watcherRepo).create(actor, issue.key, "멘션 없는 댓글")

        events.filterIsInstance<IssueMentioned>() shouldBe emptyList()
        watcherRepo.listByIssue(issue.id.value) shouldBe emptyList()
    }

    @Test
    @Order(43)
    fun `MN3-4 자기 자신만 멘션한 댓글은 이벤트도 watcher 도 없다`() {
        val issue = insertIssue(943)
        val watcherRepo = IssueWatcherRepository(dsl)
        val events = mutableListOf<IssueDomainEvent>()
        every { eventPublisher.publish(capture(events)) } returns Unit

        mentionService(watcherRepo).create(actor, issue.key, "@me 혼잣말")

        events.filterIsInstance<IssueMentioned>() shouldBe emptyList()
        watcherRepo.listByIssue(issue.id.value) shouldBe emptyList()
    }

    @Test
    @Order(44)
    fun `MN3-5 댓글 수정은 새로 추가된 멘션만 발행한다`() {
        val issue = insertIssue(944)
        val watcherRepo = IssueWatcherRepository(dsl)
        val svc = mentionService(watcherRepo)
        val comment = svc.create(actor, issue.key, "@bob 확인")

        val events = mutableListOf<IssueDomainEvent>()
        every { eventPublisher.publish(capture(events)) } returns Unit

        svc.update(actor, issue.key, comment.id, "@bob 확인 @carol 도")

        // bob 은 수정 전에 이미 있었으므로 재알림 대상이 아니다
        val mentioned = events.filterIsInstance<IssueMentioned>().single()
        mentioned.mentionedUserIds shouldBe listOf(carolUuid)
        mentioned.commentId shouldBe comment.id
    }

    @Test
    @Order(45)
    fun `MN3-6 멘션을 지우는 수정은 발행 0 이고 기존 watcher 를 유지한다`() {
        val issue = insertIssue(945)
        val watcherRepo = IssueWatcherRepository(dsl)
        val svc = mentionService(watcherRepo)
        val comment = svc.create(actor, issue.key, "@bob 확인")

        val events = mutableListOf<IssueDomainEvent>()
        every { eventPublisher.publish(capture(events)) } returns Unit

        svc.update(actor, issue.key, comment.id, "확인")

        events.filterIsInstance<IssueMentioned>() shouldBe emptyList()
        // E2 — 멘션을 지워도 이미 붙은 watcher 는 회수하지 않는다
        watcherRepo.listByIssue(issue.id.value).map { it.userId } shouldBe listOf(bobUuid)
    }

    @Test
    @Order(46)
    fun `MN3-7 본문이 같은 no-op 수정은 발행 0`() {
        val issue = insertIssue(946)
        val watcherRepo = IssueWatcherRepository(dsl)
        val svc = mentionService(watcherRepo)
        val comment = svc.create(actor, issue.key, "@bob 확인")

        val events = mutableListOf<IssueDomainEvent>()
        every { eventPublisher.publish(capture(events)) } returns Unit

        svc.update(actor, issue.key, comment.id, "@bob 확인")

        events.filterIsInstance<IssueMentioned>() shouldBe emptyList()
    }

    @Test
    @Order(47)
    fun `MN3-8 코드블록 안의 골뱅이는 멘션이 아니다`() {
        val issue = insertIssue(947)
        val watcherRepo = IssueWatcherRepository(dsl)
        val events = mutableListOf<IssueDomainEvent>()
        every { eventPublisher.publish(capture(events)) } returns Unit

        mentionService(watcherRepo).create(actor, issue.key, "```\n@bob\n```")

        events.filterIsInstance<IssueMentioned>() shouldBe emptyList()
        watcherRepo.listByIssue(issue.id.value) shouldBe emptyList()
    }
}

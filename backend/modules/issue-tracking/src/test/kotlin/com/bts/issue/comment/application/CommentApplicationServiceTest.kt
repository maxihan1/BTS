// CommentApplicationService 통합 테스트 — create(actor 강제)·createImported(원본 보존)·list 게이트/렌더링/정렬
// (FR-CO-01) + update(작성자 한정 — 모더레이터도 403) (FR-CO-02 Task 3)
// + delete(작성자 OR SOFT_DELETE 모더레이터 — 3클래스 전수) (FR-CO-02 Task 4)

package com.bts.issue.comment.application

import com.bts.issue.comment.application.CommentApplicationService.Companion.MAX_BODY_LENGTH
import com.bts.issue.comment.domain.Comment
import com.bts.issue.comment.domain.CommentBodyBlankException
import com.bts.issue.comment.domain.CommentBodyTooLongException
import com.bts.issue.comment.domain.CommentNotFoundException
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueCommentDeleted
import com.bts.issue.event.IssueCommented
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.project.archive.repository.ProjectArchiveStateRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.mockk.mockk
import io.mockk.spyk
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
import java.time.OffsetDateTime
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
 *
 * ## FR-CO-02 Task 3 시나리오 — [CommentApplicationService.update]
 * - CO2-1. 작성자는 자기 댓글을 수정한다 — body·updatedAt 갱신, createdAt·authorId 보존.
 * - **CO2-2. `SOFT_DELETE` 보유자여도 남의 댓글은 수정할 수 없다 (403).** ← 이 PR 의 핵심 판별자.
 * - CO2-3. UPDATE 권한 없으면 작성자여도 403 — 권한 판정이 작성자 판정보다 먼저다.
 * - CO2-4. 공백 본문 → [CommentBodyBlankException].
 * - CO2-5. 32,001자 → [CommentBodyTooLongException] / 32,000자 통과 (경계 양쪽).
 * - CO2-6. 수정 성공 시 [IssueHistoryRecorder.recordCommentEdited] 가 이전·이후 본문으로 1회 호출.
 * - CO2-7. 본문 동일 → 완전 no-op (DB 쓰기 0 · updatedAt 미갱신 · 이력 미기록).
 * - CO2-8. 다른 이슈 소속 commentId → [CommentNotFoundException].
 *
 * ## FR-CO-02 Task 4 시나리오 — [CommentApplicationService.delete]
 * 삭제 게이트는 `UPDATE` **AND** (작성자 **OR** `SOFT_DELETE`) 로, 수정 게이트(`UPDATE` AND 작성자)와
 * 술어가 다르다. 그래서 3클래스(작성자 / 모더레이터 / 제3자)를 **전수**로 고정한다 — 하나라도 빠지면
 * `OR` 의 한쪽 변이 검증되지 않은 채 남는다.
 * - CO2-9.  작성자는 `SOFT_DELETE` 가 없어도 자기 댓글을 삭제한다 (클래스 1 · 소프트 삭제 확인).
 * - CO2-10. `SOFT_DELETE` 보유자는 남의 댓글을 삭제한다 (클래스 2 — 모더레이션).
 * - CO2-11. `UPDATE` 는 있고 `SOFT_DELETE` 만 없는 제3자는 403 (클래스 3).
 * - CO2-12. 자동화가 만든 댓글을 룰 소유자가 아닌 `SOFT_DELETE` 보유자가 삭제한다 (ADR 근거 해소).
 * - CO2-13. `UPDATE` 권한 자체가 없으면 작성자여도 403 — 작성자 판정보다 먼저다.
 * - CO2-14. 이미 삭제된 댓글 재삭제는 [CommentNotFoundException] (최초 삭제 시각 보존).
 * - CO2-15. 다른 이슈 소속 commentId 는 [CommentNotFoundException].
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CommentApplicationServiceTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id. value class 는 lateinit 불가 → nullable var (CommentRepositoryTest 선례). */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var commentRepository: CommentRepository
    private lateinit var resolver: RecordingPermissionResolver
    private lateinit var eventPublisher: IssueEventPublisher
    private lateinit var archiveGuard: ProjectArchiveGuard
    private lateinit var historyRecorder: IssueHistoryRecorder
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
        historyRecorder = mockk(relaxed = true)
        service =
            CommentApplicationService(
                commentRepository,
                repository,
                resolver,
                eventPublisher,
                archiveGuard,
                historyRecorder,
            )
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * 지정 시각에 고정된 [Clock] 을 쓰는 서비스 인스턴스를 만든다.
     *
     * createdAt/updatedAt 을 결정적 값으로 고정해야 "updatedAt 이 갱신됐다/안 됐다"를 단정할 수
     * 있다. 시스템 시계로 만든 시각은 DB TIMESTAMPTZ(마이크로초) 왕복에서 정밀도가 잘릴 수 있어
     * 등치 비교의 판별력이 떨어진다.
     *
     * @param instant 고정할 시각.
     * @param repo 사용할 댓글 저장소. 기본값은 실 저장소이며, CO2-7 만 쓰기 호출 수를 세기 위해 spy 를 넘긴다.
     */
    private fun serviceAt(
        instant: Instant,
        repo: CommentRepository = commentRepository,
    ): CommentApplicationService =
        CommentApplicationService(
            repo,
            repository,
            resolver,
            eventPublisher,
            archiveGuard,
            historyRecorder,
            Clock.fixed(instant, ZoneOffset.UTC),
        )

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
            CommentApplicationService(
                commentRepository,
                repository,
                resolver,
                eventPublisher,
                archiveGuard,
                historyRecorder,
                fixedClock,
            )

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
            CommentApplicationService(
                commentRepository,
                repository,
                resolver,
                eventPublisher,
                archiveGuard,
                historyRecorder,
                fixedClock,
            )

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

    // ── CO2-1~CO2-8. update — 작성자 한정 수정 (FR-CO-02 Task 3) ───────────────

    /**
     * Given  actor 가 작성한 댓글 (2024-03-01 생성)
     * When   2030-01-01 로 고정된 시계로 update 호출
     * Then   body 와 updatedAt 은 갱신되고 createdAt·authorId 는 보존된다 (반환값 · DB 양쪽).
     */
    @Test
    @Order(15)
    fun `CO2-1 - 작성자는 자기 댓글을 수정한다 (body·updatedAt 갱신, createdAt·authorId 보존)`() {
        val issue = insertIssue(15L)
        val createdAt = Instant.parse("2024-03-01T00:00:00Z")
        val editedAt = Instant.parse("2030-01-01T00:00:00Z")
        val created = serviceAt(createdAt).create(actor, issue.key, "원본 본문")

        val updated = serviceAt(editedAt).update(actor, issue.key, created.id, "수정된 본문")

        assertThat(updated.body).isEqualTo("수정된 본문")
        assertThat(updated.updatedAt).isEqualTo(editedAt)
        assertThat(updated.createdAt).isEqualTo(createdAt)
        assertThat(updated.authorId).isEqualTo(actorUuid)

        val stored = requireNotNull(commentRepository.findActive(created.id, issue.id.value))
        assertThat(stored.body).isEqualTo("수정된 본문")
        assertThat(stored.updatedAt).isEqualTo(editedAt)
        assertThat(stored.createdAt).isEqualTo(createdAt)
        assertThat(stored.authorId).isEqualTo(actorUuid)
    }

    /**
     * ★ **이 PR 전체의 핵심 판별자.**
     *
     * Given  actor 가 [IssuePermission.UPDATE] 와 [IssuePermission.SOFT_DELETE] 를 **둘 다** 보유
     *        (모더레이터 — Task 4 의 삭제 게이트는 이 조합으로 통과한다)
     * When   authorUuid 가 쓴 **남의** 댓글에 update 호출
     * Then   [IssueAccessDeniedException] (403) 이고 원문은 그대로다.
     *
     * ## 왜 대조군이 필요한가 (vacuous 방지)
     * 권한이 없어서 403 이 나면 이 테스트는 "작성자 한정"을 전혀 검증하지 못한다. 그래서
     * (a) 두 권한 보유를 resolver 에 직접 물어 선언하고, (b) **같은 actor 가 자기 댓글은 수정에
     * 성공**함을 이어서 단정한다. 두 호출의 차이는 오직 "저작자가 누구인가" 뿐이다.
     *
     * ## 왜 모더레이터에게 수정을 허용하지 않는가 (ADR)
     * 실제 필요는 "지우기"이고, "남의 글 고치기"는 그 필요를 못 채우면서 기록 신뢰만 깎는다.
     * 관리자가 타인 명의 글의 내용을 바꿀 수 있으면 그 글이 원래 무엇이었는지 아무도 알 수 없다.
     */
    @Test
    @Order(16)
    fun `CO2-2 - SOFT_DELETE 보유자여도 남의 댓글은 수정할 수 없다 (403)`() {
        val issue = insertIssue(16L)
        val othersComment =
            buildComment(issue.id.value, body = "남의 원본", createdAt = Instant.parse("2024-03-01T00:00:00Z"))
        commentRepository.insert(othersComment)
        val ownComment = service.create(actor, issue.key, "내 원본")

        // (a) actor 는 UPDATE 와 SOFT_DELETE 를 모두 보유한다 — 403 이 권한 부족 탓이 아님을 고정.
        val scope = IssueScope.Issue(issue.key.value)
        assertThat(resolver.hasPermission(actorUuid, IssuePermission.UPDATE, scope)).isTrue()
        assertThat(resolver.hasPermission(actorUuid, IssuePermission.SOFT_DELETE, scope)).isTrue()

        assertThatThrownBy {
            service.update(actor, issue.key, othersComment.id, "가로챈 본문")
        }.isInstanceOf(IssueAccessDeniedException::class.java)

        assertThat(requireNotNull(commentRepository.findActive(othersComment.id, issue.id.value)).body)
            .isEqualTo("남의 원본")

        // (b) 대조군 — 같은 actor·같은 이슈·같은 권한인데 자기 댓글은 수정된다.
        assertThat(service.update(actor, issue.key, ownComment.id, "내 수정본").body).isEqualTo("내 수정본")
    }

    /**
     * Given  UPDATE 권한이 없는 actor (댓글의 **작성자 본인**)
     * When   update 호출
     * Then   403 — 권한 판정이 작성자 판정보다 먼저 일어난다 (이슈 존재 probe 방지).
     */
    @Test
    @Order(17)
    fun `CO2-3 - update 는 UPDATE 권한이 없으면 작성자여도 403`() {
        val issue = insertIssue(17L)
        val own = service.create(actor, issue.key, "원본 본문")
        resolver.deniedPermission = IssuePermission.UPDATE

        assertThatThrownBy {
            service.update(actor, issue.key, own.id, "수정된 본문")
        }.isInstanceOf(IssueAccessDeniedException::class.java)

        assertThat(requireNotNull(commentRepository.findActive(own.id, issue.id.value)).body).isEqualTo("원본 본문")
    }

    /**
     * Given  공백·개행만으로 이루어진 새 본문
     * When   update 호출
     * Then   [CommentBodyBlankException] — 수정으로 빈 댓글을 만들 수 없다. 원문 유지.
     */
    @Test
    @Order(18)
    fun `CO2-4 - update 는 공백만인 본문에 CommentBodyBlankException 을 던진다`() {
        val issue = insertIssue(18L)
        val own = service.create(actor, issue.key, "원본 본문")

        assertThatThrownBy {
            service.update(actor, issue.key, own.id, "   \n\t  ")
        }.isInstanceOf(CommentBodyBlankException::class.java)

        assertThat(requireNotNull(commentRepository.findActive(own.id, issue.id.value)).body).isEqualTo("원본 본문")
    }

    /**
     * Given  [MAX_BODY_LENGTH] + 1 자 / [MAX_BODY_LENGTH] 자 본문
     * When   update 호출
     * Then   초과분은 [CommentBodyTooLongException], 경계값은 통과.
     *
     * 경계 양쪽을 한 테스트에서 본다 — 한쪽만 두면 off-by-one 이 잡히지 않는다 (CO-1/CO-2 와 동일 근거).
     * `create` 와 같은 상한을 쓰는지도 함께 고정한다. 수정 경로만 상한이 풀리면 우회로가 된다.
     */
    @Test
    @Order(19)
    fun `CO2-5 - update 는 32001자에 CommentBodyTooLongException, 32000자는 통과`() {
        val issue = insertIssue(19L)
        val own = service.create(actor, issue.key, "원본 본문")

        assertThatThrownBy {
            service.update(actor, issue.key, own.id, "가".repeat(MAX_BODY_LENGTH + 1))
        }.isInstanceOf(CommentBodyTooLongException::class.java)
        assertThat(requireNotNull(commentRepository.findActive(own.id, issue.id.value)).body).isEqualTo("원본 본문")

        val updated = service.update(actor, issue.key, own.id, "가".repeat(MAX_BODY_LENGTH))

        assertThat(updated.body).hasSize(MAX_BODY_LENGTH)
    }

    /**
     * Given  "이전 본문" 댓글
     * When   "이후 본문" 으로 update
     * Then   [IssueHistoryRecorder.recordCommentEdited] 가 이전·이후 본문으로 **정확히 1회** 호출된다.
     *
     * 인자를 `any()` 없이 실값으로 단정한다 — 이력에 무엇이 실렸는지가 계약이기 때문이다.
     */
    @Test
    @Order(20)
    fun `CO2-6 - update 성공 시 recordCommentEdited 가 이전·이후 본문으로 1회 호출된다`() {
        val issue = insertIssue(20L)
        val own = service.create(actor, issue.key, "이전 본문")

        service.update(actor, issue.key, own.id, "이후 본문")

        verify(exactly = 1) {
            historyRecorder.recordCommentEdited(
                issueId = issue.id.value,
                issueKey = issue.key.value,
                actor = actor,
                commentId = own.id,
                beforeBody = "이전 본문",
                afterBody = "이후 본문",
            )
        }
    }

    /**
     * Given  "동일 본문" 댓글 (2024-03-01 생성)
     * When   같은 본문으로 update (시계는 2030-01-01 로 고정 — 갱신되면 반드시 티가 난다)
     * Then   **완전 no-op** — [CommentRepository.updateBody] 호출 0회, updatedAt 미갱신, 이력 미기록.
     *
     * 저장소를 [spyk] 로 감싸 "DB 쓰기 0" 을 시각 비교와 **독립적으로** 센다. 시각 단정만 두면
     * "우연히 같은 시각으로 덮어썼다" 를 구분하지 못한다.
     *
     * 근거. 내용이 안 바뀌었는데 updatedAt 이 갱신되면 화면의 "(수정됨)" 표시가 거짓말을 한다.
     */
    @Test
    @Order(21)
    fun `CO2-7 - 본문이 동일하면 완전 no-op (DB 쓰기 0·updatedAt 미갱신·이력 미기록)`() {
        val issue = insertIssue(21L)
        val createdAt = Instant.parse("2024-03-01T00:00:00Z")
        val created = serviceAt(createdAt).create(actor, issue.key, "동일 본문")
        val spyRepository = spyk(commentRepository)

        val result =
            serviceAt(Instant.parse("2030-01-01T00:00:00Z"), spyRepository)
                .update(actor, issue.key, created.id, "동일 본문")

        verify(exactly = 0) { spyRepository.updateBody(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { historyRecorder.recordCommentEdited(any(), any(), any(), any(), any(), any()) }
        assertThat(result.updatedAt).isEqualTo(createdAt)
        assertThat(requireNotNull(commentRepository.findActive(created.id, issue.id.value)).updatedAt)
            .isEqualTo(createdAt)
    }

    /**
     * Given  이슈 A 에 달린 댓글 + 같은 actor 가 볼 수 있는 이슈 B
     * When   경로에는 B, 본문에는 A 의 commentId 로 update 호출
     * Then   [CommentNotFoundException] — 소속 대조가 저장소 `WHERE` 에 있어 통과할 수 없다.
     *
     * commentId 는 전역 UUID 라 이 대조가 없으면 "내가 볼 수 있는 아무 이슈 키 + 남의 댓글 id"
     * 조합으로 이슈 단위 권한 검사를 우회할 수 있다.
     */
    @Test
    @Order(22)
    fun `CO2-8 - 다른 이슈 소속 commentId 는 CommentNotFoundException`() {
        val issueA = insertIssue(22L)
        val issueB = insertIssue(23L)
        val commentOnA = service.create(actor, issueA.key, "A 의 댓글")

        assertThatThrownBy {
            service.update(actor, issueB.key, commentOnA.id, "가로챈 본문")
        }.isInstanceOf(CommentNotFoundException::class.java)

        assertThat(requireNotNull(commentRepository.findActive(commentOnA.id, issueA.id.value)).body)
            .isEqualTo("A 의 댓글")
    }

    // ── CO2-9~CO2-15. delete — 작성자 OR SOFT_DELETE 모더레이터 (FR-CO-02 Task 4) ─

    /**
     * **삭제 3클래스 중 클래스 1 — 작성자 본인.**
     *
     * Given  actor 가 작성한 댓글. actor 는 [IssuePermission.UPDATE] 는 보유하되
     *        [IssuePermission.SOFT_DELETE] 는 **미보유**다.
     * When   2030-01-01 로 고정된 시계로 delete 호출
     * Then   삭제된다. 행은 물리적으로 남고 `deleted_at` 만 채워진다 (소프트 삭제 — DEVELOPMENT.md §1.2 #7).
     *
     * ## 왜 `SOFT_DELETE` 를 일부러 뺐는가
     * 모더레이터 분기를 **던지는** 판정(`checkPermission`)으로 구현하면 `SOFT_DELETE` 미보유자는
     * 작성자여도 즉시 403 이 되어 작성자 경로가 조용히 죽는다. 이 테스트가 그 회귀의 유일한
     * 판별자다 — 질의형 `hasPermission` 을 던지는 판정으로 바꾸면 정확히 이 한 건이 실패한다.
     */
    @Test
    @Order(23)
    fun `CO2-9 - 작성자는 SOFT_DELETE 가 없어도 자기 댓글을 삭제한다 (소프트 삭제)`() {
        val issue = insertIssue(24L)
        val deletedAt = Instant.parse("2030-01-01T00:00:00Z")
        val own = service.create(actor, issue.key, "내 댓글")
        resolver.deniedPermission = IssuePermission.SOFT_DELETE

        serviceAt(deletedAt).delete(actor, issue.key, own.id)

        assertThat(commentRepository.findActive(own.id, issue.id.value)).isNull()
        assertThat(commentRepository.listByIssue(issue.id.value)).isEmpty()
        // 행이 남아 있어야 읽힌다 — 읽히면 하드 삭제가 아니다.
        assertThat(readDeletedAt(own.id)).isEqualTo(deletedAt)
    }

    /**
     * **삭제 3클래스 중 클래스 2 — 모더레이터.** ★ 삭제 게이트가 수정 게이트와 갈라지는 지점.
     *
     * Given  authorUuid 가 쓴 **남의** 댓글. actor 는 `UPDATE` 와 `SOFT_DELETE` 를 **둘 다** 보유.
     * When   delete 호출
     * Then   삭제된다.
     *
     * 같은 권한 조합으로 `update` 는 403 이다(CO2-2). 두 테스트가 쌍으로 "삭제만 모더레이션이
     * 열린다" 를 고정한다 — 게이트를 공용 헬퍼로 합치면 CO2-2 가 죽는다.
     */
    @Test
    @Order(24)
    fun `CO2-10 - SOFT_DELETE 보유자는 남의 댓글을 삭제한다 (모더레이션)`() {
        val issue = insertIssue(25L)
        val others =
            buildComment(issue.id.value, body = "남의 댓글", createdAt = Instant.parse("2024-03-01T00:00:00Z"))
        commentRepository.insert(others)
        assertThat(others.authorId).isNotEqualTo(actorUuid)

        val scope = IssueScope.Issue(issue.key.value)
        assertThat(resolver.hasPermission(actorUuid, IssuePermission.UPDATE, scope)).isTrue()
        assertThat(resolver.hasPermission(actorUuid, IssuePermission.SOFT_DELETE, scope)).isTrue()

        service.delete(actor, issue.key, others.id)

        assertThat(commentRepository.findActive(others.id, issue.id.value)).isNull()
    }

    /**
     * **모더레이션 통지 — FR-CO-02 의 빠진 절반.**
     *
     * 삭제는 작성자 OR `SOFT_DELETE` 보유자가 할 수 있는데, **내 댓글이 모더레이터에게 지워져도
     * 아무 신호가 없었다.** 감사 이력에는 남지만 그건 조회해야 보이는 기록이지 밀어주는 신호가 아니다.
     *
     * ★[IssueCommentDeleted.commentAuthorId] 가 **삭제자(actor)가 아니라 작성자**인지가 판별자다.
     * 두 값을 바꿔 실으면 알림이 엉뚱한 사람에게 가고, 정작 당사자는 여전히 모른다.
     * 그래서 둘이 **서로 다른 상황**(모더레이션 삭제)에서 각각을 단정한다 — 자기 삭제로 검증하면
     * actor == author 라 뒤바뀜을 잡지 못한다(vacuous).
     */
    @Test
    @Order(24)
    fun `CO2-10b - 모더레이터 삭제는 작성자 id 를 실은 IssueCommentDeleted 를 발행한다`() {
        val issue = insertIssue(35L)
        val others =
            buildComment(issue.id.value, body = "남의 댓글", createdAt = Instant.parse("2024-03-01T00:00:00Z"))
        commentRepository.insert(others)
        // 판별자 전제 — actor 와 작성자가 서로 달라야 뒤바뀜을 잡을 수 있다.
        assertThat(others.authorId).isNotEqualTo(actorUuid)

        service.delete(actor, issue.key, others.id)

        verify(exactly = 1) {
            eventPublisher.publish(
                match {
                    it is IssueCommentDeleted &&
                        it.issueKey == issue.key &&
                        it.projectKey == issue.key.projectPrefix &&
                        it.commentId == others.id &&
                        // 수신자 = 작성자
                        it.commentAuthorId.value == others.authorId &&
                        // 자기제외 판정용 = 삭제 수행자
                        it.actorId == actor
                },
            )
        }
    }

    /**
     * **삭제 3클래스 중 클래스 3 — 제3자.**
     *
     * Given  authorUuid 가 쓴 **남의** 댓글. actor 는 `UPDATE` 는 보유하고 `SOFT_DELETE` 만 **미보유**.
     * When   delete 호출
     * Then   [IssueAccessDeniedException] (403) 이고 댓글은 그대로 살아 있다.
     *
     * ## vacuous 방지
     * `UPDATE` 까지 없으면 어느 쪽 때문에 403 인지 구분되지 않아 이 테스트가 아무것도 검증하지 못한다.
     * 그래서 `UPDATE` 보유 · `SOFT_DELETE` 미보유를 resolver 에 직접 물어 선언한다.
     * CO2-9 와의 차이는 오직 **"작성자인가"** 뿐이다 — `OR` 의 왼쪽 변만 다르다.
     */
    @Test
    @Order(25)
    fun `CO2-11 - UPDATE 만 있고 SOFT_DELETE 없는 제3자는 남의 댓글을 삭제할 수 없다 (403)`() {
        val issue = insertIssue(26L)
        val others =
            buildComment(issue.id.value, body = "남의 댓글", createdAt = Instant.parse("2024-03-01T00:00:00Z"))
        commentRepository.insert(others)
        resolver.deniedPermission = IssuePermission.SOFT_DELETE

        val scope = IssueScope.Issue(issue.key.value)
        assertThat(resolver.hasPermission(actorUuid, IssuePermission.UPDATE, scope)).isTrue()
        assertThat(resolver.hasPermission(actorUuid, IssuePermission.SOFT_DELETE, scope)).isFalse()

        assertThatThrownBy {
            service.delete(actor, issue.key, others.id)
        }.isInstanceOf(IssueAccessDeniedException::class.java)

        assertThat(requireNotNull(commentRepository.findActive(others.id, issue.id.value)).body)
            .isEqualTo("남의 댓글")
    }

    /**
     * ADR 이 모더레이션을 도입한 **근거 자체**를 검증한다.
     *
     * 근거. 자동화 룰이 남긴 댓글은 `authorId` 가 **룰 소유자**라, 작성자 한정 삭제만 있으면
     * 룰 소유자 외에는 아무도 못 지워 사실상 방치된다. 근거로 쓴 이상 그 근거가 실제로 해소되는지도
     * 확인해야 한다.
     *
     * Given  룰 소유자(authorUuid)를 actor 로 만든 댓글 — automation `AddCommentAction` 의 경로와 동일
     * When   룰 소유자가 아닌 `SOFT_DELETE` 보유자(actor)가 delete 호출
     * Then   삭제된다.
     */
    @Test
    @Order(26)
    fun `CO2-12 - 자동화가 만든 댓글을 룰 소유자가 아닌 SOFT_DELETE 보유자가 삭제한다`() {
        val issue = insertIssue(27L)
        val ruleOwner = ActorId(authorUuid)
        val automationComment = service.create(ruleOwner, issue.key, "자동화가 남긴 댓글")

        assertThat(automationComment.authorId).isEqualTo(authorUuid)
        assertThat(automationComment.authorId).isNotEqualTo(actorUuid)

        service.delete(actor, issue.key, automationComment.id)

        assertThat(commentRepository.findActive(automationComment.id, issue.id.value)).isNull()
    }

    /**
     * Given  `UPDATE` 권한이 없는 actor (댓글의 **작성자 본인**, `SOFT_DELETE` 는 보유)
     * When   delete 호출
     * Then   403 — 공통 전제인 `UPDATE` 가 작성자·모더레이터 판정보다 먼저다 (이슈 존재 probe 방지).
     *
     * `SOFT_DELETE` 를 남겨둔 것이 의도다. 모더레이터 분기만으로는 통과할 조건이므로,
     * 이 테스트가 실패한다면 `UPDATE` 전제 게이트가 사라졌다는 뜻이다.
     */
    @Test
    @Order(27)
    fun `CO2-13 - delete 는 UPDATE 권한이 없으면 작성자여도 403`() {
        val issue = insertIssue(28L)
        val own = service.create(actor, issue.key, "원본 본문")
        resolver.deniedPermission = IssuePermission.UPDATE

        assertThatThrownBy {
            service.delete(actor, issue.key, own.id)
        }.isInstanceOf(IssueAccessDeniedException::class.java)

        assertThat(requireNotNull(commentRepository.findActive(own.id, issue.id.value)).body).isEqualTo("원본 본문")
    }

    /**
     * Given  이미 삭제된 댓글 (2030-01-01 삭제)
     * When   2031-01-01 시계로 다시 delete 호출
     * Then   [CommentNotFoundException] 이고 **최초** 삭제 시각이 보존된다.
     *
     * 재삭제가 `deleted_at` 을 덮어쓰면 "언제 지워졌나" 라는 감사 정보가 소실된다.
     * 저장소 `WHERE` 의 `deleted_at IS NULL` 이 그 덮어쓰기를 막는다 — 시각 단정이 그 판별자다.
     */
    @Test
    @Order(28)
    fun `CO2-14 - 이미 삭제된 댓글 재삭제는 CommentNotFoundException`() {
        val issue = insertIssue(29L)
        val firstDeletedAt = Instant.parse("2030-01-01T00:00:00Z")
        val own = service.create(actor, issue.key, "내 댓글")
        serviceAt(firstDeletedAt).delete(actor, issue.key, own.id)

        assertThatThrownBy {
            serviceAt(Instant.parse("2031-01-01T00:00:00Z")).delete(actor, issue.key, own.id)
        }.isInstanceOf(CommentNotFoundException::class.java)

        assertThat(readDeletedAt(own.id)).isEqualTo(firstDeletedAt)
    }

    /**
     * Given  이슈 A 에 달린 댓글 + 같은 actor 가 볼 수 있는 이슈 B
     * When   경로에는 B, 본문에는 A 의 commentId 로 delete 호출
     * Then   [CommentNotFoundException] — 소속 대조가 저장소 `WHERE` 에 있어 통과할 수 없다.
     *
     * commentId 는 전역 UUID 라 이 대조가 없으면 "내가 볼 수 있는 아무 이슈 키 + 남의 댓글 id"
     * 조합으로 이슈 단위 권한 검사를 우회해 **삭제**까지 할 수 있다 (CO2-8 의 삭제판).
     */
    @Test
    @Order(29)
    fun `CO2-15 - delete 는 다른 이슈 소속 commentId 에 CommentNotFoundException`() {
        val issueA = insertIssue(30L)
        val issueB = insertIssue(31L)
        val commentOnA = service.create(actor, issueA.key, "A 의 댓글")

        assertThatThrownBy {
            service.delete(actor, issueB.key, commentOnA.id)
        }.isInstanceOf(CommentNotFoundException::class.java)

        assertThat(requireNotNull(commentRepository.findActive(commentOnA.id, issueA.id.value)).body)
            .isEqualTo("A 의 댓글")
    }

    /**
     * `comments` 행의 `deleted_at` 을 저장소를 거치지 않고 직접 읽는다.
     *
     * 저장소 조회는 `deleted_at IS NULL` 을 항상 붙이므로 삭제된 행을 볼 수 없다. 그래서
     * "행이 물리적으로 남아 있는가(= 하드 삭제가 아닌가)" 와 "언제 지워졌는가" 는 원시 SQL 로만
     * 확인할 수 있다.
     *
     * @param commentId 확인할 댓글 UUID.
     * @return `deleted_at` 값. 활성 행이면 null.
     * @throws IllegalStateException 행이 아예 없을 때 — 물리 삭제는 규칙 위반이다.
     */
    private fun readDeletedAt(commentId: UUID): Instant? =
        withJdbcConnection { conn ->
            conn.prepareStatement("SELECT deleted_at FROM comments WHERE id = ?").use { stmt ->
                stmt.setObject(1, commentId)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "댓글 행이 물리 삭제됐습니다 — 소프트 삭제여야 합니다 (DEVELOPMENT.md §1.2 #7)." }
                    rs.getObject(1, OffsetDateTime::class.java)?.toInstant()
                }
            }
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

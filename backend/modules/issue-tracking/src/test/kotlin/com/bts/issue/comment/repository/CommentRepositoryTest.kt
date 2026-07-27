// CommentRepository insert + listByIssue(created_at ASC) + 소프트 삭제 제외 Testcontainers 통합 테스트 (FR-IM-01 PR3 Task 2)

package com.bts.issue.comment.repository

import com.bts.issue.comment.domain.Comment
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.OffsetDateTime
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
 *
 * 테스트 시나리오 (FR-CO-02 Task 1 — findActive / updateBody / softDelete).
 * - findActive 3건. 활성 조회 · 다른 이슈 소속 차단 · 삭제된 건 차단.
 * - updateBody 3건. 본문·updatedAt 갱신 + createdAt·authorId 보존 · 삭제된 건 0 · 타 이슈 소속 0.
 * - softDelete 3건. deleted_at 기록 + listByIssue 제외 · 재삭제 0(최초 시각 보존) · 타 이슈 소속 0.
 *
 * 세 메서드 모두 `issueId` 를 받으므로 "다른 이슈 소속 차단" 을 **세 번 각각** 검증한다
 * (FR-CO-01 §D4 — 소속 대조를 상위 계층의 성실성에 맡기지 않고 쿼리 술어로 고정).
 *
 * 테스트 시나리오 (FR-CO-02 Task 11 — findActiveIds).
 * - 4건. 활성만 반환 · 삭제 제외 · 타 이슈 소속 제외 · 빈 입력 시 쿼리 미실행.
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

    /**
     * 소프트 삭제 필터를 우회해 `body` 를 직접 읽는다.
     *
     * 리포지토리 조회 API 는 `deleted_at IS NULL` 을 강제하므로, 삭제된 행이 정말 그대로인지는
     * 리포지토리로 확인할 수 없다. "0 을 반환했다" 만으로는 UPDATE 가 안 나갔다는 증거가 되지 않아
     * (반환값만 맞추고 실제로는 갱신되는 구현도 통과) DB 상태를 직접 대조한다.
     */
    @Suppress("NestedBlockDepth")
    private fun readBody(id: UUID): String? =
        withJdbcConnection { conn ->
            conn.prepareStatement("SELECT body FROM comments WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    /** 소프트 삭제 필터를 우회해 `deleted_at` 을 직접 읽는다. NULL(활성)이면 null. */
    @Suppress("NestedBlockDepth")
    private fun readDeletedAt(id: UUID): Instant? =
        withJdbcConnection { conn ->
            conn.prepareStatement("SELECT deleted_at FROM comments WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "댓글 행이 존재하지 않습니다. id=$id" }
                    rs.getObject(1, OffsetDateTime::class.java)?.toInstant()
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

    // ── findActive ────────────────────────────────────────────────────────────

    /**
     * Given  활성 댓글 1건
     * When   findActive(id, issueId)
     * Then   해당 댓글을 반환하고 모든 필드가 삽입값과 일치.
     */
    @Test
    @Order(3)
    fun `findActive 는 활성 댓글을 반환한다`() {
        val issue = insertIssue(1L)
        val createdAt = Instant.parse("2024-03-01T10:00:00Z")
        val comment = buildComment(issue.id.value, body = "활성 댓글", createdAt = createdAt)
        commentRepository.insert(comment)

        val found = commentRepository.findActive(comment.id, issue.id.value)

        val actual = requireNotNull(found) { "활성 댓글은 findActive 로 조회돼야 한다." }
        assertThat(actual.id).isEqualTo(comment.id)
        assertThat(actual.issueId).isEqualTo(issue.id.value)
        assertThat(actual.authorId).isEqualTo(comment.authorId)
        assertThat(actual.body).isEqualTo("활성 댓글")
        assertThat(actual.createdAt).isEqualTo(createdAt)
        assertThat(actual.updatedAt).isEqualTo(createdAt)
    }

    /**
     * Given  이슈 A 에 속한 댓글 1건 + 무관한 이슈 B
     * When   findActive(commentId, issueB.id) — 경로 위조 시뮬레이션
     * Then   null. 같은 commentId 를 이슈 A 로 조회하면 조회되므로 null 의 원인은 issueId 대조뿐.
     */
    @Test
    @Order(4)
    fun `findActive 는 다른 이슈 소속 commentId 에 null 을 반환한다`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)
        val comment = buildComment(issueA.id.value, body = "이슈 A 댓글")
        commentRepository.insert(comment)

        val crossIssue = commentRepository.findActive(comment.id, issueB.id.value)

        assertThat(crossIssue).isNull()
        // 대조군 — 올바른 issueId 로는 조회된다. 없으면 "무조건 null" 구현도 위 단언을 통과한다.
        assertThat(commentRepository.findActive(comment.id, issueA.id.value)).isNotNull
    }

    /**
     * Given  활성 댓글 1건 (삭제 전 조회 가능함을 먼저 확인)
     * When   소프트 삭제 후 findActive
     * Then   null. 대조군 덕분에 null 의 원인은 deleted_at 필터임이 확정된다.
     */
    @Test
    @Order(5)
    fun `findActive 는 이미 삭제된 댓글에 null 을 반환한다`() {
        val issue = insertIssue(1L)
        val comment = buildComment(issue.id.value, body = "삭제될 댓글")
        commentRepository.insert(comment)
        assertThat(commentRepository.findActive(comment.id, issue.id.value)).isNotNull

        softDeleteComment(comment.id)

        assertThat(commentRepository.findActive(comment.id, issue.id.value)).isNull()
    }

    // ── updateBody ────────────────────────────────────────────────────────────

    /**
     * Given  활성 댓글 1건
     * When   updateBody(id, 새 본문, 새 updatedAt)
     * Then   1 반환 + body/updatedAt 갱신, createdAt·authorId 는 그대로.
     */
    @Test
    @Order(6)
    fun `updateBody 는 본문과 updatedAt 을 갱신하고 createdAt·authorId 는 보존한다`() {
        val issue = insertIssue(1L)
        val createdAt = Instant.parse("2024-03-01T10:00:00Z")
        val comment = buildComment(issue.id.value, body = "원본 본문", createdAt = createdAt)
        commentRepository.insert(comment)
        val editedAt = Instant.parse("2024-03-02T11:30:00Z")

        val affected = commentRepository.updateBody(comment.id, issue.id.value, "수정된 본문", editedAt)

        assertThat(affected).isEqualTo(1)
        val reloaded = requireNotNull(commentRepository.findActive(comment.id, issue.id.value))
        assertThat(reloaded.body).isEqualTo("수정된 본문")
        assertThat(reloaded.updatedAt).isEqualTo(editedAt)
        assertThat(reloaded.createdAt).isEqualTo(createdAt)
        assertThat(reloaded.authorId).isEqualTo(comment.authorId)
    }

    /**
     * Given  소프트 삭제된 댓글 1건
     * When   updateBody
     * Then   0 반환 + 본문 미변경. 서비스가 이 0 을 404 판정 근거로 쓴다.
     */
    @Test
    @Order(7)
    fun `updateBody 는 이미 삭제된 댓글에 0 을 반환한다`() {
        val issue = insertIssue(1L)
        val comment = buildComment(issue.id.value, body = "삭제된 원본 본문")
        commentRepository.insert(comment)
        softDeleteComment(comment.id)

        val affected =
            commentRepository.updateBody(
                comment.id,
                issue.id.value,
                "되살리기 시도",
                Instant.parse("2024-03-02T11:30:00Z"),
            )

        assertThat(affected).isZero()
        // 반환값 0 과 별개로 실제 UPDATE 가 나가지 않았는지 DB 상태로 확인한다.
        assertThat(readBody(comment.id)).isEqualTo("삭제된 원본 본문")
    }

    /**
     * Given  이슈 A 에 속한 활성 댓글 1건 + 무관한 이슈 B
     * When   updateBody(commentId, issueB.id, ...) — 경로 위조 시뮬레이션
     * Then   0 반환 + 본문 미변경. 소속 대조가 서비스가 아닌 쿼리 술어에 고정돼 있음을 증명한다.
     */
    @Test
    @Order(10)
    fun `updateBody 는 다른 이슈 소속 commentId 에 0 을 반환한다`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)
        val comment = buildComment(issueA.id.value, body = "이슈 A 원본 본문")
        commentRepository.insert(comment)
        val editedAt = Instant.parse("2024-03-02T11:30:00Z")

        val affected = commentRepository.updateBody(comment.id, issueB.id.value, "위조 경로 수정 시도", editedAt)

        assertThat(affected).isZero()
        assertThat(readBody(comment.id)).isEqualTo("이슈 A 원본 본문")
        // 대조군 — 올바른 issueId 로는 갱신된다. 없으면 "무조건 0" 구현도 위 단언을 통과한다.
        assertThat(commentRepository.updateBody(comment.id, issueA.id.value, "정상 수정", editedAt)).isEqualTo(1)
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    /**
     * Given  같은 이슈에 활성 댓글 2건
     * When   1건만 softDelete
     * Then   1 반환 + 대상만 deleted_at 기록, 나머지는 활성 유지, listByIssue 에서 대상 제외.
     */
    @Test
    @Order(8)
    fun `softDelete 는 deleted_at 을 채우고 listByIssue 에서 사라진다`() {
        val issue = insertIssue(1L)
        val target = buildComment(issue.id.value, body = "삭제 대상")
        val survivor = buildComment(issue.id.value, body = "남을 댓글")
        commentRepository.insert(target)
        commentRepository.insert(survivor)
        val deletedAt = Instant.parse("2024-03-03T12:00:00Z")

        val affected = commentRepository.softDelete(target.id, issue.id.value, deletedAt)

        assertThat(affected).isEqualTo(1)
        assertThat(readDeletedAt(target.id)).isEqualTo(deletedAt)
        // WHERE 절이 빠진 전체 UPDATE 였다면 여기서 걸린다.
        assertThat(readDeletedAt(survivor.id)).isNull()
        assertThat(commentRepository.listByIssue(issue.id.value).map { it.id }).containsExactly(survivor.id)
    }

    /**
     * Given  이미 softDelete 된 댓글 1건
     * When   같은 댓글을 다시 softDelete
     * Then   0 반환 + 최초 삭제 시각이 덮어써지지 않음.
     */
    @Test
    @Order(9)
    fun `softDelete 는 이미 삭제된 댓글에 0 을 반환한다`() {
        val issue = insertIssue(1L)
        val comment = buildComment(issue.id.value, body = "두 번 삭제될 댓글")
        commentRepository.insert(comment)
        val firstDeletedAt = Instant.parse("2024-03-03T12:00:00Z")
        assertThat(commentRepository.softDelete(comment.id, issue.id.value, firstDeletedAt)).isEqualTo(1)

        val affected = commentRepository.softDelete(comment.id, issue.id.value, Instant.parse("2024-03-04T13:00:00Z"))

        assertThat(affected).isZero()
        // 재삭제가 실제로 차단됐는지 — 반환값 0 만으로는 deleted_at 덮어쓰기를 배제하지 못한다.
        assertThat(readDeletedAt(comment.id)).isEqualTo(firstDeletedAt)
    }

    /**
     * Given  이슈 A 에 속한 활성 댓글 1건 + 무관한 이슈 B
     * When   softDelete(commentId, issueB.id, ...) — 경로 위조 시뮬레이션
     * Then   0 반환 + deleted_at 여전히 NULL(활성 유지).
     */
    @Test
    @Order(11)
    fun `softDelete 는 다른 이슈 소속 commentId 에 0 을 반환한다`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)
        val comment = buildComment(issueA.id.value, body = "이슈 A 댓글")
        commentRepository.insert(comment)
        val deletedAt = Instant.parse("2024-03-03T12:00:00Z")

        val affected = commentRepository.softDelete(comment.id, issueB.id.value, deletedAt)

        assertThat(affected).isZero()
        assertThat(readDeletedAt(comment.id)).isNull()
        // 대조군 — 올바른 issueId 로는 삭제된다. 없으면 "무조건 0" 구현도 위 단언을 통과한다.
        assertThat(commentRepository.softDelete(comment.id, issueA.id.value, deletedAt)).isEqualTo(1)
    }

    // ── findActiveIds (FR-CO-02 Task 11 — 삭제 댓글 이력 마스킹 판정용 배치 조회) ──────

    /**
     * Given  같은 이슈에 활성 댓글 2건 + DB 에 없는 id 1건
     * When   findActiveIds(3개 id, issueId)
     * Then   실재하는 활성 2건만 반환. 미존재 id 는 조용히 빠진다.
     *
     * 미존재 id 를 섞는 이유. 마스킹 판정은 "활성 목록에 없으면 가린다" 이므로, 없는 id 에
     * 예외를 던지면 이력 조회 전체가 죽는다. 빠짐 = 마스킹(fail-closed) 이 정답이다.
     */
    @Test
    @Order(12)
    fun `findActiveIds 는 활성 댓글 id 만 반환한다`() {
        val issue = insertIssue(1L)
        val c1 = buildComment(issue.id.value, body = "활성 댓글 1")
        val c2 = buildComment(issue.id.value, body = "활성 댓글 2")
        commentRepository.insert(c1)
        commentRepository.insert(c2)
        val unknownId = UUID.randomUUID()

        val active = commentRepository.findActiveIds(setOf(c1.id, c2.id, unknownId), issue.id.value)

        assertThat(active).containsExactlyInAnyOrder(c1.id, c2.id)
    }

    /**
     * Given  같은 이슈에 활성 댓글 2건
     * When   1건 소프트 삭제 후 같은 id 집합으로 findActiveIds
     * Then   삭제된 id 는 빠지고 활성 id 만 남는다.
     */
    @Test
    @Order(13)
    fun `findActiveIds 는 삭제된 댓글 id 를 제외한다`() {
        val issue = insertIssue(1L)
        val survivor = buildComment(issue.id.value, body = "남을 댓글")
        val deleted = buildComment(issue.id.value, body = "삭제될 댓글")
        commentRepository.insert(survivor)
        commentRepository.insert(deleted)
        val ids = setOf(survivor.id, deleted.id)
        // 대조군 — 삭제 전에는 둘 다 나온다. 없으면 "항상 1건만" 구현도 아래 단언을 통과한다.
        assertThat(commentRepository.findActiveIds(ids, issue.id.value))
            .containsExactlyInAnyOrder(survivor.id, deleted.id)

        softDeleteComment(deleted.id)

        assertThat(commentRepository.findActiveIds(ids, issue.id.value)).containsExactly(survivor.id)
    }

    /**
     * Given  이슈 A·B 에 각각 활성 댓글 1건
     * When   두 id 를 한 집합으로 묶어 각 이슈로 findActiveIds
     * Then   각자 자기 이슈 소속 id 만 반환. 양방향 대조로 "issueId 를 무시하는 구현" 을 배제한다.
     */
    @Test
    @Order(14)
    fun `findActiveIds 는 다른 이슈 소속 id 를 제외한다`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)
        val commentA = buildComment(issueA.id.value, body = "이슈 A 댓글")
        val commentB = buildComment(issueB.id.value, body = "이슈 B 댓글")
        commentRepository.insert(commentA)
        commentRepository.insert(commentB)
        val ids = setOf(commentA.id, commentB.id)

        assertThat(commentRepository.findActiveIds(ids, issueA.id.value)).containsExactly(commentA.id)
        assertThat(commentRepository.findActiveIds(ids, issueB.id.value)).containsExactly(commentB.id)
    }

    /**
     * Given  스텁이 하나도 없는 strict mock [DSLContext] 로 조립한 저장소
     * When   findActiveIds(빈 집합, 임의 issueId)
     * Then   빈 집합 반환 + DSLContext 를 한 번도 건드리지 않는다.
     *
     * 실제 DB 로는 "빈 결과" 와 "쿼리를 안 보냄" 을 구분할 수 없어 왕복 제거를 증명하지 못한다.
     * strict mock 은 스텁하지 않은 호출에 예외를 던지므로, 구현이 `dsl` 을 한 번이라도 만지면
     * 이 테스트가 죽는다 — 조기 반환이 실재한다는 직접 증거다.
     */
    @Test
    @Order(15)
    fun `findActiveIds 는 빈 입력에 빈 집합을 반환한다 (쿼리 미실행)`() {
        val unusedDsl = mockk<DSLContext>()
        val repositoryOnMockDsl = CommentRepository(unusedDsl)

        val active = repositoryOnMockDsl.findActiveIds(emptySet(), UUID.randomUUID())

        assertThat(active).isEmpty()
        verify { unusedDsl wasNot Called }
    }
}

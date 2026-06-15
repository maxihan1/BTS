// AttachmentRepositoryTest — AttachmentRepository CRUD Testcontainers 통합 테스트 (FR-AC-01 Task 3)

package com.bts.issue.attachment

import com.bts.issue.attachment.domain.Attachment
import com.bts.issue.attachment.repository.AttachmentRepository
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [AttachmentRepository] CRUD Testcontainers 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 * `issue_attachments.issue_id` 가 `issues(id)` FK 이므로 각 테스트 전 부모 이슈를 시드한다.
 *
 * 테스트 시나리오 (FR-AC-01 Task 3).
 * - T3-A. insert + findById — 삽입 후 동일 데이터 조회.
 * - T3-B. findByIssueId 정렬 — created_at 내림차순, 2건 이상.
 * - T3-C. findById 없음 — 존재하지 않는 id 조회 시 null 반환.
 * - T3-D. deleteById — 삭제 성공 true, 이후 findById null.
 * - T3-E. deleteById 없는 id — false 반환.
 * - T3-F. 교차 이슈 격리 — 다른 issueId 의 첨부는 findByIssueId 결과에 미포함.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AttachmentRepositoryTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var attachmentRepository: AttachmentRepository

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupRepository() {
        attachmentRepository = AttachmentRepository(dsl)
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    /** 각 테스트 전 issue_attachments 전체 삭제. issues 는 부모 cleanIssues() 가 처리. */
    @BeforeEach
    fun cleanAttachments() {
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM issue_attachments") }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** 이슈 1건을 삽입하고 반환한다. seqNum 은 이슈 키 시퀀스 번호. */
    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "첨부 통합 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    /**
     * [Attachment] 테스트 픽스처를 생성한다.
     *
     * @param issueId 소속 이슈 UUID.
     * @param filename 파일명. 기본값 "test.png".
     * @param createdAt 업로드 시각. 정렬 검증 시 명시 지정.
     */
    private fun buildAttachment(
        issueId: UUID,
        filename: String = "test.png",
        createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
    ): Attachment =
        Attachment(
            id = UUID.randomUUID(),
            issueId = issueId,
            filename = filename,
            contentType = "image/png",
            sizeBytes = 1024L,
            storageKey = "attachments/${UUID.randomUUID()}/test.png",
            uploadedBy = UUID.randomUUID(),
            createdAt = createdAt,
        )

    // ── T3-A. insert + findById ────────────────────────────────────────────────

    /**
     * Given  부모 이슈 존재
     * When   insert(attachment) 후 findById(attachment.id)
     * Then   동일 필드 값 반환.
     */
    @Test
    @Order(1)
    fun `T3-A - insert 후 findById 로 동일 데이터 조회`() {
        val issue = insertIssue(1L)
        val attachment = buildAttachment(issueId = issue.id.value)

        attachmentRepository.insert(attachment)
        val found = attachmentRepository.findById(attachment.id)

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(attachment.id)
        assertThat(found.issueId).isEqualTo(attachment.issueId)
        assertThat(found.filename).isEqualTo(attachment.filename)
        assertThat(found.contentType).isEqualTo(attachment.contentType)
        assertThat(found.sizeBytes).isEqualTo(attachment.sizeBytes)
        assertThat(found.storageKey).isEqualTo(attachment.storageKey)
        assertThat(found.uploadedBy).isEqualTo(attachment.uploadedBy)
        assertThat(found.createdAt).isEqualTo(attachment.createdAt)
    }

    // ── T3-B. findByIssueId 정렬 ──────────────────────────────────────────────

    /**
     * Given  동일 issueId 에 createdAt 이 다른 첨부 2건 삽입
     * When   findByIssueId(issueId)
     * Then   created_at 내림차순(최신 → 오래된 순) 2건 반환.
     */
    @Test
    @Order(2)
    fun `T3-B - findByIssueId 는 created_at 내림차순 정렬`() {
        val issue = insertIssue(1L)
        val earlier = Instant.parse("2026-01-01T00:00:00Z")
        val later = Instant.parse("2026-06-15T00:00:00Z")

        val oldAttachment = buildAttachment(issueId = issue.id.value, filename = "old.png", createdAt = earlier)
        val newAttachment = buildAttachment(issueId = issue.id.value, filename = "new.png", createdAt = later)

        attachmentRepository.insert(oldAttachment)
        attachmentRepository.insert(newAttachment)

        val results = attachmentRepository.findByIssueId(issue.id.value)

        assertThat(results).hasSize(2)
        assertThat(results[0].filename).isEqualTo("new.png")
        assertThat(results[1].filename).isEqualTo("old.png")
        assertThat(results[0].createdAt).isAfterOrEqualTo(results[1].createdAt)
    }

    // ── T3-C. findById 없음 ────────────────────────────────────────────────────

    /**
     * Given  존재하지 않는 UUID
     * When   findById(unknownId)
     * Then   null 반환.
     */
    @Test
    @Order(3)
    fun `T3-C - findById 존재하지 않는 id 는 null 반환`() {
        val result = attachmentRepository.findById(UUID.randomUUID())
        assertThat(result).isNull()
    }

    // ── T3-D. deleteById — 삭제 성공 ──────────────────────────────────────────

    /**
     * Given  삽입된 첨부 1건
     * When   deleteById(attachment.id)
     * Then   true 반환, findById 는 null.
     */
    @Test
    @Order(4)
    fun `T3-D - deleteById 성공 시 true 반환 후 findById null`() {
        val issue = insertIssue(1L)
        val attachment = buildAttachment(issueId = issue.id.value)
        attachmentRepository.insert(attachment)

        val deleted = attachmentRepository.deleteById(attachment.id)

        assertThat(deleted).isTrue()
        assertThat(attachmentRepository.findById(attachment.id)).isNull()
    }

    // ── T3-E. deleteById — 없는 id ────────────────────────────────────────────

    /**
     * Given  존재하지 않는 UUID
     * When   deleteById(unknownId)
     * Then   false 반환.
     */
    @Test
    @Order(5)
    fun `T3-E - deleteById 없는 id 는 false 반환`() {
        val result = attachmentRepository.deleteById(UUID.randomUUID())
        assertThat(result).isFalse()
    }

    // ── T3-F. 교차 이슈 격리 ──────────────────────────────────────────────────

    /**
     * Given  issueA, issueB 각각에 첨부 1건씩 삽입
     * When   findByIssueId(issueA.id)
     * Then   issueA 의 첨부 1건만 반환, issueB 의 첨부 미포함.
     */
    @Test
    @Order(6)
    fun `T3-F - findByIssueId 는 다른 이슈의 첨부를 반환하지 않는다`() {
        val issueA = insertIssue(1L)
        val issueB = insertIssue(2L)

        val attachmentA = buildAttachment(issueId = issueA.id.value, filename = "a.png")
        val attachmentB = buildAttachment(issueId = issueB.id.value, filename = "b.png")

        attachmentRepository.insert(attachmentA)
        attachmentRepository.insert(attachmentB)

        val results = attachmentRepository.findByIssueId(issueA.id.value)

        assertThat(results).hasSize(1)
        assertThat(results[0].id).isEqualTo(attachmentA.id)
        assertThat(results.none { it.issueId == issueB.id.value }).isTrue()
    }
}

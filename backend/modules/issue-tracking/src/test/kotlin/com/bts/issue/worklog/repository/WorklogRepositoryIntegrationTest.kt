// WorklogRepository CRUD + 소프트 삭제 + SUM Testcontainers 통합 테스트 (FR-TT-01 Task 2)

package com.bts.issue.worklog.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.worklog.domain.Worklog
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * [WorklogRepository] CRUD + 소프트 삭제 + SUM Testcontainers 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 * `worklogs.issue_id` 가 `issues(id)` FK 이므로 각 테스트 전 부모 이슈를 시드한다.
 *
 * 테스트 시나리오 (FR-TT-01 Task 2).
 * - T2-A. insert + findByIssueId — 삽입 후 목록 반영 (started_at DESC 정렬).
 * - T2-B. findById — 단건 조회 성공.
 * - T2-C. update — 값 변경 후 findById 에 반영.
 * - T2-D. softDelete — 삭제 후 findByIssueId 에서 제외.
 * - T2-E. sumTimeSpentByIssue — 활성 합계만 집계 (소프트 삭제분 제외).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorklogRepositoryIntegrationTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var worklogRepository: WorklogRepository

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupRepository() {
        worklogRepository = WorklogRepository(dsl)
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    /** 각 테스트 전 worklogs 전체 삭제. issues 는 부모 cleanIssues() 가 처리. */
    @BeforeEach
    fun cleanWorklogs() {
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM worklogs") }
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

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** 이슈 1건을 삽입하고 반환한다. seqNum 은 이슈 키 시퀀스 번호. */
    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "워크로그 통합 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    /** 워크로그 도메인 객체 생성 헬퍼. */
    private fun buildWorklog(
        issueId: UUID,
        timeSpentSeconds: Int = 3600,
        startedAt: Instant = Instant.now(),
        comment: String? = null,
    ): Worklog =
        Worklog(
            id = UUID.randomUUID(),
            issueId = issueId,
            authorId = UUID.randomUUID(),
            timeSpentSeconds = timeSpentSeconds,
            startedAt = startedAt,
            comment = comment,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    // ── T2-A. insert + findByIssueId (started_at DESC) ───────────────────────

    /**
     * Given  부모 이슈 존재
     * When   워크로그 2건 insert (started_at 다름) 후 findByIssueId
     * Then   2건 반환, started_at DESC 정렬 (최신이 첫 번째).
     */
    @Test
    @Order(1)
    fun `T2-A - insert 후 findByIssueId 에 반영되고 started_at DESC 정렬`() {
        val issue = insertIssue(1L)
        val earlier = Instant.parse("2024-01-01T09:00:00Z")
        val later = Instant.parse("2024-01-02T09:00:00Z")

        val w1 = buildWorklog(issue.id.value, startedAt = earlier)
        val w2 = buildWorklog(issue.id.value, startedAt = later)

        worklogRepository.insert(w1)
        worklogRepository.insert(w2)

        val result = worklogRepository.findByIssueId(issue.id.value)

        assertThat(result).hasSize(2)
        assertThat(result[0].id).isEqualTo(w2.id)
        assertThat(result[1].id).isEqualTo(w1.id)
        // started_at DESC — 첫 번째가 두 번째보다 이후이거나 같아야 함
        assertThat(result[0].startedAt).isAfterOrEqualTo(result[1].startedAt)
    }

    // ── T2-B. findById ────────────────────────────────────────────────────────

    /**
     * Given  워크로그 1건 insert
     * When   findById(id)
     * Then   동일 id 의 Worklog 반환.
     */
    @Test
    @Order(2)
    fun `T2-B - findById 단건 조회 성공`() {
        val issue = insertIssue(1L)
        val worklog = buildWorklog(issue.id.value, timeSpentSeconds = 1800, comment = "첫 작업")
        worklogRepository.insert(worklog)

        val found = worklogRepository.findById(worklog.id)

        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(worklog.id)
        assertThat(found.issueId).isEqualTo(worklog.issueId)
        assertThat(found.timeSpentSeconds).isEqualTo(1800)
        assertThat(found.comment).isEqualTo("첫 작업")
    }

    // ── T2-C. update ──────────────────────────────────────────────────────────

    /**
     * Given  워크로그 1건 insert
     * When   update(id, 새 값들)
     * Then   findById 에 변경 반영, updated_at 갱신.
     */
    @Test
    @Order(3)
    fun `T2-C - update 후 findById 에 변경 반영`() {
        val issue = insertIssue(1L)
        val worklog = buildWorklog(issue.id.value, timeSpentSeconds = 3600, comment = "원래 코멘트")
        worklogRepository.insert(worklog)

        val newStartedAt = Instant.parse("2024-06-01T10:00:00Z")
        val rows =
            worklogRepository.update(
                id = worklog.id,
                timeSpentSeconds = 7200,
                startedAt = newStartedAt,
                comment = "수정된 코멘트",
            )

        assertThat(rows).isEqualTo(1)

        val updated = worklogRepository.findById(worklog.id)
        assertThat(updated).isNotNull()
        assertThat(updated!!.timeSpentSeconds).isEqualTo(7200)
        assertThat(updated.comment).isEqualTo("수정된 코멘트")
        assertThat(updated.startedAt.epochSecond).isEqualTo(newStartedAt.epochSecond)
    }

    // ── T2-D. softDelete ──────────────────────────────────────────────────────

    /**
     * Given  워크로그 2건 insert
     * When   1건 softDelete
     * Then   true 반환, findByIssueId 에서 해당 건 제외 (1건만 조회).
     * Also   findById(삭제 id) → null.
     */
    @Test
    @Order(4)
    fun `T2-D - softDelete 후 findByIssueId 에서 제외되고 findById 도 null`() {
        val issue = insertIssue(1L)
        val w1 = buildWorklog(issue.id.value, timeSpentSeconds = 1800)
        val w2 = buildWorklog(issue.id.value, timeSpentSeconds = 3600)
        worklogRepository.insert(w1)
        worklogRepository.insert(w2)

        val deleted = worklogRepository.softDelete(w1.id)

        assertThat(deleted).isTrue()

        val remaining = worklogRepository.findByIssueId(issue.id.value)
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].id).isEqualTo(w2.id)

        assertThat(worklogRepository.findById(w1.id)).isNull()
    }

    // ── T2-E. sumTimeSpentByIssue ─────────────────────────────────────────────

    /**
     * Given  워크로그 3건 insert (1800 + 3600 + 7200), 이 중 1건 softDelete
     * When   sumTimeSpentByIssue(issueId)
     * Then   소프트 삭제 제외 활성 합계 = 1800 + 7200 = 9000.
     */
    @Test
    @Order(5)
    fun `T2-E - sumTimeSpentByIssue 는 소프트 삭제 제외 활성 합계만 반환`() {
        val issue = insertIssue(1L)
        val w1 = buildWorklog(issue.id.value, timeSpentSeconds = 1800)
        val w2 = buildWorklog(issue.id.value, timeSpentSeconds = 3600)
        val w3 = buildWorklog(issue.id.value, timeSpentSeconds = 7200)
        worklogRepository.insert(w1)
        worklogRepository.insert(w2)
        worklogRepository.insert(w3)

        // w2 소프트 삭제 → 합계에서 3600 제외
        worklogRepository.softDelete(w2.id)

        val sum = worklogRepository.sumTimeSpentByIssue(issue.id.value)

        assertThat(sum).isEqualTo(9000)
    }
}

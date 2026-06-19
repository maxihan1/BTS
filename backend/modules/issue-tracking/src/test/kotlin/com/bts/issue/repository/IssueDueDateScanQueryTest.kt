// due_date 스캔 쿼리(마감임박·지연 이슈) 통합 테스트 — FR-PL-02 Task 2 TDD RED

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
import java.time.LocalDate
import java.util.UUID

/**
 * [IssueRepository.findOpenIssuesDueOn] / [IssueRepository.findOpenOverdueIssues] 통합 테스트.
 *
 * FR-PL-02 Task 2 — 매일 스케줄러가 호출하는 due_date 스캔 쿼리 검증.
 * [IssueTestcontainersBase] 상속으로 JVM singleton PostgreSQL container + Flyway migrate 공유.
 *
 * 시드 이슈 6종.
 * - S1: due=내일(tomorrow) + 열림(resolution=null, deleted_at=null)
 * - S2: due=과거(yesterday) + 열림
 * - S3: due=오늘(today) + 열림
 * - S4: due=null + 열림
 * - S5: due=과거(yesterday) + resolution 있음(종료)
 * - S6: due=과거(yesterday) + deleted_at 있음(소프트삭제)
 *
 * 단언.
 * - findOpenIssuesDueOn(내일) → S1 1건만.
 * - findOpenOverdueIssues(오늘) → S2 1건만(S3·S4·S5·S6·S1 전부 제외).
 * - 반환 item 에 issueKey + projectKey 정확.
 */
class IssueDueDateScanQueryTest : IssueTestcontainersBase() {

    private var taskTypeId: IssueTypeId? = null

    /** V003 seed 에서 task 타입 id 조회. */
    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                        taskTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다 — resolveTaskTypeId 실행 확인" }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    /**
     * 최소 필드로 이슈를 생성하고 삽입한다.
     * 날짜/resolution/deletedAt 는 caller 가 copy 로 지정한다.
     */
    private fun insertIssue(
        seq: Long,
        dueDate: LocalDate? = null,
    ): Issue {
        val key = IssueKey.of("TPRJ", seq)
        val issue =
            Issue
                .create(
                    id = IssueId(UUID.randomUUID()),
                    key = key,
                    projectId = testProjectId,
                    typeId = requireTaskTypeId(),
                    summary = "due scan seed $seq",
                    reporterId = ActorId(UUID.randomUUID()),
                    currentStateKey = "open",
                ).copy(dueDate = dueDate)
        return repository.insert(issue)
    }

    /**
     * 이미 삽입된 이슈에 resolution_id 를 직접 UPDATE 한다.
     * 도메인 전이를 거치지 않는 시드 전용 헬퍼.
     */
    private fun setResolution(
        issueKey: String,
        resolutionId: UUID,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET resolution_id = ? WHERE key = ?").use { stmt ->
                stmt.setObject(1, resolutionId)
                stmt.setString(2, issueKey)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * 이미 삽입된 이슈를 소프트 삭제 처리한다.
     * 시드 전용 헬퍼.
     */
    private fun softDeleteIssue(issueKey: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeUpdate()
            }
        }
    }

    // ── T1. findOpenIssuesDueOn — 정확히 해당 날짜인 열린 이슈만 반환 ──────────

    /**
     * Given  6종 시드 이슈 (S1~S6)
     * When   findOpenIssuesDueOn(내일)
     * Then   S1(due=내일+열림) 1건만 반환.
     *        issueKey = "TPRJ-1", projectKey = "TPRJ".
     */
    @Test
    fun `T1 - findOpenIssuesDueOn - 해당 날짜 열린 이슈만 반환`() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val yesterday = today.minusDays(1)

        // S1: due=내일+열림
        val s1 = insertIssue(seq = 1L, dueDate = tomorrow)
        // S2: due=과거+열림
        insertIssue(seq = 2L, dueDate = yesterday)
        // S3: due=오늘+열림
        insertIssue(seq = 3L, dueDate = today)
        // S4: due=null+열림
        insertIssue(seq = 4L, dueDate = null)
        // S5: due=과거+resolution(종료)
        val s5 = insertIssue(seq = 5L, dueDate = yesterday)
        setResolution(s5.key.value, UUID.randomUUID())
        // S6: due=과거+소프트삭제
        val s6 = insertIssue(seq = 6L, dueDate = yesterday)
        softDeleteIssue(s6.key.value)

        val result = repository.findOpenIssuesDueOn(tomorrow)

        assertThat(result).hasSize(1)
        assertThat(result[0].issueKey).isEqualTo(s1.key.value)
        assertThat(result[0].projectKey).isEqualTo("TPRJ")
    }

    // ── T2. findOpenOverdueIssues — 오늘 미만 due + 열린 이슈만 반환 ───────────

    /**
     * Given  6종 시드 이슈 (S1~S6)
     * When   findOpenOverdueIssues(오늘)
     * Then   S2(due=과거+열림) 1건만 반환.
     *        S3(오늘)·S1(내일)·S4(null)·S5(종료)·S6(삭제) 전부 제외.
     *        issueKey = "TPRJ-2", projectKey = "TPRJ".
     */
    @Test
    fun `T2 - findOpenOverdueIssues - 지난 마감일+열린 이슈만 반환`() {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val yesterday = today.minusDays(1)

        // S1: due=내일+열림
        insertIssue(seq = 1L, dueDate = tomorrow)
        // S2: due=과거+열림
        val s2 = insertIssue(seq = 2L, dueDate = yesterday)
        // S3: due=오늘+열림 (오늘은 지연 아님)
        insertIssue(seq = 3L, dueDate = today)
        // S4: due=null+열림
        insertIssue(seq = 4L, dueDate = null)
        // S5: due=과거+resolution(종료)
        val s5 = insertIssue(seq = 5L, dueDate = yesterday)
        setResolution(s5.key.value, UUID.randomUUID())
        // S6: due=과거+소프트삭제
        val s6 = insertIssue(seq = 6L, dueDate = yesterday)
        softDeleteIssue(s6.key.value)

        val result = repository.findOpenOverdueIssues(today)

        assertThat(result).hasSize(1)
        assertThat(result[0].issueKey).isEqualTo(s2.key.value)
        assertThat(result[0].projectKey).isEqualTo("TPRJ")
    }
}

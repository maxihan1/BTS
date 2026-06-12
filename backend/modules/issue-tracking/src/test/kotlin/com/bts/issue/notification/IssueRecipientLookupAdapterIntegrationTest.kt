// IssueRecipientLookupAdapter 통합 테스트 — issues 테이블에서 reporter/assignee 수신자 조회 검증 (FR-NT-02 Task 6)

package com.bts.issue.notification

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueRecipients
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [IssueRecipientLookupAdapter] Testcontainers 통합 테스트.
 *
 * [IssueTestcontainersBase] 의 JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext + [IssueRepository] 를 직접 조합한다.
 *
 * ## 시나리오
 *
 * - T1. reporter·assignee 모두 있는 이슈 → 정확한 reporterId/assigneeId 반환
 * - T2. assignee 없는 이슈 → assigneeId=null, reporterId 정상 반환
 * - T3. soft-deleted 이슈(deleted_at 설정) → 빈 수신자([IssueRecipients.empty]) fail-safe
 * - T4. 존재하지 않는 issueKey → 빈 수신자([IssueRecipients.empty]) fail-safe
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueRecipientLookupAdapterIntegrationTest : IssueTestcontainersBase() {
    private lateinit var adapter: IssueRecipientLookupAdapter

    /** V003 seed 에서 task 타입 id 를 DB 에서 직접 조회. value class 특성상 var+null 허용 초기화. */
    private var taskTypeId: IssueTypeId? = null

    /**
     * JVM 당 1회 실행 — adapter 초기화 + task 타입 id 조회.
     * [IssueTestcontainersBase.bootstrap] 이 먼저 실행되어 dsl/repository 가 초기화된 후 호출된다.
     */
    @BeforeAll
    fun setupAdapter() {
        adapter = IssueRecipientLookupAdapter(repository)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 시드에서 task 타입을 찾을 수 없습니다." }
                    taskTypeId = IssueTypeId(rs.getLong(1))
                }
            }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId {
        return requireNotNull(taskTypeId) { "taskTypeId 초기화 전 접근 — setupAdapter 확인" }
    }

    /**
     * 이슈를 삽입하고 반환된 Issue 를 제공하는 헬퍼.
     * key_sequence 는 cleanIssues() 에서 0 으로 초기화되므로 sequence 인자로 구분한다.
     */
    private fun seedIssue(
        sequence: Long,
        reporterId: UUID,
        assigneeId: UUID?,
    ): Issue {
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", sequence),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "Test Issue $sequence",
                reporterId = ActorId(reporterId),
                currentStateKey = "open",
                assigneeId = assigneeId?.let { ActorId(it) },
            )
        return repository.insert(issue)
    }

    /**
     * T1. reporter·assignee 모두 있는 이슈 — findRecipients 가 정확한 reporterId/assigneeId 를 반환한다.
     *
     * Given   reporter·assignee UUID 를 지정해 이슈를 삽입
     * When    findRecipients(issueKey) 호출
     * Then    reporterId = 삽입 시 reporter UUID, assigneeId = 삽입 시 assignee UUID
     */
    @Test
    fun `T1 - reporter와 assignee 모두 있는 이슈 - 정확한 수신자 반환`() {
        val reporterId = UUID.randomUUID()
        val assigneeId = UUID.randomUUID()

        val inserted = seedIssue(sequence = 1L, reporterId = reporterId, assigneeId = assigneeId)

        val result = adapter.findRecipients(inserted.key.value)

        assertThat(result.reporterId).isEqualTo(reporterId)
        assertThat(result.assigneeId).isEqualTo(assigneeId)
    }

    /**
     * T2. assignee 없는 이슈 — reporterId 정상, assigneeId=null.
     *
     * Given   assigneeId=null 로 이슈 삽입
     * When    findRecipients(issueKey) 호출
     * Then    reporterId = 삽입 시 reporter UUID, assigneeId = null
     */
    @Test
    fun `T2 - assignee 없는 이슈 - assigneeId는 null 반환`() {
        val reporterId = UUID.randomUUID()

        val inserted = seedIssue(sequence = 1L, reporterId = reporterId, assigneeId = null)

        val result = adapter.findRecipients(inserted.key.value)

        assertThat(result.reporterId).isEqualTo(reporterId)
        assertThat(result.assigneeId).isNull()
    }

    /**
     * T3. soft-deleted 이슈 — 빈 수신자 반환 (fail-safe).
     *
     * Given   이슈를 삽입 후 deleted_at 을 직접 설정(소프트 삭제)
     * When    findRecipients(issueKey) 호출
     * Then    IssueRecipients.empty() 와 동일 (reporterId=null, assigneeId=null)
     */
    @Test
    fun `T3 - soft-deleted 이슈 - 빈 수신자 반환`() {
        val reporterId = UUID.randomUUID()
        val inserted = seedIssue(sequence = 1L, reporterId = reporterId, assigneeId = null)

        // soft-delete: deleted_at 직접 설정
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = ? WHERE key = ?").use { stmt ->
                stmt.setObject(1, OffsetDateTime.now(ZoneOffset.UTC))
                stmt.setString(2, inserted.key.value)
                stmt.executeUpdate()
            }
        }

        val result = adapter.findRecipients(inserted.key.value)

        assertThat(result).isEqualTo(IssueRecipients.empty())
    }

    /**
     * T4. 존재하지 않는 issueKey — 빈 수신자 반환 (fail-safe).
     *
     * Given   DB 에 존재하지 않는 키 "TPRJ-99999"
     * When    findRecipients("TPRJ-99999") 호출
     * Then    IssueRecipients.empty() 와 동일
     */
    @Test
    fun `T4 - 존재하지 않는 이슈 키 - 빈 수신자 반환`() {
        val result = adapter.findRecipients("TPRJ-99999")

        assertThat(result).isEqualTo(IssueRecipients.empty())
    }
}

// IssueSecurityClassificationAdapter Testcontainers 통합 테스트 — 채널 브로드캐스트 제외 게이트 fail-closed 검증 (FR-SL-06 PR-B Task 1)

package com.bts.issue.adapter

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueSecurityClassificationAdapter] 통합 테스트 (FR-SL-06 PR-B Task 1).
 *
 * ## 왜 Testcontainers 인가
 * 어댑터는 [com.bts.issue.repository.IssueRepository.findByKey] (활성 이슈만, `deleted_at IS NULL`)를
 * 직접 호출한다. 실 DB round-trip 없이는 소프트 삭제/미존재 이슈가 실제로 fail-closed(제한)로
 * 수렴하는지 검증할 수 없다.
 *
 * ## 검증 시나리오
 * - (a) `security_level_id` 가 설정된 이슈 → `isSecurityRestricted` = true.
 * - (b) `security_level_id` 가 null 인 이슈(공개) → `isSecurityRestricted` = false.
 * - (c) 존재하지 않는(또는 소프트 삭제된) 이슈 키 → `isSecurityRestricted` = true(fail-closed).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueSecurityClassificationAdapterTest : IssueTestcontainersBase() {
    private var bugTypeId: IssueTypeId? = null

    /** V003 seed 에서 bug 타입 id 조회 — [com.bts.issue.adapter.IssueUnfurlAdapterIntegrationTest.resolveTaskTypeId] 동형. */
    @BeforeAll
    fun resolveBugTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'bug' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 bug 타입이 없습니다." }
                        bugTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
        }
    }

    private fun requireBugTypeId(): IssueTypeId = requireNotNull(bugTypeId) { "bugTypeId 가 초기화되지 않았습니다." }

    private fun adapter(): IssueSecurityClassificationAdapter = IssueSecurityClassificationAdapter(repository)

    /** 테스트용 이슈 생성 helper — [com.bts.issue.adapter.outbound.automation.AutomationIssueSnapshotAdapterTest.insertIssue] 동형. */
    private fun insertIssue(
        seq: Long,
        securityLevelId: UUID? = null,
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = requireBugTypeId(),
                summary = "채널 브로드캐스트 게이트 테스트 이슈",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                securityLevelId = securityLevelId,
            ),
        )

    @Test
    @Order(1)
    fun `security_level_id 가 설정된 이슈는 isSecurityRestricted 가 true 다`() {
        insertIssue(seq = 1, securityLevelId = UUID.randomUUID())

        val result = adapter().isSecurityRestricted("TPRJ-1")

        assertThat(result).isTrue()
    }

    @Test
    @Order(2)
    fun `security_level_id 가 null 인 이슈는 isSecurityRestricted 가 false 다`() {
        insertIssue(seq = 1, securityLevelId = null)

        val result = adapter().isSecurityRestricted("TPRJ-1")

        assertThat(result).isFalse()
    }

    @Test
    @Order(3)
    fun `존재하지 않는 이슈 키는 fail-closed 로 isSecurityRestricted 가 true 다`() {
        val result = adapter().isSecurityRestricted("TPRJ-999")

        assertThat(result).isTrue()
    }
}

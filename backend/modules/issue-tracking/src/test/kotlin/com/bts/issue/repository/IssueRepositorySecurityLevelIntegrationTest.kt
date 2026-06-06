// IssueRepository findByKeyWithType securityLevelId 노출 통합 테스트 — FR-PM-06 PR-B BE-1 RED

package com.bts.issue.repository

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
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
 * IssueRepository.findByKeyWithType 반환 IssueResponse 의 securityLevelId 노출 통합 테스트.
 *
 * FR-PM-06 PR-B BE-1 — findByKeyWithType 이 반환하는 [IssueResponse] 에
 * issues.security_level_id 컬럼 값이 올바르게 매핑되어야 한다.
 *
 * 검증 시나리오.
 * - SL-IT-1. securityLevelId 를 지정해 삽입한 이슈를 findByKeyWithType 으로 조회하면
 *   IssueResponse.securityLevelId 가 지정한 UUID 와 일치한다.
 * - SL-IT-2. securityLevelId 없이 삽입한 이슈를 findByKeyWithType 으로 조회하면
 *   IssueResponse.securityLevelId 가 null 이다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositorySecurityLevelIntegrationTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

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
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    // ── SL-IT-1. securityLevelId 지정 이슈 — findByKeyWithType 결과에 반영 ────

    /**
     * Given  security_level_id 를 지정해 삽입된 이슈
     * When   findByKeyWithType 으로 조회
     * Then   IssueResponse.securityLevelId 가 지정한 UUID 와 일치한다.
     */
    @Test
    @Order(1)
    fun `SL-IT-1 — securityLevelId 지정 이슈를 findByKeyWithType 으로 조회하면 응답에 UUID 가 반영된다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val levelId = UUID.randomUUID()
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "보안 등급 노출 통합 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                securityLevelId = levelId,
            )
        repository.insert(issue)

        val response: IssueResponse =
            requireNotNull(repository.findByKeyWithType(key)) { "이슈 조회 불가" }

        assertThat(response.securityLevelId).isEqualTo(levelId)
    }

    // ── SL-IT-2. securityLevelId 미지정 이슈 — findByKeyWithType 결과 null ────

    /**
     * Given  security_level_id 없이 삽입된 이슈
     * When   findByKeyWithType 으로 조회
     * Then   IssueResponse.securityLevelId 가 null 이다.
     */
    @Test
    @Order(2)
    fun `SL-IT-2 — securityLevelId 없는 이슈를 findByKeyWithType 으로 조회하면 응답 securityLevelId 가 null 이다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "보안 등급 없는 이슈 통합 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val response: IssueResponse =
            requireNotNull(repository.findByKeyWithType(key)) { "이슈 조회 불가" }

        assertThat(response.securityLevelId).isNull()
    }
}

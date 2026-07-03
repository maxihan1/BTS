// IssueRepository Cycle/Lead Time 원천 조회 Testcontainers 통합 테스트 — 활성·가시 이슈만 반환 + issueKey 검증 (FR-RP-04 Task 4)

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueRepository.fetchActiveVisibleIssuesForCycleTime] 통합 테스트 (FR-RP-04 Task 4).
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL + Flyway 마이그레이션을 재사용한다.
 * CFD 원천 조회([IssueRepositoryCfdSourceTest])를 미러하되, Cycle/Lead Time 분포 응답 샘플에
 * 필요한 `issueKey` 가 정확히 채워지는지 값 대조로 추가 검증한다. 정본 보안 술어
 * `buildActiveSecureWhere` 재사용 여부를 soft-delete/보안등급/타 프로젝트 시나리오로 검증한다
 * (복제 금지, isomorphic-clone 회귀 방지).
 *
 * ## 검증 시나리오
 * - CT-1. 프로젝트 이슈 메타(id·issueKey·typeId·currentStateKey·createdAt)를 정확히 반환한다.
 * - CT-2. 소프트 삭제된 이슈는 결과에서 제외된다.
 * - CT-3. viewer 가 접근 불가한 보안 등급 이슈는 결과에서 제외된다(비-vacuous: 공개는 나오고 기밀만 빠짐).
 * - CT-4. 타 프로젝트 이슈는 결과에서 제외된다.
 */
class IssueRepositoryCycleTimeSourceTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id — value class 는 lateinit 불가, nullable var 사용. */
    private var taskTypeId: IssueTypeId? = null

    /** Cycle/Lead Time 원천을 조회하는 viewer UUID — 가시성 판단 기준. */
    private val viewerId: UUID = UUID.randomUUID()

    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    taskTypeId = IssueTypeId(rs.getLong(1))
                }
            }
        }
    }

    private fun requireTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 미초기화 — resolveTaskTypeId 확인" }

    /** unrestricted=true 빠른경로 IssueSecurityAccess. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /** unrestricted=false, 지정 staticLevelIds 만 허용하는 IssueSecurityAccess 생성 헬퍼. */
    private fun restrictedAccess(vararg allowedLevelIds: UUID): IssueSecurityAccess =
        IssueSecurityAccess(
            unrestricted = false,
            staticLevelIds = allowedLevelIds.toSet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /**
     * 테스트용 이슈를 삽입한다.
     *
     * @param seq IssueKey 고유성을 위한 시퀀스 번호.
     * @param projectId 소속 프로젝트 UUID. 기본값 testProjectId(TPRJ).
     * @param projectKeyPrefix IssueKey prefix. projectId 를 바꿀 때 함께 변경해야 한다.
     * @param securityLevelId 보안 등급 UUID. null 이면 공개(등급 없음).
     */
    private fun insertIssue(
        seq: Long,
        projectId: UUID = testProjectId,
        projectKeyPrefix: String = "TPRJ",
        securityLevelId: UUID? = null,
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of(projectKeyPrefix, seq),
                projectId = projectId,
                typeId = requireTypeId(),
                summary = "Cycle/Lead Time 원천 테스트 이슈 $seq",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                securityLevelId = securityLevelId,
            ),
        )

    /** 이슈를 소프트 삭제한다 (deleted_at 설정). */
    private fun softDeleteIssue(id: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeUpdate()
            }
        }
    }

    /** 두 번째 프로젝트(TPRJ2)를 삽입하고 id 를 반환한다 (EP-10 cross-project 선례와 동일 패턴). */
    private fun insertOtherProject(): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('TPRJ2', 'Test Project 2') ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }
            conn.prepareStatement("SELECT id FROM projects WHERE key = 'TPRJ2'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }
        }

    // ── CT-1. 프로젝트 이슈 메타 + issueKey 반환 ──────────────────────────────

    /**
     * Given  TPRJ 프로젝트 이슈 1건.
     * When   fetchActiveVisibleIssuesForCycleTime(unrestricted) 호출.
     * Then   [CycleTimeIssueSourceRow] 의 id·issueKey·typeId·currentStateKey·createdAt 이 삽입값과 일치한다.
     */
    @Test
    fun `CT-1 - 프로젝트 이슈의 id issueKey typeId currentStateKey createdAt 을 반환한다`() {
        val issue = insertIssue(seq = 1L)

        val rows = repository.fetchActiveVisibleIssuesForCycleTime("TPRJ", viewerId, unrestrictedAccess)

        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.issueId).isEqualTo(issue.id.value)
        assertThat(row.issueKey).isEqualTo("TPRJ-1")
        assertThat(row.typeId).isEqualTo(requireTypeId().value)
        assertThat(row.currentStateKey).isEqualTo("open")
        assertThat(row.createdAt).isEqualTo(issue.createdAt)
    }

    // ── CT-2. 소프트 삭제 이슈 제외 ────────────────────────────────────────────

    /**
     * Given  활성 이슈 1건 + 소프트 삭제된 이슈 1건.
     * When   fetchActiveVisibleIssuesForCycleTime(unrestricted) 호출.
     * Then   소프트 삭제된 이슈는 결과에서 제외되고 활성 이슈만 반환된다.
     */
    @Test
    fun `CT-2 - 소프트 삭제된 이슈는 결과에서 제외된다`() {
        val active = insertIssue(seq = 1L)
        val deleted = insertIssue(seq = 2L)
        softDeleteIssue(deleted.id.value)

        val rows = repository.fetchActiveVisibleIssuesForCycleTime("TPRJ", viewerId, unrestrictedAccess)

        assertThat(rows.map { it.issueId }).containsExactly(active.id.value)
        assertThat(rows.map { it.issueKey }).containsExactly("TPRJ-1")
    }

    // ── CT-3. 보안 등급 차단 이슈 제외 (비-vacuous) ────────────────────────────

    /**
     * Given  공개 이슈 1건 + viewer 가 접근 불가한 보안등급 이슈 1건.
     * When   viewer 가 restrictedLevel 미소속(NULL 등급만 접근)인 access 로 조회.
     * Then   기밀 이슈는 결과에서 제외되고 공개 이슈만 반환된다(기밀 누출 0, 비-vacuous 대조).
     */
    @Test
    fun `CT-3 - viewer 가 접근 불가한 보안 등급 이슈는 결과에서 제외된다`() {
        val restrictedLevel = UUID.randomUUID()
        val publicIssue = insertIssue(seq = 1L, securityLevelId = null)
        val secretIssue = insertIssue(seq = 2L, securityLevelId = restrictedLevel)

        val rows = repository.fetchActiveVisibleIssuesForCycleTime("TPRJ", viewerId, restrictedAccess())

        assertThat(rows.map { it.issueId }).containsExactly(publicIssue.id.value)
        assertThat(rows.map { it.issueId }).doesNotContain(secretIssue.id.value)
        assertThat(rows.map { it.issueKey }).containsExactly("TPRJ-1")
    }

    // ── CT-4. 타 프로젝트 이슈 제외 ────────────────────────────────────────────

    /**
     * Given  TPRJ 이슈 1건 + TPRJ2(타 프로젝트) 이슈 1건.
     * When   projectKey="TPRJ" 로 fetchActiveVisibleIssuesForCycleTime 조회.
     * Then   TPRJ2 이슈는 결과에서 제외되고 TPRJ 이슈만 반환된다.
     */
    @Test
    fun `CT-4 - 타 프로젝트 이슈는 결과에서 제외된다`() {
        val otherProjectId = insertOtherProject()
        val ownIssue = insertIssue(seq = 1L)
        insertIssue(seq = 1L, projectId = otherProjectId, projectKeyPrefix = "TPRJ2")

        val rows = repository.fetchActiveVisibleIssuesForCycleTime("TPRJ", viewerId, unrestrictedAccess)

        assertThat(rows.map { it.issueId }).containsExactly(ownIssue.id.value)
        assertThat(rows.map { it.issueKey }).containsExactly("TPRJ-1")
    }
}

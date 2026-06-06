// 보안 등급 목록 필터 통합 테스트 — IssueSecurityAccess 주입으로 SQL WHERE 술어가 올바른 행을 거르는지 검증.

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.data.domain.PageRequest
import java.sql.DriverManager
import java.util.UUID

/**
 * IssueRepository.listWithType 보안 등급 필터 통합 테스트 (FR-PM-06 T10).
 *
 * 실 멤버십 기반 end-to-end 는 identity-access prod 구현(T9) 담당이며, 이 테스트는
 * 손수 만든 [IssueSecurityAccess] 를 주입해 SQL WHERE 술어가 올바른 행을 거르는지만 검증한다.
 * (non-prod AlwaysAllow stub 의 unrestricted=true 경로는 별도 빠른경로 테스트로 검증.)
 *
 * 공유 Testcontainers 인스턴스: [IssueTestcontainersBase.postgres] JVM singleton 재사용.
 *
 * ## 테스트 시나리오
 * - S1. security_level_id=NULL 이슈는 항상 노출.
 * - S2. staticLevelIds 포함 등급 이슈는 actor 무관 노출.
 * - S3. REPORTER 등급 이슈는 actor 가 reporter 일 때만 노출.
 * - S4. ASSIGNEE 등급 이슈는 actor 가 assignee 일 때만 노출.
 * - S5. 비멤버 등급 이슈는 content·총개수(count) 양쪽에서 제외.
 * - S6. unrestricted=true 이면 전부 노출(빠른경로).
 * - S7. 페이지네이션 정합 — 20개 중 일부 제외돼도 page size·total 정확.
 * - S8. 여러 등급 분류 혼합 — 노출 대상과 제외 대상이 섞인 경우 정확히 분리.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueSecurityListFilterTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

    /** resolveTaskTypeId — V003 seed 에서 task 타입 id 조회. */
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

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /**
     * 테스트용 이슈를 생성하는 helper.
     *
     * @param seq issues.key_sequence 증분값 — IssueKey 고유성에 사용.
     * @param reporterId 이슈 보고자 UUID.
     * @param assigneeId 이슈 담당자 UUID. null 이면 미배정.
     * @param securityLevelId 보안 등급 UUID. null 이면 공개(등급 없음).
     */
    private fun buildIssue(
        seq: Long,
        reporterId: UUID = UUID.randomUUID(),
        assigneeId: UUID? = null,
        securityLevelId: UUID? = null,
    ): Issue =
        Issue.create(
            id = IssueId(UUID.randomUUID()),
            key = IssueKey.of("TPRJ", seq),
            projectId = testProjectId,
            typeId = requireTaskTypeId(),
            summary = "security filter test $seq",
            reporterId = ActorId(reporterId),
            currentStateKey = "open",
            assigneeId = assigneeId?.let { ActorId(it) },
            securityLevelId = securityLevelId,
        )

    // ── S1. NULL 등급 이슈는 항상 노출 ─────────────────────────────────────────

    /**
     * Given  security_level_id=NULL 이슈 1건, 비멤버 등급 이슈 1건
     * When   staticLevelIds 에 비멤버 등급이 없는 access 로 listWithType 호출
     * Then   NULL 이슈만 반환(total=1, content 1건).
     */
    @Test
    @Order(1)
    fun `S1 - NULL 등급 이슈는 항상 노출된다`() {
        val actor = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()

        repository.insert(buildIssue(seq = 1, reporterId = actor, securityLevelId = null))
        repository.insert(buildIssue(seq = 2, reporterId = actor, securityLevelId = excludedLevel))

        val access =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, access)

        assertThat(page.totalElements).isEqualTo(1L)
        assertThat(page.content).hasSize(1)
        // NULL 등급 이슈만 노출 — summary 로 식별
        assertThat(page.content.first().summary).isEqualTo("security filter test 1")
    }

    // ── S2. staticLevelIds 포함 등급 이슈 노출 ────────────────────────────────

    /**
     * Given  NULL 등급 이슈 1건, static 등급 이슈 1건, 비멤버 등급 이슈 1건
     * When   staticLevelIds 에 static 등급만 포함한 access 로 listWithType 호출
     * Then   NULL + static 등급 이슈 2건 반환.
     */
    @Test
    @Order(2)
    fun `S2 - staticLevelIds 에 포함된 등급 이슈는 노출된다`() {
        val actor = UUID.randomUUID()
        val staticLevel = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()

        repository.insert(buildIssue(seq = 1, securityLevelId = null))
        repository.insert(buildIssue(seq = 2, securityLevelId = staticLevel))
        repository.insert(buildIssue(seq = 3, securityLevelId = excludedLevel))

        val access =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = setOf(staticLevel),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, access)

        assertThat(page.totalElements).isEqualTo(2L)
        assertThat(page.content).hasSize(2)
    }

    // ── S3. REPORTER 등급 — actor 가 reporter 일 때만 노출 ───────────────────

    /**
     * Given  reporterLevel 등급 이슈 2건 (각각 actor/타인이 reporter)
     * When   reporterLevelIds 에 reporterLevel 포함, actor UUID 전달
     * Then   actor 가 reporter 인 이슈 1건만 반환.
     */
    @Test
    @Order(3)
    fun `S3 - REPORTER 등급 이슈는 actor 가 reporter 일 때만 노출된다`() {
        val actor = UUID.randomUUID()
        val otherReporter = UUID.randomUUID()
        val reporterLevel = UUID.randomUUID()

        // actor 가 reporter 인 이슈
        repository.insert(buildIssue(seq = 1, reporterId = actor, securityLevelId = reporterLevel))
        // 타인이 reporter 인 이슈 — 노출 불가
        repository.insert(buildIssue(seq = 2, reporterId = otherReporter, securityLevelId = reporterLevel))

        val access =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(),
                reporterLevelIds = setOf(reporterLevel),
                assigneeLevelIds = emptySet(),
            )

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, access)

        assertThat(page.totalElements).isEqualTo(1L)
        assertThat(page.content).hasSize(1)
    }

    // ── S4. ASSIGNEE 등급 — actor 가 assignee 일 때만 노출 ──────────────────

    /**
     * Given  assigneeLevel 등급 이슈 2건 (각각 actor/타인이 assignee)
     * When   assigneeLevelIds 에 assigneeLevel 포함, actor UUID 전달
     * Then   actor 가 assignee 인 이슈 1건만 반환.
     */
    @Test
    @Order(4)
    fun `S4 - ASSIGNEE 등급 이슈는 actor 가 assignee 일 때만 노출된다`() {
        val actor = UUID.randomUUID()
        val otherAssignee = UUID.randomUUID()
        val assigneeLevel = UUID.randomUUID()

        // actor 가 assignee 인 이슈
        repository.insert(buildIssue(seq = 1, assigneeId = actor, securityLevelId = assigneeLevel))
        // 타인이 assignee 인 이슈 — 노출 불가
        repository.insert(buildIssue(seq = 2, assigneeId = otherAssignee, securityLevelId = assigneeLevel))

        val access =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = setOf(assigneeLevel),
            )

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, access)

        assertThat(page.totalElements).isEqualTo(1L)
        assertThat(page.content).hasSize(1)
    }

    // ── S5. 비멤버 등급 이슈 — content·count 양쪽 제외 ───────────────────────

    /**
     * Given  비멤버 등급만 달린 이슈 3건
     * When   staticLevelIds/reporterLevelIds/assigneeLevelIds 모두 비어있는 access 로 조회
     * Then   total=0, content 비어있음 (count 쿼리도 제외).
     */
    @Test
    @Order(5)
    fun `S5 - 비멤버 등급 이슈는 content 와 count 양쪽에서 제외된다`() {
        val actor = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()

        for (i in 1..3) {
            repository.insert(buildIssue(seq = i.toLong(), securityLevelId = excludedLevel))
        }

        val access =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, access)

        assertThat(page.totalElements).isEqualTo(0L)
        assertThat(page.content).isEmpty()
    }

    // ── S6. unrestricted=true 빠른경로 — 전부 노출 ──────────────────────────

    /**
     * Given  NULL 등급 이슈 1건, 임의 등급 이슈 2건
     * When   unrestricted=true 인 access 로 listWithType 호출
     * Then   total=3, content 3건 (WHERE 술어 미적용).
     */
    @Test
    @Order(6)
    fun `S6 - unrestricted=true 이면 등급 무관 전부 노출된다`() {
        val actor = UUID.randomUUID()
        val levelA = UUID.randomUUID()
        val levelB = UUID.randomUUID()

        repository.insert(buildIssue(seq = 1, securityLevelId = null))
        repository.insert(buildIssue(seq = 2, securityLevelId = levelA))
        repository.insert(buildIssue(seq = 3, securityLevelId = levelB))

        val access =
            IssueSecurityAccess(
                unrestricted = true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, access)

        assertThat(page.totalElements).isEqualTo(3L)
        assertThat(page.content).hasSize(3)
    }

    // ── S7. 페이지네이션 정합 ────────────────────────────────────────────────

    /**
     * Given  null 등급 이슈 5건, 비멤버 등급 이슈 15건 (총 20건)
     * When   unrestricted=false, pageSize=3, page=0 으로 listWithType 호출
     * Then   total=5(비멤버 15건 제외), content=3(page size), 다음 페이지 존재.
     */
    @Test
    @Order(7)
    fun `S7 - 페이지네이션 total 은 필터 후 개수, content 는 pageSize 로 자른다`() {
        val actor = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()

        for (i in 1..5) {
            repository.insert(buildIssue(seq = i.toLong(), securityLevelId = null))
        }
        for (i in 6..20) {
            repository.insert(buildIssue(seq = i.toLong(), securityLevelId = excludedLevel))
        }

        val access =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 3), actor, access)

        assertThat(page.totalElements).isEqualTo(5L)
        assertThat(page.content).hasSize(3)
        assertThat(page.hasNext()).isTrue()
    }

    // ── S8. 혼합 등급 분류 — 복수 카테고리 동시 검증 ─────────────────────────

    /**
     * Given  null(1) + static(1) + reporter(1, actor가 reporter) + assignee(1, actor가 assignee)
     *        + 비멤버(2) 혼합 6건
     * When   staticLevelIds·reporterLevelIds·assigneeLevelIds 각 1개씩 포함한 access 로 조회
     * Then   total=4 (비멤버 2건 제외), content 4건.
     */
    @Test
    @Order(8)
    fun `S8 - 혼합 등급 분류에서 노출 대상과 비멤버 이슈가 정확히 분리된다`() {
        val actor = UUID.randomUUID()
        val staticLevel = UUID.randomUUID()
        val reporterLevel = UUID.randomUUID()
        val assigneeLevel = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()

        repository.insert(buildIssue(seq = 1, securityLevelId = null))
        repository.insert(buildIssue(seq = 2, securityLevelId = staticLevel))
        repository.insert(buildIssue(seq = 3, reporterId = actor, securityLevelId = reporterLevel))
        repository.insert(buildIssue(seq = 4, assigneeId = actor, securityLevelId = assigneeLevel))
        repository.insert(buildIssue(seq = 5, securityLevelId = excludedLevel))
        repository.insert(buildIssue(seq = 6, securityLevelId = excludedLevel))

        val access =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = setOf(staticLevel),
                reporterLevelIds = setOf(reporterLevel),
                assigneeLevelIds = setOf(assigneeLevel),
            )

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, access)

        assertThat(page.totalElements).isEqualTo(4L)
        assertThat(page.content).hasSize(4)
    }
}

// IssueRepository.listWithType 필터 통합 테스트 — BoardCardFilter(status/assignee/label) 적용 검증 (FR-SR-01 Task 2)

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.board.BoardCardFilter
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
 * IssueRepository.listWithType 필터 통합 테스트 (FR-SR-01 Task 2).
 *
 * [BoardCardFilter] 의 statusKeys / assigneeIds / labels 필드가 SQL WHERE 술어로 올바르게 변환되는지
 * 실 PostgreSQL(Testcontainers — 테스트용 DB를 도커로 자동 실행하는 라이브러리) 에서 검증한다.
 *
 * 공유 Testcontainers 인스턴스: [IssueTestcontainersBase.postgres] JVM singleton 재사용.
 *
 * ## 테스트 시나리오
 * - EC1. EMPTY 필터 — 필터 없으면 전체 목록(기존 동작 보존).
 * - S2. status 단일 필터 — 해당 상태 이슈만 반환.
 * - S3. status 다중값 — 필드 내 OR (두 상태 중 하나면 포함).
 * - S4. status + assignee — 필드 간 AND (두 조건 모두 충족해야 포함).
 * - EC6. 필터가 count(totalElements)에 반영 — 필터 결과 수와 page.totalElements 일치.
 * - S6. label overlap 필터 — 라벨 배열 overlap(&&) 술어.
 * - S7/EC7. visibility 제한 access + 필터 — 권한 없는 이슈는 결과 0.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositoryFilterTest : IssueTestcontainersBase() {
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
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** unrestricted=true 접근권한 — 보안등급 필터 미적용 빠른경로. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /**
     * 테스트용 이슈를 생성하는 helper.
     *
     * @param seq IssueKey 고유성을 위한 순번.
     * @param currentStateKey 이슈 현재 상태 키.
     * @param assigneeId 담당자 UUID. null 이면 미배정.
     * @param labels 라벨 이름 목록.
     * @param reporterId 보고자 UUID.
     * @param securityLevelId 보안 등급 UUID. null 이면 공개.
     */
    private fun buildIssue(
        seq: Long,
        currentStateKey: String = "open",
        assigneeId: UUID? = null,
        labels: List<String> = emptyList(),
        reporterId: UUID = UUID.randomUUID(),
        securityLevelId: UUID? = null,
    ): Issue =
        Issue.create(
            id = IssueId(UUID.randomUUID()),
            key = IssueKey.of("TPRJ", seq),
            projectId = testProjectId,
            typeId = requireTaskTypeId(),
            summary = "filter-test seq=$seq state=$currentStateKey",
            reporterId = ActorId(reporterId),
            currentStateKey = currentStateKey,
            assigneeId = assigneeId?.let { ActorId(it) },
            labels = labels,
            securityLevelId = securityLevelId,
        )

    // ── EC1. EMPTY 필터 — 전체 목록 ────────────────────────────────────────

    /**
     * Given  open 이슈 2건, done 이슈 1건
     * When   BoardCardFilter.EMPTY 로 listWithType 호출
     * Then   전체 3건 반환 (기존 동작 보존).
     */
    @Test
    @Order(1)
    fun `EC1 - EMPTY 필터는 전체 목록을 반환한다`() {
        val actor = UUID.randomUUID()
        repository.insert(buildIssue(seq = 1, currentStateKey = "open", reporterId = actor))
        repository.insert(buildIssue(seq = 2, currentStateKey = "open", reporterId = actor))
        repository.insert(buildIssue(seq = 3, currentStateKey = "done", reporterId = actor))

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, unrestrictedAccess, BoardCardFilter.EMPTY)

        assertThat(page.totalElements).isEqualTo(3L)
        assertThat(page.content).hasSize(3)
    }

    // ── S2. status 단일 필터 ────────────────────────────────────────────────

    /**
     * Given  open 이슈 2건, in_progress 이슈 1건
     * When   statusKeys = ["in_progress"] 필터로 listWithType 호출
     * Then   in_progress 이슈 1건만 반환.
     */
    @Test
    @Order(2)
    fun `S2 - status 단일 필터는 해당 상태 이슈만 반환한다`() {
        val actor = UUID.randomUUID()
        repository.insert(buildIssue(seq = 1, currentStateKey = "open", reporterId = actor))
        repository.insert(buildIssue(seq = 2, currentStateKey = "open", reporterId = actor))
        repository.insert(buildIssue(seq = 3, currentStateKey = "in_progress", reporterId = actor))

        val filter = BoardCardFilter(statusKeys = listOf("in_progress"))
        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, unrestrictedAccess, filter)

        assertThat(page.content).hasSize(1)
        assertThat(page.content.first().summary).contains("state=in_progress")
    }

    // ── S3. status 다중값 — 필드 내 OR ─────────────────────────────────────

    /**
     * Given  open 이슈 1건, in_progress 이슈 1건, done 이슈 1건
     * When   statusKeys = ["open", "in_progress"] 필터로 listWithType 호출
     * Then   open + in_progress 이슈 2건 반환 (done 제외).
     */
    @Test
    @Order(3)
    fun `S3 - status 다중값은 필드 내 OR 로 결합된다`() {
        val actor = UUID.randomUUID()
        repository.insert(buildIssue(seq = 1, currentStateKey = "open", reporterId = actor))
        repository.insert(buildIssue(seq = 2, currentStateKey = "in_progress", reporterId = actor))
        repository.insert(buildIssue(seq = 3, currentStateKey = "done", reporterId = actor))

        val filter = BoardCardFilter(statusKeys = listOf("open", "in_progress"))
        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, unrestrictedAccess, filter)

        assertThat(page.content).hasSize(2)
        val summaries = page.content.map { it.summary }
        assertThat(summaries).allMatch { it.contains("state=open") || it.contains("state=in_progress") }
    }

    // ── S4. status + assignee — 필드 간 AND ────────────────────────────────

    /**
     * Given  actor 가 담당자이고 open 인 이슈 1건, actor 가 담당자이고 done 인 이슈 1건, 타인 담당자 open 이슈 1건
     * When   statusKeys = ["open"] AND assigneeIds = [actor] 필터로 listWithType 호출
     * Then   actor 담당 + open 이슈 1건만 반환.
     */
    @Test
    @Order(4)
    fun `S4 - status 와 assignee 는 필드 간 AND 로 결합된다`() {
        val actor = UUID.randomUUID()
        val other = UUID.randomUUID()
        repository.insert(buildIssue(seq = 1, currentStateKey = "open", assigneeId = actor))
        repository.insert(buildIssue(seq = 2, currentStateKey = "done", assigneeId = actor))
        repository.insert(buildIssue(seq = 3, currentStateKey = "open", assigneeId = other))

        val filter = BoardCardFilter(statusKeys = listOf("open"), assigneeIds = listOf(actor))
        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, unrestrictedAccess, filter)

        assertThat(page.content).hasSize(1)
        assertThat(page.content.first().summary).contains("state=open")
    }

    // ── EC6. 필터가 count(totalElements)에 반영 ────────────────────────────

    /**
     * Given  open 이슈 3건, done 이슈 2건
     * When   statusKeys = ["open"] 필터로 listWithType 호출
     * Then   page.totalElements = 3, content.size = 3.
     */
    @Test
    @Order(5)
    fun `EC6 - 필터 결과 수가 totalElements 에 반영된다`() {
        val actor = UUID.randomUUID()
        for (i in 1..3) {
            repository.insert(buildIssue(seq = i.toLong(), currentStateKey = "open", reporterId = actor))
        }
        for (i in 4..5) {
            repository.insert(buildIssue(seq = i.toLong(), currentStateKey = "done", reporterId = actor))
        }

        val filter = BoardCardFilter(statusKeys = listOf("open"))
        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, unrestrictedAccess, filter)

        assertThat(page.totalElements).isEqualTo(3L)
        assertThat(page.content).hasSize(3)
    }

    // ── S6. label overlap 필터 ──────────────────────────────────────────────

    /**
     * Given  "bug" 라벨 이슈 1건, "feature" 라벨 이슈 1건, 라벨 없는 이슈 1건
     * When   labels = ["bug"] 필터로 listWithType 호출
     * Then   "bug" 라벨 이슈 1건만 반환.
     */
    @Test
    @Order(6)
    fun `S6 - label overlap 필터는 해당 라벨 이슈만 반환한다`() {
        val actor = UUID.randomUUID()
        repository.insert(buildIssue(seq = 1, labels = listOf("bug"), reporterId = actor))
        repository.insert(buildIssue(seq = 2, labels = listOf("feature"), reporterId = actor))
        repository.insert(buildIssue(seq = 3, labels = emptyList(), reporterId = actor))

        val filter = BoardCardFilter(labels = listOf("bug"))
        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, unrestrictedAccess, filter)

        assertThat(page.content).hasSize(1)
        assertThat(page.content.first().summary).contains("seq=1")
    }

    // ── S7/EC7. visibility 제한 access + 필터 — 권한 없는 이슈 제외 ────────

    /**
     * Given  비멤버 등급 open 이슈 2건, NULL 등급 done 이슈 1건
     * When   비멤버 등급을 포함하지 않는 restrictedAccess + statusKeys=["open"] 필터로 조회
     * Then   권한 없는 open 이슈가 모두 제외되어 content 0건, totalElements=0.
     */
    @Test
    @Order(7)
    fun `S7 EC7 - visibility 제한 access 와 status 필터가 AND 로 결합된다`() {
        val actor = UUID.randomUUID()
        val excludedLevel = UUID.randomUUID()

        repository.insert(buildIssue(seq = 1, currentStateKey = "open", securityLevelId = excludedLevel, reporterId = actor))
        repository.insert(buildIssue(seq = 2, currentStateKey = "open", securityLevelId = excludedLevel, reporterId = actor))
        repository.insert(buildIssue(seq = 3, currentStateKey = "done", securityLevelId = null, reporterId = actor))

        val restrictedAccess =
            IssueSecurityAccess(
                unrestricted = false,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )
        val filter = BoardCardFilter(statusKeys = listOf("open"))
        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10), actor, restrictedAccess, filter)

        assertThat(page.totalElements).isEqualTo(0L)
        assertThat(page.content).isEmpty()
    }
}

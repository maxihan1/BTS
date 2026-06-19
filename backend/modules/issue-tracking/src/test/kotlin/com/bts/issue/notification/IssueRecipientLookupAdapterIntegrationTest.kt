// IssueRecipientLookupAdapter 통합 테스트 — watcherIds/componentLeadIds/previousAssigneeId 채움 검증 (FR-NT-03 Task 4)

package com.bts.issue.notification

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.history.IssueChangeGroup
import com.bts.issue.history.IssueChangeItem
import com.bts.issue.history.JdbcIssueChangeHistoryRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.watcher.repository.IssueWatcherRepository
import com.bts.shared.issue.IssueRecipients
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [IssueRecipientLookupAdapter] Testcontainers 통합 테스트 (FR-NT-03 Task 4).
 *
 * [IssueTestcontainersBase] 의 JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext + 각 Repository 를 직접 조합한다.
 *
 * ## 시나리오 (Task 1~3 기존 + Task 4 신규)
 *
 * - T1. reporter·assignee 모두 있는 이슈 → 정확한 reporterId/assigneeId 반환 (기존 유지)
 * - T2. assignee 없는 이슈 → assigneeId=null, reporterId 정상 반환 (기존 유지)
 * - T3. soft-deleted 이슈 → 빈 수신자 fail-safe (기존 유지)
 * - T4. 존재하지 않는 issueKey → 빈 수신자 fail-safe (기존 유지)
 * - T5. 워처 N명 → watcherIds 채움
 * - T6. 컴포넌트 2개 중 lead 1개만 지정 → componentLeadIds 에 지정된 lead만 포함
 * - T7. assignee 변경 이력 있음 → previousAssigneeId = 직전 from_value UUID
 * - T8. assignee 변경 이력 없음 → previousAssigneeId = null
 * - T9. 이슈 미존재 → IssueRecipients.empty()
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueRecipientLookupAdapterIntegrationTest : IssueTestcontainersBase() {
    private lateinit var adapter: IssueRecipientLookupAdapter
    private lateinit var watcherRepository: IssueWatcherRepository
    private lateinit var componentRepository: ComponentRepository
    private lateinit var historyRepository: JdbcIssueChangeHistoryRepository
    private lateinit var namedJdbc: NamedParameterJdbcTemplate

    /** V003 seed 에서 task 타입 id 를 DB 에서 직접 조회. value class 특성상 var+null 허용 초기화. */
    private var taskTypeId: IssueTypeId? = null

    /**
     * JVM 당 1회 실행 — adapter 초기화 + task 타입 id 조회.
     * [IssueTestcontainersBase.bootstrap] 이 먼저 실행되어 dsl/repository 가 초기화된 후 호출된다.
     */
    @BeforeAll
    fun setupAdapter() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        namedJdbc = NamedParameterJdbcTemplate(dataSource)

        watcherRepository = IssueWatcherRepository(dsl)
        componentRepository = ComponentRepository(dsl)
        historyRepository = JdbcIssueChangeHistoryRepository(namedJdbc)

        adapter =
            IssueRecipientLookupAdapter(
                issueRepository = repository,
                watcherRepository = watcherRepository,
                componentRepository = componentRepository,
                historyRepository = historyRepository,
            )

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

    /**
     * 각 테스트 전에 부모 [IssueTestcontainersBase.cleanIssues] 이후 실행된다 (JUnit 5 부모 먼저).
     *
     * - issue_watchers — issues ON DELETE CASCADE 로 cleanIssues() 에서 자동 정리.
     * - issue_components — issues ON DELETE CASCADE 로 cleanIssues() 에서 자동 정리.
     * - issue_change_item — issue_change_group(id) FK 자식이므로 group 보다 먼저 삭제해야 한다.
     * - issue_change_group — issues FK 없음(이력 보존 우선 설계). 별도 수동 정리.
     * - components — issue_components 자동 정리 후 부모 행 수동 정리.
     */
    @BeforeEach
    fun cleanRelatedData() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
                stmt.execute("DELETE FROM components WHERE project_id = '$testProjectId'")
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
        assigneeId: UUID? = null,
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

    // ── 기존 T1~T4 시나리오 유지 ─────────────────────────────────────────────

    /**
     * T1. reporter·assignee 모두 있는 이슈 — findRecipients 가 정확한 reporterId/assigneeId 를 반환한다.
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
     */
    @Test
    fun `T3 - soft-deleted 이슈 - 빈 수신자 반환`() {
        val reporterId = UUID.randomUUID()
        val inserted = seedIssue(sequence = 1L, reporterId = reporterId, assigneeId = null)

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
     */
    @Test
    fun `T4 - 존재하지 않는 이슈 키 - 빈 수신자 반환`() {
        val result = adapter.findRecipients("TPRJ-99999")

        assertThat(result).isEqualTo(IssueRecipients.empty())
    }

    // ── Task 4 신규 시나리오 T5~T9 ───────────────────────────────────────────

    /**
     * T5. 워처 N명 → watcherIds 에 등록된 사용자 UUID 가 모두 포함된다.
     *
     * Given   이슈에 워처 3명 등록
     * When    findRecipients(issueKey) 호출
     * Then    watcherIds = 등록한 3명의 UUID (순서 무관)
     */
    @Test
    fun `T5 - 워처 N명 등록된 이슈 - watcherIds 채움`() {
        val reporterId = UUID.randomUUID()
        val watcher1 = UUID.randomUUID()
        val watcher2 = UUID.randomUUID()
        val watcher3 = UUID.randomUUID()

        val inserted = seedIssue(sequence = 1L, reporterId = reporterId)
        watcherRepository.add(inserted.id.value, watcher1)
        watcherRepository.add(inserted.id.value, watcher2)
        watcherRepository.add(inserted.id.value, watcher3)

        val result = adapter.findRecipients(inserted.key.value)

        assertThat(result.watcherIds).containsExactlyInAnyOrder(watcher1, watcher2, watcher3)
    }

    /**
     * T6. 컴포넌트 2개 중 lead 1개만 지정 → componentLeadIds 에 지정된 lead UUID 만 포함된다.
     *
     * Given   컴포넌트 A(lead 있음) + 컴포넌트 B(lead 없음) 가 이슈에 연결
     * When    findRecipients(issueKey) 호출
     * Then    componentLeadIds = [컴포넌트 A의 leadUserId]
     */
    @Test
    fun `T6 - 컴포넌트 2개 중 lead 1개만 지정 - componentLeadIds에 지정된 lead만 포함`() {
        val reporterId = UUID.randomUUID()
        val leadId = UUID.randomUUID()

        val inserted = seedIssue(sequence = 1L, reporterId = reporterId)

        // 컴포넌트 A: lead 있음
        val compAId = insertComponent(name = "Backend", leadUserId = leadId)
        // 컴포넌트 B: lead 없음
        val compBId = insertComponent(name = "Frontend", leadUserId = null)

        // 이슈에 두 컴포넌트 연결
        linkIssueComponent(inserted.id.value, compAId)
        linkIssueComponent(inserted.id.value, compBId)

        val result = adapter.findRecipients(inserted.key.value)

        assertThat(result.componentLeadIds).containsExactly(leadId)
    }

    /**
     * T7. assignee 변경 이력 있음 → previousAssigneeId = 직전 변경의 from_value UUID.
     *
     * Given   assignee 변경 이력(from_value=이전담당자UUID)이 1건 이상 기록됨
     * When    findRecipients(issueKey) 호출
     * Then    previousAssigneeId = from_value 를 파싱한 UUID
     */
    @Test
    fun `T7 - assignee 변경 이력 있음 - previousAssigneeId가 직전 from_value UUID`() {
        val reporterId = UUID.randomUUID()
        val previousAssignee = UUID.randomUUID()

        val inserted = seedIssue(sequence = 1L, reporterId = reporterId)

        // assignee 변경 이력 기록 (from_value = previousAssignee UUID 문자열)
        historyRepository.record(
            IssueChangeGroup(
                issueId = inserted.id.value,
                issueKey = inserted.key.value,
                actorId = reporterId,
                items =
                    listOf(
                        IssueChangeItem(
                            field = "assignee",
                            fromValue = previousAssignee.toString(),
                            toValue = UUID.randomUUID().toString(),
                            fromLabel = null,
                            toLabel = null,
                        ),
                    ),
            ),
        )

        val result = adapter.findRecipients(inserted.key.value)

        assertThat(result.previousAssigneeId).isEqualTo(previousAssignee)
    }

    /**
     * T8. assignee 변경 이력 없음 → previousAssigneeId = null.
     *
     * Given   이슈에 변경 이력이 없거나 assignee 이외 필드만 변경됨
     * When    findRecipients(issueKey) 호출
     * Then    previousAssigneeId = null
     */
    @Test
    fun `T8 - assignee 변경 이력 없음 - previousAssigneeId는 null`() {
        val reporterId = UUID.randomUUID()
        val inserted = seedIssue(sequence = 1L, reporterId = reporterId)

        // assignee 이외 필드 변경만 기록
        historyRepository.record(
            IssueChangeGroup(
                issueId = inserted.id.value,
                issueKey = inserted.key.value,
                actorId = reporterId,
                items =
                    listOf(
                        IssueChangeItem(
                            field = "status",
                            fromValue = "open",
                            toValue = "in_progress",
                            fromLabel = null,
                            toLabel = null,
                        ),
                    ),
            ),
        )

        val result = adapter.findRecipients(inserted.key.value)

        assertThat(result.previousAssigneeId).isNull()
    }

    /**
     * T9. 이슈 미존재 → IssueRecipients.empty() (watcherIds/componentLeadIds/previousAssigneeId 모두 빈/null).
     */
    @Test
    fun `T9 - 이슈 미존재 - empty 반환`() {
        val result = adapter.findRecipients("TPRJ-88888")

        assertThat(result).isEqualTo(IssueRecipients.empty())
        assertThat(result.watcherIds).isEmpty()
        assertThat(result.componentLeadIds).isEmpty()
        assertThat(result.previousAssigneeId).isNull()
    }

    // ── DB 시드 헬퍼 ─────────────────────────────────────────────────────────

    /**
     * 컴포넌트를 직접 INSERT 하고 생성된 UUID 를 반환한다.
     * ComponentRepository.insert 를 재사용하지 않고 직접 SQL 로 시드한다 — 도메인 객체 생성 복잡성 회피.
     */
    private fun insertComponent(
        name: String,
        leadUserId: UUID?,
    ): UUID {
        val compId = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO components (id, project_id, name, lead_user_id) VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, compId)
                stmt.setObject(2, testProjectId)
                stmt.setString(3, name)
                stmt.setObject(4, leadUserId)
                stmt.executeUpdate()
            }
        }
        return compId
    }

    /**
     * issue_components 연결 행을 직접 INSERT 한다.
     */
    private fun linkIssueComponent(
        issueId: UUID,
        componentId: UUID,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO issue_components (issue_id, component_id) VALUES (?, ?)",
            ).use { stmt ->
                stmt.setObject(1, issueId)
                stmt.setObject(2, componentId)
                stmt.executeUpdate()
            }
        }
    }
}

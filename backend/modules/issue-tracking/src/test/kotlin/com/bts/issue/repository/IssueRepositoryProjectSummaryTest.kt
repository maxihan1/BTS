// IssueRepository 프로젝트 요약 원천 조회 Testcontainers 통합 테스트 — 보안 술어 재사용 · 분포 봉인 · 활동 피드
// ktlint(140)와 detekt(120)의 한도가 달라 그 사이 길이의 픽스처 한 줄이 서로 다른 요구를 받는다.
// 테스트 픽스처는 파일 단위로 억제하는 것이 저장소 관례다(같은 모듈에 21건).
@file:Suppress("MaxLineLength")

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 프로젝트 요약·활동 원천 조회 통합 테스트 (Jira 패리티 캠페인 PR ③).
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL + Flyway 마이그레이션을 재사용한다.
 * 세 메서드가 모두 정본 보안 술어 `buildActiveSecureWhere` 를 **재사용**하는지를
 * [IssueRepositoryCfdSourceTest] 와 동일한 시나리오 구성으로 검증한다(복제 금지).
 *
 * ## 검증 시나리오
 * - SUM-1. 요약 원천 행이 집계에 필요한 필드를 정확히 담는다.
 * - SUM-2. 소프트 삭제된 이슈는 제외된다.
 * - SUM-3. viewer 가 접근 불가한 보안 등급 이슈는 제외된다.
 * - SUM-4. 타 프로젝트 이슈는 제외된다.
 * - SUM-5. **이슈 1건이면 분포 4종의 합이 각각 정확히 1** — 조인 추가 시 행 부풀림을 잡는 전방 회귀 가드.
 * - HIS-1. 프로젝트 스코프 상태 이력이 since 하한을 지킨다.
 * - HIS-2. 이력 조회도 같은 보안 술어를 통과한다(타 프로젝트·삭제·보안등급 이슈의 이력 0건).
 * - ACT-1. 활동 피드는 최신순이고 limit 을 지킨다.
 * - ACT-2. 활동 피드도 같은 보안 술어를 통과한다.
 * - ACT-3. 한 그룹의 여러 항목이 같은 그룹 id 로 묶여 나온다.
 */
class IssueRepositoryProjectSummaryTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id — value class 는 lateinit 불가, nullable var 사용. */
    private var taskTypeId: IssueTypeId? = null

    /** 요약을 조회하는 viewer UUID — 가시성 판단 기준. */
    private val viewerId: UUID = UUID.randomUUID()

    @BeforeAll
    fun resolveTaskTypeId() {
        query("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1") { rs ->
            check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
            taskTypeId = IssueTypeId(rs.getLong(1))
        }
    }

    /**
     * 변경 이력은 `issues` 로의 FK 가 없어 부모 [IssueTestcontainersBase.cleanIssues] 의
     * `DELETE FROM issues` 로 지워지지 않는다. 테스트 간 누수를 막으려면 직접 지워야 한다.
     */
    @BeforeEach
    fun cleanChangeHistory() {
        exec("DELETE FROM issue_change_item")
        exec("DELETE FROM issue_change_group")
    }

    // ── SUM-1. 요약 원천 필드 ────────────────────────────────────────────────

    /**
     * Given  우선순위·담당자·마감일이 지정된 이슈 1건.
     * When   fetchActiveVisibleIssuesForSummary 호출.
     * Then   집계에 필요한 8개 필드가 삽입값과 일치한다.
     */
    @Test
    fun `SUM-1 - 요약 원천 행이 집계에 필요한 필드를 담는다`() {
        val assignee = UUID.randomUUID()
        val issue = insertIssue(seq = 1L, priority = 1, assigneeId = assignee)
        setDueDate(issue.id.value, LocalDate.of(2026, 9, 5))

        val rows = repository.fetchActiveVisibleIssuesForSummary("TPRJ", viewerId, unrestrictedAccess)

        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row.issueId).isEqualTo(issue.id.value)
        assertThat(row.typeId).isEqualTo(requireTypeId().value)
        assertThat(row.currentStateKey).isEqualTo("open")
        assertThat(row.priority).isEqualTo(1)
        assertThat(row.assigneeId).isEqualTo(assignee)
        assertThat(row.dueDate).isEqualTo(LocalDate.of(2026, 9, 5))
        assertThat(row.createdAt).isEqualTo(issue.createdAt)
        assertThat(row.updatedAt).isNotNull()
    }

    /**
     * Given  담당자·마감일이 없는 이슈 1건.
     * When   fetchActiveVisibleIssuesForSummary 호출.
     * Then   assigneeId 와 dueDate 가 null 로 온다(미할당 버킷을 만들 수 있어야 한다).
     */
    @Test
    fun `SUM-1b - 미할당 이슈는 assigneeId 와 dueDate 가 null 이다`() {
        insertIssue(seq = 1L)

        val rows = repository.fetchActiveVisibleIssuesForSummary("TPRJ", viewerId, unrestrictedAccess)

        assertThat(rows.single().assigneeId).isNull()
        assertThat(rows.single().dueDate).isNull()
    }

    // ── SUM-2~4. 보안 술어 재사용 ────────────────────────────────────────────

    @Test
    fun `SUM-2 - 소프트 삭제된 이슈는 제외된다`() {
        val active = insertIssue(seq = 1L)
        val deleted = insertIssue(seq = 2L)
        softDeleteIssue(deleted.id.value)

        val rows = repository.fetchActiveVisibleIssuesForSummary("TPRJ", viewerId, unrestrictedAccess)

        assertThat(rows.map { it.issueId }).containsExactly(active.id.value)
    }

    @Test
    fun `SUM-3 - viewer 가 접근 불가한 보안 등급 이슈는 제외된다`() {
        val publicIssue = insertIssue(seq = 1L, securityLevelId = null)
        val secretIssue = insertIssue(seq = 2L, securityLevelId = UUID.randomUUID())

        val rows = repository.fetchActiveVisibleIssuesForSummary("TPRJ", viewerId, restrictedAccess())

        assertThat(rows.map { it.issueId }).containsExactly(publicIssue.id.value)
        assertThat(rows.map { it.issueId }).doesNotContain(secretIssue.id.value)
    }

    @Test
    fun `SUM-4 - 타 프로젝트 이슈는 제외된다`() {
        val otherProjectId = insertOtherProject()
        val ownIssue = insertIssue(seq = 1L)
        insertIssue(seq = 1L, projectId = otherProjectId, projectKeyPrefix = "TPRJ2")

        val rows = repository.fetchActiveVisibleIssuesForSummary("TPRJ", viewerId, unrestrictedAccess)

        assertThat(rows.map { it.issueId }).containsExactly(ownIssue.id.value)
    }

    // ── SUM-5. 행 부풀림 전방 회귀 가드 ──────────────────────────────────────

    /**
     * Given  라벨 3개를 단 이슈 **1건**(`labels` 는 `issues` 의 `TEXT[]` 배열 컬럼 — V006).
     * When   fetchActiveVisibleIssuesForSummary 호출.
     * Then   행이 정확히 1개다 — 상태·우선순위·유형·담당자 어느 축으로 그룹핑해도 합이 1.
     *
     * **현재 결함을 봉인하는 테스트가 아니라 전방 회귀 가드다.** 지금 이 쿼리는
     * `.from(ISSUES).join(PROJECTS)`(N:1 FK) 하나뿐이고 라벨은 조인 테이블이 아니라 배열 컬럼이라,
     * 라벨을 몇 개 달든 행이 늘어날 수 **구조적으로** 없다. 이 픽스처로 재현되는 현재 결함은 없다.
     *
     * 이 테스트가 잡는 것은 앞으로 이 쿼리에 **1:N 조인이 추가되는 변경**이다
     * (라벨의 조인 테이블 정규화 · `issue_components` · `issue_version_links` 등).
     * `IssueTypeRepository` PR#31 이 그 양식이었다. 행이 2배가 되면 분포 4종의 합이 전부 2가 되어
     * 화면의 모든 숫자가 동시에 거짓이 되므로, 어느 축으로 조인이 들어오든 축별 합 단언이 red 가 된다.
     */
    @Test
    fun `SUM-5 - 이슈 1건이면 분포 4종의 합이 각각 정확히 1이다`() {
        val assignee = UUID.randomUUID()
        val issue =
            insertIssue(
                seq = 1L,
                priority = 2,
                assigneeId = assignee,
                labels = listOf("alpha", "beta", "gamma"),
            )

        val rows = repository.fetchActiveVisibleIssuesForSummary("TPRJ", viewerId, unrestrictedAccess)

        assertThat(rows).hasSize(1)
        assertThat(rows.count { it.issueId == issue.id.value }).isEqualTo(1)
        assertThat(rows.groupingBy { it.currentStateKey }.eachCount().values.sum()).isEqualTo(1)
        assertThat(rows.groupingBy { it.priority }.eachCount().values.sum()).isEqualTo(1)
        assertThat(rows.groupingBy { it.typeId }.eachCount().values.sum()).isEqualTo(1)
        assertThat(rows.groupingBy { it.assigneeId }.eachCount().values.sum()).isEqualTo(1)
    }

    // ── HIS-1~2. 프로젝트 스코프 상태 이력 ───────────────────────────────────

    /**
     * Given  since 이후 전환 1건 + since 이전 전환 1건.
     * When   fetchStatusChangesSinceForProject(since) 호출.
     * Then   since 이후 전환만 반환된다(하한 inclusive).
     */
    @Test
    fun `HIS-1 - since 이전 전환은 제외되고 이후 전환만 반환된다`() {
        val issue = insertIssue(seq = 1L)
        val since = utc(2026, 8, 21)
        insertStatusChange(issue, changedAt = since.minusDays(1).toInstant(), from = "open", to = "in_progress")
        insertStatusChange(issue, changedAt = since.plusDays(2).toInstant(), from = "in_progress", to = "done")

        val rows = repository.fetchStatusChangesSinceForProject("TPRJ", viewerId, unrestrictedAccess, since)

        assertThat(rows).hasSize(1)
        assertThat(rows.single().toValue).isEqualTo("done")
    }

    /**
     * Given  경계 시각(since 정각)의 전환 1건.
     * When   fetchStatusChangesSinceForProject(since) 호출.
     * Then   포함된다 — 하한은 inclusive 다.
     */
    @Test
    fun `HIS-1b - since 정각 전환은 포함된다`() {
        val issue = insertIssue(seq = 1L)
        val since = utc(2026, 8, 21)
        insertStatusChange(issue, changedAt = since.toInstant(), from = "open", to = "done")

        val rows = repository.fetchStatusChangesSinceForProject("TPRJ", viewerId, unrestrictedAccess, since)

        assertThat(rows).hasSize(1)
    }

    /**
     * Given  가시 이슈 1건 + 삭제 이슈 1건 + 보안등급 이슈 1건, 각각 전환 1건씩.
     * When   restrictedAccess 로 fetchStatusChangesSinceForProject 호출.
     * Then   가시 이슈의 전환만 반환된다 — 이력 경로가 보안 술어를 우회하지 않는다.
     */
    @Test
    fun `HIS-2 - 이력 조회도 보안 술어를 통과한다`() {
        val since = utc(2026, 8, 21)
        val visible = insertIssue(seq = 1L)
        val deleted = insertIssue(seq = 2L)
        val secret = insertIssue(seq = 3L, securityLevelId = UUID.randomUUID())
        listOf(visible, deleted, secret).forEach {
            insertStatusChange(it, changedAt = since.plusDays(1).toInstant(), from = "open", to = "done")
        }
        softDeleteIssue(deleted.id.value)

        val rows = repository.fetchStatusChangesSinceForProject("TPRJ", viewerId, restrictedAccess(), since)

        assertThat(rows.map { it.issueId }).containsExactly(visible.id.value)
    }

    // ── ACT-1~3. 활동 피드 ───────────────────────────────────────────────────

    /**
     * Given  전환 3건(시각 상이).
     * When   limit=2 로 fetchProjectActivity 호출.
     * Then   최신 2건만, 최신순으로 반환된다.
     */
    @Test
    fun `ACT-1 - 활동 피드는 최신순이고 limit 을 지킨다`() {
        val issue = insertIssue(seq = 1L)
        val base = utc(2026, 9, 1)
        insertStatusChange(issue, changedAt = base.toInstant(), from = "open", to = "a")
        insertStatusChange(issue, changedAt = base.plusDays(1).toInstant(), from = "a", to = "b")
        insertStatusChange(issue, changedAt = base.plusDays(2).toInstant(), from = "b", to = "c")

        val rows = repository.fetchProjectActivity("TPRJ", viewerId, unrestrictedAccess, limit = 2)

        assertThat(rows.map { it.toValue }).containsExactly("c", "b")
    }

    @Test
    fun `ACT-2 - 활동 피드도 보안 술어를 통과한다`() {
        val visible = insertIssue(seq = 1L)
        val secret = insertIssue(seq = 2L, securityLevelId = UUID.randomUUID())
        val at = utc(2026, 9, 1).toInstant()
        insertStatusChange(visible, changedAt = at, from = "open", to = "done")
        insertStatusChange(secret, changedAt = at, from = "open", to = "done")

        val rows = repository.fetchProjectActivity("TPRJ", viewerId, restrictedAccess(), limit = 20)

        assertThat(rows.map { it.issueKey }).containsExactly(visible.key.value)
    }

    /**
     * Given  한 변경 그룹에 status·assignee 두 항목.
     * When   fetchProjectActivity 호출.
     * Then   두 행이 같은 groupId·issueKey·actorId 로 나온다 — 서비스가 그룹 단위로 묶을 수 있다.
     */
    @Test
    fun `ACT-3 - 한 그룹의 여러 항목이 같은 그룹 id 로 묶여 나온다`() {
        val issue = insertIssue(seq = 1L)
        val actor = UUID.randomUUID()
        val groupId = insertChangeGroup(issue, utc(2026, 9, 1).toInstant(), actor)
        insertChangeItem(groupId, "status", "open", "done", "열림", "완료")
        insertChangeItem(groupId, "assignee", null, actor.toString(), null, "홍길동")

        val rows = repository.fetchProjectActivity("TPRJ", viewerId, unrestrictedAccess, limit = 20)

        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.groupId }.distinct()).containsExactly(groupId)
        assertThat(rows.map { it.issueKey }.distinct()).containsExactly(issue.key.value)
        assertThat(rows.map { it.actorId }.distinct()).containsExactly(actor)
        assertThat(rows.map { it.field }).containsExactlyInAnyOrder("status", "assignee")
        assertThat(rows.first { it.field == "status" }.toLabel).isEqualTo("완료")
    }

    /**
     * Given  actor 가 없는 시스템 자동 변경 1건.
     * When   fetchProjectActivity 호출.
     * Then   actorId 가 null 로 온다(시스템 변경을 표현할 수 있어야 한다).
     */
    @Test
    fun `ACT-4 - 시스템 자동 변경은 actorId 가 null 이다`() {
        val issue = insertIssue(seq = 1L)
        val groupId = insertChangeGroup(issue, utc(2026, 9, 1).toInstant(), actorId = null)
        insertChangeItem(groupId, "status", "open", "done", null, null)

        val rows = repository.fetchProjectActivity("TPRJ", viewerId, unrestrictedAccess, limit = 20)

        assertThat(rows.single().actorId).isNull()
    }

    // ── 픽스처 헬퍼 ──────────────────────────────────────────────────────────

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

    @Suppress("LongParameterList")
    private fun insertIssue(
        seq: Long,
        projectId: UUID = testProjectId,
        projectKeyPrefix: String = "TPRJ",
        securityLevelId: UUID? = null,
        priority: Int = 3,
        assigneeId: UUID? = null,
        labels: List<String> = emptyList(),
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of(projectKeyPrefix, seq),
                projectId = projectId,
                typeId = requireTypeId(),
                summary = "요약 원천 테스트 이슈 $seq",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                priority = priority,
                labels = labels,
                assigneeId = assigneeId?.let { ActorId(it) },
                securityLevelId = securityLevelId,
            ),
        )

    private fun softDeleteIssue(id: UUID) = exec("UPDATE issues SET deleted_at = NOW() WHERE id = ?") { it.setObject(1, id) }

    private fun setDueDate(
        id: UUID,
        due: LocalDate,
    ) = exec("UPDATE issues SET due_date = ? WHERE id = ?") {
        it.setObject(1, due)
        it.setObject(2, id)
    }

    /** 두 번째 프로젝트(TPRJ2)를 삽입하고 id 를 반환한다. */
    private fun insertOtherProject(): UUID {
        exec("INSERT INTO projects (key, name) VALUES ('TPRJ2', 'Test Project 2') ON CONFLICT (key) DO NOTHING")
        var id: UUID? = null
        query("SELECT id FROM projects WHERE key = 'TPRJ2'") { rs ->
            rs.next()
            id = rs.getObject(1) as UUID
        }
        return requireNotNull(id)
    }

    private fun insertChangeGroup(
        issue: Issue,
        createdAt: Instant,
        actorId: UUID?,
    ): Long {
        var groupId: Long? = null
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO issue_change_group (issue_id, issue_key, actor_id, created_at) " +
                    "VALUES (?, ?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, issue.id.value)
                stmt.setString(2, issue.key.value)
                stmt.setObject(3, actorId)
                stmt.setTimestamp(4, Timestamp.from(createdAt))
                stmt.executeQuery().use { rs ->
                    rs.next()
                    groupId = rs.getLong(1)
                }
            }
        }
        return requireNotNull(groupId)
    }

    @Suppress("LongParameterList")
    private fun insertChangeItem(
        groupId: Long,
        field: String,
        fromValue: String?,
        toValue: String?,
        fromLabel: String?,
        toLabel: String?,
    ) = exec(
        "INSERT INTO issue_change_item (group_id, field, from_value, to_value, from_label, to_label) " +
            "VALUES (?, ?, ?, ?, ?, ?)",
    ) {
        it.setLong(1, groupId)
        it.setString(2, field)
        it.setString(3, fromValue)
        it.setString(4, toValue)
        it.setString(5, fromLabel)
        it.setString(6, toLabel)
    }

    private fun insertStatusChange(
        issue: Issue,
        changedAt: Instant,
        from: String?,
        to: String?,
        actorId: UUID? = UUID.randomUUID(),
    ) {
        val groupId = insertChangeGroup(issue, changedAt, actorId)
        insertChangeItem(groupId, "status", from, to, null, to)
    }

    private fun utc(
        year: Int,
        month: Int,
        day: Int,
    ): OffsetDateTime = LocalDate.of(year, month, day).atStartOfDay().atOffset(ZoneOffset.UTC)

    private fun exec(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit = {},
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                bind(stmt)
                stmt.executeUpdate()
            }
        }
    }

    private fun query(
        sql: String,
        read: (java.sql.ResultSet) -> Unit,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs -> read(rs) }
            }
        }
    }
}

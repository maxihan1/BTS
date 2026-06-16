// IssueWatcherRepositoryTest — IssueWatcherRepository CRUD Testcontainers 통합 테스트 (FR-WT-01 Task 2)

package com.bts.issue.watcher

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.shared.issue.IssueTypeId
import com.bts.issue.watcher.repository.IssueWatcherRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueWatcherRepository] CRUD Testcontainers 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 * `issue_watchers.issue_id` 가 `issues(id)` FK 이므로 각 테스트 전 부모 이슈를 시드한다.
 *
 * 테스트 시나리오 (FR-WT-01 Task 2).
 * - T2-A. add + listByIssue — 추가 후 목록 반영.
 * - T2-B. add 멱등성 — 동일 (issue, user) 쌍 2회 add 시 1행만 존재 (ON CONFLICT DO NOTHING).
 * - T2-C. remove — 삭제 성공 시 true, listByIssue 에서 제거.
 * - T2-D. remove 멱등성 — 존재하지 않는 워처 remove 시 false 반환 (예외 없음).
 * - T2-E. countByIssue — 워처 수 정확히 반환.
 * - T2-F. existsForUser true/false — 등록/미등록 사용자 각각.
 * - T2-G. listByIssue 정렬 — created_at ASC (오래된 순).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueWatcherRepositoryTest : IssueTestcontainersBase() {
    /** V003 seed 의 task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    private lateinit var watcherRepository: IssueWatcherRepository

    // ── setup ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setupRepository() {
        watcherRepository = IssueWatcherRepository(dsl)
        if (taskTypeId == null) {
            taskTypeId = loadTaskTypeId()
        }
    }

    /** 각 테스트 전 issue_watchers 전체 삭제. issues 는 부모 cleanIssues() 가 처리. */
    @BeforeEach
    fun cleanWatchers() {
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM issue_watchers") }
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

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** 이슈 1건을 삽입하고 반환한다. seqNum 은 이슈 키 시퀀스 번호. */
    private fun insertIssue(seqNum: Long): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seqNum),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "워처 통합 테스트 이슈 $seqNum",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    // ── T2-A. add + listByIssue ───────────────────────────────────────────────

    /**
     * Given  부모 이슈 존재
     * When   add(issueId, userId) 후 listByIssue(issueId)
     * Then   해당 userId 를 포함하는 WatcherRow 1건 반환.
     */
    @Test
    @Order(1)
    fun `T2-A - add 후 listByIssue 에 반영`() {
        val issue = insertIssue(1L)
        val userId = UUID.randomUUID()

        watcherRepository.add(issue.id.value, userId)
        val result = watcherRepository.listByIssue(issue.id.value)

        assertThat(result).hasSize(1)
        assertThat(result[0].userId).isEqualTo(userId)
        assertThat(result[0].createdAt).isNotNull()
    }

    // ── T2-B. add 멱등성 ─────────────────────────────────────────────────────

    /**
     * Given  부모 이슈 존재
     * When   동일 (issueId, userId) 쌍으로 add 2회 호출
     * Then   listByIssue 결과 1건만 존재 (ON CONFLICT DO NOTHING 멱등).
     */
    @Test
    @Order(2)
    fun `T2-B - 동일 (issue, user) 쌍 2회 add 시 1행만 존재`() {
        val issue = insertIssue(1L)
        val userId = UUID.randomUUID()

        watcherRepository.add(issue.id.value, userId)
        watcherRepository.add(issue.id.value, userId)

        val result = watcherRepository.listByIssue(issue.id.value)
        assertThat(result).hasSize(1)
    }

    // ── T2-C. remove ─────────────────────────────────────────────────────────

    /**
     * Given  add 된 워처 1건
     * When   remove(issueId, userId)
     * Then   true 반환, listByIssue 결과 빈 리스트.
     */
    @Test
    @Order(3)
    fun `T2-C - remove 성공 시 true 반환 및 listByIssue 에서 제거`() {
        val issue = insertIssue(1L)
        val userId = UUID.randomUUID()
        watcherRepository.add(issue.id.value, userId)

        val removed = watcherRepository.remove(issue.id.value, userId)

        assertThat(removed).isTrue()
        assertThat(watcherRepository.listByIssue(issue.id.value)).isEmpty()
    }

    // ── T2-D. remove 멱등성 ──────────────────────────────────────────────────

    /**
     * Given  존재하지 않는 (issueId, userId) 쌍
     * When   remove(issueId, userId)
     * Then   false 반환 (예외 없음).
     */
    @Test
    @Order(4)
    fun `T2-D - 존재하지 않는 워처 remove 시 false 반환`() {
        val issue = insertIssue(1L)
        val removed = watcherRepository.remove(issue.id.value, UUID.randomUUID())
        assertThat(removed).isFalse()
    }

    // ── T2-E. countByIssue ───────────────────────────────────────────────────

    /**
     * Given  동일 이슈에 워처 2명 add
     * When   countByIssue(issueId)
     * Then   2 반환.
     */
    @Test
    @Order(5)
    fun `T2-E - countByIssue 는 워처 수를 정확히 반환`() {
        val issue = insertIssue(1L)
        watcherRepository.add(issue.id.value, UUID.randomUUID())
        watcherRepository.add(issue.id.value, UUID.randomUUID())

        assertThat(watcherRepository.countByIssue(issue.id.value)).isEqualTo(2)
    }

    // ── T2-F. existsForUser true/false ────────────────────────────────────────

    /**
     * Given  userId1 은 add, userId2 는 미add
     * When   existsForUser 각각 호출
     * Then   userId1 true, userId2 false.
     */
    @Test
    @Order(6)
    fun `T2-F - existsForUser 등록 사용자 true 미등록 사용자 false`() {
        val issue = insertIssue(1L)
        val watcherId = UUID.randomUUID()
        val nonWatcherId = UUID.randomUUID()

        watcherRepository.add(issue.id.value, watcherId)

        assertThat(watcherRepository.existsForUser(issue.id.value, watcherId)).isTrue()
        assertThat(watcherRepository.existsForUser(issue.id.value, nonWatcherId)).isFalse()
    }

    // ── T2-G. listByIssue 정렬 ────────────────────────────────────────────────

    /**
     * Given  동일 이슈에 워처 2명 시간차 add
     * When   listByIssue(issueId)
     * Then   created_at ASC (오래된 순) 정렬.
     */
    @Test
    @Order(7)
    fun `T2-G - listByIssue 는 created_at ASC 정렬`() {
        val issue = insertIssue(1L)
        val firstUser = UUID.randomUUID()
        val secondUser = UUID.randomUUID()

        watcherRepository.add(issue.id.value, firstUser)
        // 두 번째 insert 가 created_at 기본값 now() 로 약간 늦게 들어가도록 DB 레벨 INSERT 순서 보장
        watcherRepository.add(issue.id.value, secondUser)

        val result = watcherRepository.listByIssue(issue.id.value)

        assertThat(result).hasSize(2)
        // ASC 정렬 — 앞 원소 createdAt 이 뒤 원소보다 이전이거나 같아야 함
        assertThat(result[0].createdAt).isBeforeOrEqualTo(result[1].createdAt)
    }
}

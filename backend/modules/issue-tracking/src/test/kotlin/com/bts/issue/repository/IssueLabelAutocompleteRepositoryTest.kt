// 라벨 자동완성 prefix 집계 통합 테스트 — FR-IS-09 Task 1

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
import java.time.Instant
import java.util.UUID

/**
 * IssueRepository.findLabelsByPrefix 통합 테스트.
 *
 * IssueTestcontainersBase 상속 — JVM singleton Testcontainers + Flyway 마이그레이션.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * 시드 데이터.
 * - 활성 이슈: "bug" 라벨 3개 이슈, "backend" 1, "billing" 1
 * - 삭제 이슈(deleted_at NOT NULL): "deleted-only" 라벨 1개 이슈
 *
 * 검증 시나리오.
 * - S1. prefix="b" → ["bug","backend","billing"] (빈도순, 동률은 알파벳순 tiebreak)
 * - S2. prefix="d" → [] (삭제된 이슈 라벨은 집계 제외)
 * - S3. prefix=""  → 전체 top-N (빈도순)
 * - S4. prefix="b%" → [] (와일드카드 이스케이프 — 리터럴 "b%" 라벨 없음)
 * - S5. 동률 tiebreak — "backend"(1)과 "billing"(1)이 알파벳순으로 "backend" 먼저
 */
class IssueLabelAutocompleteRepositoryTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val sql = "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    taskTypeId = IssueTypeId(rs.getLong(1))
                }
            }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    // ── seed helpers ─────────────────────────────────────────────────────────────

    /** 라벨 목록을 가진 활성 이슈를 DB 에 삽입하고 반환한다. */
    private fun insertActiveIssue(
        keySeq: Long,
        labels: List<String>,
    ): Issue {
        val typeId = requireTaskTypeId()
        val issue =
            Issue(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey("TPRJ-$keySeq"),
                projectId = testProjectId,
                summary = "label seed $keySeq",
                reporterId = ActorId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                currentStateKey = "open",
                version = 1L,
                deletedAt = null,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                typeId = typeId,
                description = null,
                priority = 3,
                labels = labels,
                environment = null,
                impact = null,
                assigneeId = null,
                resolutionId = null,
            )
        return repository.insert(issue)
    }

    /** 삭제된 이슈(deleted_at NOT NULL)를 직접 SQL 로 삽입한다. */
    private fun insertDeletedIssue(
        keySeq: Long,
        labels: List<String>,
    ) {
        val typeId = requireTaskTypeId()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val labelsArray = conn.createArrayOf("text", labels.toTypedArray())
            conn.prepareStatement(
                """INSERT INTO issues
                   (id, key, project_id, summary, reporter_id, current_state_key, version,
                    type_id, priority, labels, deleted_at)
                   VALUES (?, ?, ?, ?, ?, 'open', 1, ?, 3, ?, NOW())""",
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, "TPRJ-$keySeq")
                stmt.setObject(3, testProjectId)
                stmt.setString(4, "deleted seed $keySeq")
                stmt.setObject(5, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                stmt.setLong(6, typeId.value)
                stmt.setArray(7, labelsArray)
                stmt.executeUpdate()
            }
        }
    }

    // ── 공통 시드 삽입 ──────────────────────────────────────────────────────────

    /** 각 테스트 전 cleanIssues 가 실행되므로 시드는 각 테스트에서 개별 삽입한다. */
    private fun seedData() {
        // 활성 이슈: "bug" 3개, "backend" 1개, "billing" 1개
        insertActiveIssue(1L, listOf("bug"))
        insertActiveIssue(2L, listOf("bug"))
        insertActiveIssue(3L, listOf("bug"))
        insertActiveIssue(4L, listOf("backend"))
        insertActiveIssue(5L, listOf("billing"))
        // 삭제 이슈: "deleted-only"
        insertDeletedIssue(6L, listOf("deleted-only"))
    }

    // ── S1. prefix="b" ────────────────────────────────────────────────────────

    /**
     * Given  "bug"×3, "backend"×1, "billing"×1 활성 이슈 + 삭제 이슈 1건
     * When   findLabelsByPrefix("b", 10) 호출
     * Then   ["bug","backend","billing"] 순서 (빈도 DESC, 동률 알파벳 ASC)
     */
    @Test
    fun `S1 - prefix b - 빈도순 정렬, bug 가 먼저`() {
        seedData()

        val result = repository.findLabelsByPrefix("b", 10)

        assertThat(result).containsExactly("bug", "backend", "billing")
    }

    // ── S2. 삭제 이슈 제외 ───────────────────────────────────────────────────

    /**
     * Given  삭제 이슈에만 "deleted-only" 라벨 존재
     * When   findLabelsByPrefix("d", 10) 호출
     * Then   빈 리스트 반환 — 삭제된 이슈 라벨은 집계에서 제외
     */
    @Test
    fun `S2 - prefix d - 삭제 이슈 라벨은 결과에 없다`() {
        seedData()

        val result = repository.findLabelsByPrefix("d", 10)

        assertThat(result).isEmpty()
    }

    // ── S3. prefix="" 전체 ───────────────────────────────────────────────────

    /**
     * Given  시드 데이터
     * When   findLabelsByPrefix("", 10) 호출
     * Then   전체 라벨 빈도순 — "bug"(3) 먼저, 나머지 동률은 알파벳순
     */
    @Test
    fun `S3 - prefix empty - 전체 top-N 반환, bug 가 먼저`() {
        seedData()

        val result = repository.findLabelsByPrefix("", 10)

        // 빈도: bug=3, backend=1, billing=1. 동률은 알파벳순 → ["bug","backend","billing"]
        assertThat(result).containsExactly("bug", "backend", "billing")
    }

    // ── S4. 와일드카드 이스케이프 ─────────────────────────────────────────────

    /**
     * Given  "b%" 라는 리터럴 라벨을 가진 이슈가 없는 시드 데이터
     * When   findLabelsByPrefix("b%", 10) 호출
     * Then   빈 리스트 반환 — "b%"가 와일드카드가 아닌 리터럴로 처리됨
     */
    @Test
    fun `S4 - prefix b-percent - 와일드카드 이스케이프로 리터럴 매칭, 결과 없음`() {
        seedData()

        val result = repository.findLabelsByPrefix("b%", 10)

        assertThat(result).isEmpty()
    }

    // ── S5. limit 준수 ────────────────────────────────────────────────────────

    /**
     * Given  시드 데이터 (라벨 3종)
     * When   findLabelsByPrefix("", 2) — limit=2
     * Then   상위 2개만 반환
     */
    @Test
    fun `S5 - limit 2 - 상위 2개만 반환`() {
        seedData()

        val result = repository.findLabelsByPrefix("", 2)

        assertThat(result).hasSize(2)
        assertThat(result.first()).isEqualTo("bug")
    }

    // ── S6. 동률 tiebreak 결정적 순서 ────────────────────────────────────────

    /**
     * Given  "alpha"×2, "beta"×2 활성 이슈 (동률)
     * When   findLabelsByPrefix("", 10) 호출
     * Then   알파벳순으로 "alpha" 먼저
     */
    @Test
    fun `S6 - 동률 tiebreak - 알파벳순 alpha 가 beta 앞`() {
        insertActiveIssue(1L, listOf("alpha"))
        insertActiveIssue(2L, listOf("alpha"))
        insertActiveIssue(3L, listOf("beta"))
        insertActiveIssue(4L, listOf("beta"))

        val result = repository.findLabelsByPrefix("", 10)

        assertThat(result).containsExactly("alpha", "beta")
    }
}

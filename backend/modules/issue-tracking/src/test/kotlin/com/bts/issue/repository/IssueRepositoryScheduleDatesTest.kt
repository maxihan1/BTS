// IssueRepository 날짜 필드(startDate/dueDate/targetDate) 통합 테스트 — T12 계열 (FR-PL-01 Task 4)

package com.bts.issue.repository

import com.bts.issue.application.DatePatch
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
import java.time.LocalDate
import java.util.UUID

/**
 * IssueRepository 날짜 필드 통합 테스트.
 *
 * FR-PL-01 Task 4 에서 추가된 startDate/dueDate/targetDate 필드의 Set/Clear/Unchanged 동작과
 * toIssue() 매퍼의 날짜 컬럼 읽기를 검증한다.
 *
 * IssueTestcontainersBase 상속으로 JVM singleton PostgreSQL container + Flyway migrate 를 공유한다.
 * IssueRepositoryTest 가 LargeClass detekt 임계를 초과하지 않도록 T12 계열을 별도 파일로 분리.
 *
 * 테스트 시나리오.
 * - T12a. DatePatch.Set — 날짜 3개 지정 시 DB 에 영속되고 findByKey 에서 반영된다.
 * - T12b. DatePatch.Clear — 날짜 지정 후 Clear 시 DB NULL 로 클리어된다.
 * - T12c. DatePatch.Unchanged — 기본값(Unchanged) 시 기존 날짜가 변경되지 않는다.
 * - T12d. toIssue() 매퍼 — startDate/dueDate/targetDate 3컬럼이 findByKey 에서 올바르게 반환된다.
 * - T12e. toInsertRecord — copy 로 날짜 지정 후 insert 시 날짜가 DB 에 영속된다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositoryScheduleDatesTest : IssueTestcontainersBase() {
    /**
     * V003 seed 에서 task 타입의 id 를 DB 에서 직접 조회한다.
     * IssueTypeId 는 value class 이므로 lateinit 불가 — var + null 허용으로 초기화.
     */
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

    /** 각 테스트에서 안전하게 taskTypeId 를 꺼내는 helper. resolveTaskTypeId 이후 항상 non-null. */
    @Suppress("MaxLineLength")
    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다 — resolveTaskTypeId 실행 확인" }

    // ── T12a. 날짜 필드 Set → 재조회 반영 (FR-PL-01 Task 4) ─────────────────────────

    /**
     * Given  날짜 없이 삽입된 이슈 (startDate/dueDate/targetDate = null)
     * When   updateFields 에 DatePatch.Set 으로 세 날짜를 지정
     * Then   반환값 1, findByKey 로 조회 시 지정한 날짜가 그대로 반영된다.
     */
    @Test
    @Order(30)
    fun `T12a - updateFields - DatePatch Set 시 날짜가 DB 에 영속되고 재조회 시 반영된다`() {
        val key = IssueKey.of("TPRJ", 1L)
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "날짜 Set 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

        val start = LocalDate.of(2026, 7, 1)
        val due = LocalDate.of(2026, 7, 31)
        val target = LocalDate.of(2026, 8, 15)

        val updateCount =
            repository.updateFields(
                key = key,
                patch =
                    IssueFieldPatch(
                        startDate = DatePatch.Set(start),
                        dueDate = DatePatch.Set(due),
                        targetDate = DatePatch.Set(target),
                    ),
                expectedVersion = 1L,
            )

        assertThat(updateCount).isEqualTo(1)
        val found = requireNotNull(repository.findByKey(key)) { "updateFields 후 이슈 조회 불가" }
        assertThat(found.startDate).isEqualTo(start)
        assertThat(found.dueDate).isEqualTo(due)
        assertThat(found.targetDate).isEqualTo(target)
        assertThat(found.version).isEqualTo(2L)
    }

    // ── T12b. 날짜 필드 Clear → null (FR-PL-01 Task 4) ───────────────────────────

    /**
     * Given  startDate/dueDate/targetDate 가 지정된 이슈
     * When   updateFields 에 DatePatch.Clear 로 세 날짜를 클리어
     * Then   반환값 1, findByKey 로 조회 시 세 날짜가 null 로 반환된다.
     */
    @Test
    @Order(31)
    fun `T12b - updateFields - DatePatch Clear 시 날짜가 DB NULL 로 클리어된다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val start = LocalDate.of(2026, 7, 1)
        val due = LocalDate.of(2026, 7, 31)
        val target = LocalDate.of(2026, 8, 15)
        repository.insert(
            Issue
                .create(
                    id = IssueId(UUID.randomUUID()),
                    key = key,
                    projectId = testProjectId,
                    typeId = requireTaskTypeId(),
                    summary = "날짜 Clear 테스트",
                    reporterId = ActorId(UUID.randomUUID()),
                    currentStateKey = "open",
                ).copy(startDate = start, dueDate = due, targetDate = target),
        )
        // version=1 로 삽입됨 (insert 는 version bump 없음)

        val updateCount =
            repository.updateFields(
                key = key,
                patch =
                    IssueFieldPatch(
                        startDate = DatePatch.Clear,
                        dueDate = DatePatch.Clear,
                        targetDate = DatePatch.Clear,
                    ),
                expectedVersion = 1L,
            )

        assertThat(updateCount).isEqualTo(1)
        val found = requireNotNull(repository.findByKey(key)) { "Clear 후 이슈 조회 불가" }
        assertThat(found.startDate).isNull()
        assertThat(found.dueDate).isNull()
        assertThat(found.targetDate).isNull()
        assertThat(found.version).isEqualTo(2L)
    }

    // ── T12c. 날짜 필드 Unchanged → 기존 유지 (FR-PL-01 Task 4) ─────────────────

    /**
     * Given  startDate 가 지정된 이슈
     * When   updateFields 에 DatePatch.Unchanged (기본값) 로 summary 만 변경
     * Then   startDate 가 기존 값 그대로 유지된다.
     */
    @Test
    @Order(32)
    fun `T12c - updateFields - DatePatch Unchanged 시 기존 날짜가 변경되지 않는다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val originalStart = LocalDate.of(2026, 6, 1)
        repository.insert(
            Issue
                .create(
                    id = IssueId(UUID.randomUUID()),
                    key = key,
                    projectId = testProjectId,
                    typeId = requireTaskTypeId(),
                    summary = "날짜 Unchanged 테스트",
                    reporterId = ActorId(UUID.randomUUID()),
                    currentStateKey = "open",
                ).copy(startDate = originalStart),
        )

        // DatePatch 필드를 기본값(Unchanged)으로 두고 summary 만 변경
        repository.updateFields(
            key = key,
            patch = IssueFieldPatch(summary = "변경된 제목"),
            expectedVersion = 1L,
        )

        val found = requireNotNull(repository.findByKey(key)) { "Unchanged 후 이슈 조회 불가" }
        assertThat(found.startDate).isEqualTo(originalStart)
    }

    // ── T12d. toIssue() 매퍼 — 날짜 3컬럼 읽기 (FR-PL-01 Task 4 B3) ─────────────

    /**
     * Given  startDate/dueDate/targetDate 를 모두 지정해 삽입한 이슈
     * When   findByKey 로 조회
     * Then   toIssue() 매퍼가 세 날짜 컬럼을 올바르게 매핑한다.
     */
    @Test
    @Order(33)
    fun `T12d - toIssue 매퍼 - startDate dueDate targetDate 3컬럼이 findByKey 에서 올바르게 반환된다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val start = LocalDate.of(2026, 1, 10)
        val due = LocalDate.of(2026, 3, 20)
        val target = LocalDate.of(2026, 4, 5)

        repository.insert(
            Issue
                .create(
                    id = IssueId(UUID.randomUUID()),
                    key = key,
                    projectId = testProjectId,
                    typeId = requireTaskTypeId(),
                    summary = "매퍼 3컬럼 읽기 테스트",
                    reporterId = ActorId(UUID.randomUUID()),
                    currentStateKey = "open",
                ).copy(startDate = start, dueDate = due, targetDate = target),
        )

        val found = requireNotNull(repository.findByKey(key)) { "매퍼 테스트 — 이슈 조회 불가" }

        assertThat(found.startDate).isEqualTo(start)
        assertThat(found.dueDate).isEqualTo(due)
        assertThat(found.targetDate).isEqualTo(target)
    }
}

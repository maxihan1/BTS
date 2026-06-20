// IssueRepository 추정 필드 + worklog 롤업 통합 테스트 — FR-TT-01 Task 3

package com.bts.issue.repository

import com.bts.issue.application.EstimatePatch
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * IssueRepository 추정 필드 및 worklog 롤업 통합 테스트 (FR-TT-01 Task 3).
 *
 * IssueTestcontainersBase 상속으로 JVM singleton PostgreSQL container + Flyway migrate 를 공유한다.
 * cleanIssues @BeforeEach 로 worklogs 행도 같이 삭제된다(issues.id FK ON DELETE CASCADE).
 *
 * 테스트 시나리오.
 * - T01. findByKey 가 original/timeSpent/remaining 3컬럼을 반환한다.
 * - T02a~c. applyEstimatePatch 3-state (Set/Clear/Unchanged) 및 version 증가 확인.
 * - T03. recomputeTimeSpentWithDecrement — worklogs 합 재집계 + remaining 자동 차감 + version 불변.
 * - T04. remaining NULL 일 때 decrement 후에도 NULL 유지.
 * - T05. recomputeTimeSpentSetRemaining — remaining 직접 override.
 * - T06. recomputeTimeSpent — time_spent 만 재집계, remaining 미변경, version 불변.
 * - T07a~c. 소프트 삭제된 이슈에 recompute* 호출 시 예외 발생 (DELETED_AT IS NULL 필터 검증).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueEstimateRepositoryIntegrationTest : IssueTestcontainersBase() {
    /**
     * V003 seed 에서 task 타입 id 를 조회한다.
     * IssueTypeId 는 value class 라 lateinit 불가 — var + null 허용.
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

    @Suppress("MaxLineLength")
    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다 — resolveTaskTypeId 실행 확인" }

    /**
     * 기본 테스트 이슈를 DB 에 삽입하고 반환한다.
     * cleanIssues(@BeforeEach) 이후 호출을 전제로, key 는 항상 TPRJ-1.
     */
    private fun insertTestIssue(
        originalEstimate: Int? = null,
        remaining: Int? = null,
    ): Issue {
        val key = IssueKey.of("TPRJ", 1L)
        return repository.insert(
            Issue
                .create(
                    id = IssueId(UUID.randomUUID()),
                    key = key,
                    projectId = testProjectId,
                    typeId = requireTaskTypeId(),
                    summary = "추정 테스트 이슈",
                    reporterId = ActorId(UUID.randomUUID()),
                    currentStateKey = "open",
                ).copy(
                    originalEstimateSeconds = originalEstimate,
                    remainingEstimateSeconds = remaining,
                ),
        )
    }

    /**
     * worklogs 행을 직접 삽입한다 (WorklogRepository 없이 JDBC raw INSERT).
     * deleted_at IS NULL = 활성, NOT NULL = 삭제됨.
     */
    private fun insertWorklog(
        issueId: UUID,
        timeSpentSeconds: Int,
        deletedAt: OffsetDateTime? = null,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO worklogs (id, issue_id, author_id, time_spent_seconds, started_at, deleted_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, UUID.randomUUID())
                stmt.setObject(2, issueId)
                stmt.setObject(3, UUID.randomUUID())
                stmt.setInt(4, timeSpentSeconds)
                stmt.setObject(5, OffsetDateTime.now(ZoneOffset.UTC))
                stmt.setObject(6, deletedAt)
                stmt.executeUpdate()
            }
        }
    }

    // ── T01. findByKey — 추정 3컬럼 읽기 매핑 ───────────────────────────────────

    /**
     * Given  original=3600, timeSpent=0(기본), remaining=1800 으로 삽입된 이슈
     * When   findByKey 로 조회
     * Then   3컬럼이 도메인 모델에 올바르게 매핑된다.
     */
    @Test
    @Order(1)
    fun `T01 - findByKey - 추정 3컬럼이 Issue 도메인에 올바르게 매핑된다`() {
        val inserted = insertTestIssue(originalEstimate = 3600, remaining = 1800)

        val found = requireNotNull(repository.findByKey(inserted.key)) { "findByKey 결과가 null" }

        assertThat(found.originalEstimateSeconds).isEqualTo(3600)
        assertThat(found.timeSpentSeconds).isEqualTo(0)
        assertThat(found.remainingEstimateSeconds).isEqualTo(1800)
    }

    // ── T02a. EstimatePatch.Set — 값 지정 후 version 증가 ────────────────────────

    /**
     * Given  이슈 (original=null, remaining=null)
     * When   updateFields 에 EstimatePatch.Set 으로 두 필드를 지정
     * Then   반환값 1, 값이 DB 에 영속되고 version 이 +1 된다.
     */
    @Test
    @Order(10)
    fun `T02a - updateFields - EstimatePatch Set 시 값이 영속되고 version 이 증가한다`() {
        val inserted = insertTestIssue()

        val updated =
            repository.updateFields(
                key = inserted.key,
                patch =
                    IssueFieldPatch(
                        originalEstimate = EstimatePatch.Set(7200),
                        remainingEstimate = EstimatePatch.Set(3600),
                    ),
                expectedVersion = 1L,
            )

        assertThat(updated).isEqualTo(1)
        val found = requireNotNull(repository.findByKey(inserted.key)) { "T02a — findByKey null" }
        assertThat(found.originalEstimateSeconds).isEqualTo(7200)
        assertThat(found.remainingEstimateSeconds).isEqualTo(3600)
        assertThat(found.version).isEqualTo(2L)
    }

    // ── T02b. EstimatePatch.Clear — null 클리어 후 version 증가 ─────────────────

    /**
     * Given  이슈 (original=7200, remaining=3600)
     * When   updateFields 에 EstimatePatch.Clear 로 두 필드를 클리어
     * Then   두 필드가 null 로 되고 version 이 +1 된다.
     */
    @Test
    @Order(11)
    fun `T02b - updateFields - EstimatePatch Clear 시 null 로 클리어되고 version 이 증가한다`() {
        val inserted = insertTestIssue(originalEstimate = 7200, remaining = 3600)

        val updated =
            repository.updateFields(
                key = inserted.key,
                patch =
                    IssueFieldPatch(
                        originalEstimate = EstimatePatch.Clear,
                        remainingEstimate = EstimatePatch.Clear,
                    ),
                expectedVersion = 1L,
            )

        assertThat(updated).isEqualTo(1)
        val found = requireNotNull(repository.findByKey(inserted.key)) { "T02b — findByKey null" }
        assertThat(found.originalEstimateSeconds).isNull()
        assertThat(found.remainingEstimateSeconds).isNull()
        assertThat(found.version).isEqualTo(2L)
    }

    // ── T02c. EstimatePatch.Unchanged — 기존 값 유지, version 증가 ────────────

    /**
     * Given  이슈 (original=3600, remaining=1800)
     * When   updateFields 에 EstimatePatch.Unchanged(기본) 로 summary 만 변경
     * Then   추정 필드는 변경되지 않고, version 은 summary 변경으로 +1 된다.
     */
    @Test
    @Order(12)
    fun `T02c - updateFields - EstimatePatch Unchanged 시 기존 추정 값이 변경되지 않는다`() {
        val inserted = insertTestIssue(originalEstimate = 3600, remaining = 1800)

        repository.updateFields(
            key = inserted.key,
            patch = IssueFieldPatch(summary = "변경된 제목"),
            expectedVersion = 1L,
        )

        val found = requireNotNull(repository.findByKey(inserted.key)) { "T02c — findByKey null" }
        assertThat(found.originalEstimateSeconds).isEqualTo(3600)
        assertThat(found.remainingEstimateSeconds).isEqualTo(1800)
        assertThat(found.version).isEqualTo(2L)
    }

    // ── T03. recomputeTimeSpentWithDecrement — 합산 + remaining 차감 + version 불변 ─

    /**
     * Given  이슈 (remaining=5000), 활성 worklogs 3건 (300+400+500=1200s), 삭제 worklog 1건 (999s, 제외)
     * When   recomputeTimeSpentWithDecrement(decrementSeconds=300)
     * Then   timeSpent=1200, remaining=max(0,5000-300)=4700, version 불변(no-bump).
     */
    @Test
    @Order(20)
    fun `T03 - recomputeTimeSpentWithDecrement - 활성 워크로그 합산 + remaining 차감 + version 불변`() {
        val inserted = insertTestIssue(remaining = 5000)
        insertWorklog(inserted.id.value, 300)
        insertWorklog(inserted.id.value, 400)
        insertWorklog(inserted.id.value, 500)
        // 소프트 삭제 worklog — SUM 에서 제외돼야 한다 (deleted_at IS NULL 필터)
        insertWorklog(inserted.id.value, 999, deletedAt = OffsetDateTime.now(ZoneOffset.UTC))

        val result =
            repository.recomputeTimeSpentWithDecrement(
                issueId = inserted.id.value,
                decrementSeconds = 300,
            )

        assertThat(result.timeSpent).isEqualTo(1200)
        assertThat(result.remaining).isEqualTo(4700)

        val found = requireNotNull(repository.findByKey(inserted.key)) { "T03 — findByKey null" }
        assertThat(found.timeSpentSeconds).isEqualTo(1200)
        assertThat(found.remainingEstimateSeconds).isEqualTo(4700)
        // no-bump: version 불변
        assertThat(found.version).isEqualTo(1L)
    }

    // ── T04. remaining NULL 이면 decrement 후에도 NULL 유지 ──────────────────────

    /**
     * Given  이슈 (remaining=null), 활성 worklog 1건 (600s)
     * When   recomputeTimeSpentWithDecrement(decrementSeconds=100)
     * Then   timeSpent=600, remaining=null (NULL 유지 — PG GREATEST(0,NULL)=0 함정 회피).
     */
    @Test
    @Order(21)
    fun `T04 - recomputeTimeSpentWithDecrement - remaining NULL 이면 decrement 후에도 NULL 유지`() {
        val inserted = insertTestIssue(remaining = null)
        insertWorklog(inserted.id.value, 600)

        val result =
            repository.recomputeTimeSpentWithDecrement(
                issueId = inserted.id.value,
                decrementSeconds = 100,
            )

        assertThat(result.timeSpent).isEqualTo(600)
        assertThat(result.remaining).isNull()

        val found = requireNotNull(repository.findByKey(inserted.key)) { "T04 — findByKey null" }
        assertThat(found.remainingEstimateSeconds).isNull()
    }

    // ── T05. recomputeTimeSpentSetRemaining — remaining 직접 override ────────────

    /**
     * Given  이슈 (remaining=5000), 활성 worklogs 2건 (200+300=500s)
     * When   recomputeTimeSpentSetRemaining(newRemaining=9999)
     * Then   timeSpent=500, remaining=9999 (override), version 불변.
     */
    @Test
    @Order(30)
    fun `T05 - recomputeTimeSpentSetRemaining - remaining 직접 지정 + timeSpent 재집계 + version 불변`() {
        val inserted = insertTestIssue(remaining = 5000)
        insertWorklog(inserted.id.value, 200)
        insertWorklog(inserted.id.value, 300)

        val result =
            repository.recomputeTimeSpentSetRemaining(
                issueId = inserted.id.value,
                newRemaining = 9999,
            )

        assertThat(result.timeSpent).isEqualTo(500)
        assertThat(result.remaining).isEqualTo(9999)

        val found = requireNotNull(repository.findByKey(inserted.key)) { "T05 — findByKey null" }
        assertThat(found.timeSpentSeconds).isEqualTo(500)
        assertThat(found.remainingEstimateSeconds).isEqualTo(9999)
        // no-bump
        assertThat(found.version).isEqualTo(1L)
    }

    // ── T06. recomputeTimeSpent — time_spent 만 재집계, remaining 미변경, version 불변 ─

    /**
     * Given  이슈 (remaining=2000), 활성 worklogs 2건 (100+200=300s)
     * When   recomputeTimeSpent
     * Then   timeSpent=300, remaining=2000 (미변경), version 불변.
     */
    @Test
    @Order(40)
    fun `T06 - recomputeTimeSpent - timeSpent 만 재집계, remaining 불변, version 불변`() {
        val inserted = insertTestIssue(remaining = 2000)
        insertWorklog(inserted.id.value, 100)
        insertWorklog(inserted.id.value, 200)

        val result = repository.recomputeTimeSpent(inserted.id.value)

        assertThat(result.timeSpent).isEqualTo(300)
        assertThat(result.remaining).isEqualTo(2000)

        val found = requireNotNull(repository.findByKey(inserted.key)) { "T06 — findByKey null" }
        assertThat(found.timeSpentSeconds).isEqualTo(300)
        assertThat(found.remainingEstimateSeconds).isEqualTo(2000)
        // no-bump
        assertThat(found.version).isEqualTo(1L)
    }

    // ── T07a~c. 소프트 삭제된 이슈에 recompute* 호출 시 예외 (DELETED_AT IS NULL 필터 검증) ─

    /**
     * 소프트 삭제 헬퍼 — issues.deleted_at 를 JDBC 로 직접 설정한다.
     *
     * IssueRepository.softDelete(key) 를 사용하지 않는 이유: 이 테스트는 recompute* 의
     * DELETED_AT IS NULL 필터 단독 검증이 목적이다. softDelete 가 side-effect 없이
     * deleted_at 만 설정함을 가정하고 JDBC 로 직접 수행해 격리성을 높인다.
     */
    private fun softDeleteIssue(issueId: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "UPDATE issues SET deleted_at = NOW() WHERE id = ?",
            ).use { stmt ->
                stmt.setObject(1, issueId)
                stmt.executeUpdate()
            }
        }
    }

    /**
     * T07a — 소프트 삭제된 이슈에 recomputeTimeSpentWithDecrement 호출 시 예외.
     *
     * Given  이슈를 삽입하고 소프트 삭제(deleted_at 설정)
     * When   recomputeTimeSpentWithDecrement 호출
     * Then   IllegalStateException 발생 (0행 갱신 → RETURNING null → error()).
     *
     * GREEN 조건: recompute* WHERE 에 DELETED_AT.isNull 이 추가되면 UPDATE 대상이 0행 →
     * RETURNING fetchOne() = null → IllegalStateException 발생.
     * 현재(RED): 필터 없어서 삭제 이슈도 갱신 → RETURNING 행 반환 → 예외 미발생 → 테스트 실패.
     */
    @Test
    @Order(50)
    fun `T07a - 소프트 삭제된 이슈에 recomputeTimeSpentWithDecrement 호출 시 예외`() {
        val inserted = insertTestIssue(remaining = 1000)
        softDeleteIssue(inserted.id.value)

        assertThatThrownBy {
            repository.recomputeTimeSpentWithDecrement(
                issueId = inserted.id.value,
                decrementSeconds = 100,
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    /**
     * T07b — 소프트 삭제된 이슈에 recomputeTimeSpentSetRemaining 호출 시 예외.
     *
     * Given  이슈를 삽입하고 소프트 삭제
     * When   recomputeTimeSpentSetRemaining 호출
     * Then   IllegalStateException 발생.
     */
    @Test
    @Order(51)
    fun `T07b - 소프트 삭제된 이슈에 recomputeTimeSpentSetRemaining 호출 시 예외`() {
        val inserted = insertTestIssue(remaining = 1000)
        softDeleteIssue(inserted.id.value)

        assertThatThrownBy {
            repository.recomputeTimeSpentSetRemaining(
                issueId = inserted.id.value,
                newRemaining = 500,
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    /**
     * T07c — 소프트 삭제된 이슈에 recomputeTimeSpent 호출 시 예외.
     *
     * Given  이슈를 삽입하고 소프트 삭제
     * When   recomputeTimeSpent 호출
     * Then   IllegalStateException 발생.
     */
    @Test
    @Order(52)
    fun `T07c - 소프트 삭제된 이슈에 recomputeTimeSpent 호출 시 예외`() {
        val inserted = insertTestIssue(remaining = null)
        softDeleteIssue(inserted.id.value)

        assertThatThrownBy {
            repository.recomputeTimeSpent(inserted.id.value)
        }.isInstanceOf(IllegalStateException::class.java)
    }
}

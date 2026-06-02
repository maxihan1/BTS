// BulkOperationRepository 통합 테스트 — insert/find/CAS(claimForRun, markCompleted)/멱등/TTL 조회/payload round-trip/Clock

package com.bts.issue.bulk.repository

import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationItem
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationStatus
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * BulkOperationRepository 통합 테스트.
 *
 * Testcontainers singleton container 패턴 — [IssueTestcontainersBase] 상속.
 * JVM 당 컨테이너 1개, Ryuk 이 JVM 종료 시 자동 정리.
 *
 * 커버 항목.
 * - insert: 작업 + 항목 N건 배치 insert
 * - findById / findItemsByOperationId: 별쿼리 2개 조회 (cartesian product 방지)
 * - claimForRun CAS: PENDING→RUNNING 선점, 중복 선점 false 반환
 * - updateItemResult: PENDING 가드 멱등 (이미 종료 항목 갱신 안 함)
 * - recomputeAndPersistCounts: 항목 집계 후 persist
 * - markCompleted CAS: RUNNING→COMPLETED 1회 보장
 * - findCompletedBefore: TTL cleanup 조회
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BulkOperationRepositoryTest : IssueTestcontainersBase() {
    private lateinit var bulkRepo: BulkOperationRepository

    @BeforeEach
    fun initRepo() {
        bulkRepo = BulkOperationRepository(dsl)
    }

    // helper — ObjectMapper 는 Spring context 없이 직접 생성하여 사용
    private val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
        .registerModule(com.fasterxml.jackson.module.kotlin.KotlinModule.Builder().build())

    @BeforeEach
    fun cleanBulkTables() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM bulk_operation_items")
                stmt.execute("DELETE FROM bulk_operations")
            }
        }
    }

    // ── helper ─────────────────────────────────────────────────────────────────

    private fun makeOperation(
        id: BulkOperationId = BulkOperationId(UUID.randomUUID()),
        keys: List<String> = listOf("TPRJ-1", "TPRJ-2"),
        payload: BulkOperationPayload = BulkOperationPayload.Edit(priority = 3, impact = null),
    ): BulkOperation =
        BulkOperation.create(
            id = id,
            actorId = UUID.randomUUID(),
            type = BulkOperationType.BULK_EDIT,
            items = keys.map { BulkOperationItem(issueKey = IssueKey(it), status = ItemStatus.PENDING) },
            payload = payload,
        )

    // ── insert + find (별쿼리 2개) ──────────────────────────────────────────────

    @Test
    fun `insert 후 findById 로 작업 조회 가능`() {
        val op = makeOperation()
        bulkRepo.insert(op)

        val found = bulkRepo.findById(op.id)
        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(op.id)
        assertThat(found.status).isEqualTo(BulkOperationStatus.PENDING)
        assertThat(found.totalCount).isEqualTo(2)
    }

    @Test
    fun `insert 후 findItemsByOperationId 로 항목 목록 조회 가능`() {
        val op = makeOperation(keys = listOf("AB-1", "AB-2", "AB-3"))
        bulkRepo.insert(op)

        val items = bulkRepo.findItemsByOperationId(op.id)
        assertThat(items).hasSize(3)
        assertThat(items.map { it.issueKey.value }).containsExactlyInAnyOrder("AB-1", "AB-2", "AB-3")
        assertThat(items).allMatch { it.status == ItemStatus.PENDING }
    }

    @Test
    fun `존재하지 않는 id 로 findById 시 null 반환`() {
        val result = bulkRepo.findById(BulkOperationId(UUID.randomUUID()))
        assertThat(result).isNull()
    }

    // ── claimForRun CAS ────────────────────────────────────────────────────────

    @Test
    fun `claimForRun 은 PENDING 작업을 RUNNING 으로 전환하고 true 반환`() {
        val op = makeOperation()
        bulkRepo.insert(op)

        val claimed = bulkRepo.claimForRun(op.id)
        assertThat(claimed).isTrue()

        val found = bulkRepo.findById(op.id)
        assertThat(found!!.status).isEqualTo(BulkOperationStatus.RUNNING)
    }

    @Test
    fun `claimForRun 은 이미 RUNNING 인 작업에 대해 false 반환 — 타 워커 선점 시뮬레이션`() {
        val op = makeOperation()
        bulkRepo.insert(op)

        val first = bulkRepo.claimForRun(op.id)
        assertThat(first).isTrue()

        // 동일 작업을 두 번째 워커가 선점 시도 → false
        val second = bulkRepo.claimForRun(op.id)
        assertThat(second).isFalse()
    }

    // ── updateItemResult 멱등 ──────────────────────────────────────────────────

    @Test
    fun `updateItemResult 는 PENDING 항목을 SUCCEEDED 로 갱신하고 affected rows 1 반환`() {
        val op = makeOperation(keys = listOf("PRJ-10"))
        bulkRepo.insert(op)

        val affected =
            bulkRepo.updateItemResult(
                operationId = op.id,
                issueKey = IssueKey("PRJ-10"),
                status = ItemStatus.SUCCEEDED,
                reasonCode = null,
            )
        assertThat(affected).isEqualTo(1)

        val items = bulkRepo.findItemsByOperationId(op.id)
        assertThat(items.first { it.issueKey.value == "PRJ-10" }.status).isEqualTo(ItemStatus.SUCCEEDED)
    }

    @Test
    fun `updateItemResult 는 이미 SUCCEEDED 인 항목 재갱신 시 0 반환 — 멱등 보장`() {
        val op = makeOperation(keys = listOf("PRJ-20"))
        bulkRepo.insert(op)

        bulkRepo.updateItemResult(op.id, IssueKey("PRJ-20"), ItemStatus.SUCCEEDED, null)

        // 동일 항목 재갱신 시도 — PENDING 가드로 0 rows 반환해야 함
        val affected =
            bulkRepo.updateItemResult(op.id, IssueKey("PRJ-20"), ItemStatus.FAILED, FailureReasonCode.NOT_FOUND)
        assertThat(affected).isEqualTo(0)

        // 상태가 SUCCEEDED 그대로 유지되어야 함
        val items = bulkRepo.findItemsByOperationId(op.id)
        assertThat(items.first { it.issueKey.value == "PRJ-20" }.status).isEqualTo(ItemStatus.SUCCEEDED)
    }

    @Test
    fun `updateItemResult 는 PENDING 항목을 FAILED 로 갱신 시 failure_reason 저장`() {
        val op = makeOperation(keys = listOf("PRJ-30"))
        bulkRepo.insert(op)

        val affected =
            bulkRepo.updateItemResult(op.id, IssueKey("PRJ-30"), ItemStatus.FAILED, FailureReasonCode.FORBIDDEN)
        assertThat(affected).isEqualTo(1)

        val item = bulkRepo.findItemsByOperationId(op.id).first()
        assertThat(item.status).isEqualTo(ItemStatus.FAILED)
        assertThat(item.failureReasonCode).isEqualTo(FailureReasonCode.FORBIDDEN)
    }

    // NOTE: PRJ prefix 는 최소 2자 (^[A-Z][A-Z0-9]{1,9}-) 규칙 준수. 단일 문자 prefix 사용 불가

    // ── recomputeAndPersistCounts ──────────────────────────────────────────────

    @Test
    fun `recomputeAndPersistCounts 는 항목 집계 결과를 bulk_operations 에 저장`() {
        val op = makeOperation(keys = listOf("CA-1", "CA-2", "CA-3"))
        bulkRepo.insert(op)
        bulkRepo.claimForRun(op.id)

        bulkRepo.updateItemResult(op.id, IssueKey("CA-1"), ItemStatus.SUCCEEDED, null)
        bulkRepo.updateItemResult(op.id, IssueKey("CA-2"), ItemStatus.FAILED, FailureReasonCode.NOT_FOUND)
        // CA-3 은 PENDING 유지

        bulkRepo.recomputeAndPersistCounts(op.id)

        val updated = bulkRepo.findById(op.id)
        assertThat(updated!!.succeededCount).isEqualTo(1)
        assertThat(updated.failedCount).isEqualTo(1)
        assertThat(updated.processedCount).isEqualTo(2)
    }

    @Test
    fun `recomputeAndPersistCounts 멱등 — 같은 상태로 두 번 호출해도 결과 동일`() {
        val op = makeOperation(keys = listOf("DA-1", "DA-2"))
        bulkRepo.insert(op)
        bulkRepo.updateItemResult(op.id, IssueKey("DA-1"), ItemStatus.SUCCEEDED, null)

        bulkRepo.recomputeAndPersistCounts(op.id)
        bulkRepo.recomputeAndPersistCounts(op.id) // 두 번째 호출

        val found = bulkRepo.findById(op.id)
        assertThat(found!!.succeededCount).isEqualTo(1)
        assertThat(found.processedCount).isEqualTo(1)
    }

    // ── markCompleted CAS ──────────────────────────────────────────────────────

    @Test
    fun `markCompleted 는 RUNNING 작업을 COMPLETED 로 전환하고 true 반환`() {
        val op = makeOperation()
        bulkRepo.insert(op)
        bulkRepo.claimForRun(op.id)

        val completed = bulkRepo.markCompleted(op.id)
        assertThat(completed).isTrue()

        val found = bulkRepo.findById(op.id)
        assertThat(found!!.status).isEqualTo(BulkOperationStatus.COMPLETED)
    }

    @Test
    fun `markCompleted 는 PENDING 작업에 대해 false 반환 — RUNNING 아님`() {
        val op = makeOperation()
        bulkRepo.insert(op)

        val result = bulkRepo.markCompleted(op.id)
        assertThat(result).isFalse()

        // 상태 변경 없음
        val found = bulkRepo.findById(op.id)
        assertThat(found!!.status).isEqualTo(BulkOperationStatus.PENDING)
    }

    @Test
    fun `markCompleted 는 이미 COMPLETED 인 작업에 대해 false 반환 — 1회 보장`() {
        val op = makeOperation()
        bulkRepo.insert(op)
        bulkRepo.claimForRun(op.id)
        bulkRepo.markCompleted(op.id)

        val second = bulkRepo.markCompleted(op.id)
        assertThat(second).isFalse()
    }

    // ── findCompletedBefore TTL ────────────────────────────────────────────────

    @Test
    fun `findCompletedBefore 는 지정 시각 이전에 completed_at 이 설정된 작업을 반환`() {
        val op = makeOperation()
        bulkRepo.insert(op)
        bulkRepo.claimForRun(op.id)
        bulkRepo.markCompleted(op.id)

        // 방금 완료됐으므로 now+1s 기준으로 조회하면 포함
        val result = bulkRepo.findCompletedBefore(Instant.now().plusSeconds(1))
        assertThat(result.map { it.id }).contains(op.id)
    }

    // ── payload round-trip ─────────────────────────────────────────────────

    @Test
    fun `insert 후 findById 에서 BULK_EDIT payload 가 동일하게 복원된다`() {
        val payload = BulkOperationPayload.Edit(priority = 2, impact = 1)
        val op = makeOperation(payload = payload)
        bulkRepo = BulkOperationRepository(dsl, objectMapper)
        bulkRepo.insert(op)

        val found = bulkRepo.findById(op.id)
        assertThat(found).isNotNull
        assertThat(found!!.payload).isEqualTo(BulkOperationPayload.Edit(priority = 2, impact = 1))
    }

    @Test
    fun `insert 후 findById 에서 BULK_TRANSITION payload 가 동일하게 복원된다`() {
        val payload = BulkOperationPayload.Transition(toStateKey = "DONE")
        val op =
            BulkOperation.create(
                id = BulkOperationId(UUID.randomUUID()),
                actorId = UUID.randomUUID(),
                type = BulkOperationType.BULK_TRANSITION,
                items = listOf(BulkOperationItem(issueKey = IssueKey("TPRJ-1"), status = ItemStatus.PENDING)),
                payload = payload,
            )
        bulkRepo = BulkOperationRepository(dsl, objectMapper)
        bulkRepo.insert(op)

        val found = bulkRepo.findById(op.id)
        assertThat(found).isNotNull
        assertThat(found!!.payload).isEqualTo(BulkOperationPayload.Transition(toStateKey = "DONE"))
    }

    // ── Clock 주입 ─────────────────────────────────────────────────────────

    @Test
    fun `claimForRun 은 Clock fixed 로 주입된 시각을 started_at 에 기록한다`() {
        val fixedInstant = Instant.parse("2026-06-02T12:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val repoWithClock = BulkOperationRepository(dsl, objectMapper, fixedClock)

        val op = makeOperation()
        bulkRepo.insert(op)
        repoWithClock.claimForRun(op.id)

        // started_at 이 fixedInstant 와 동일한지 DB에서 직접 확인
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT started_at FROM bulk_operations WHERE id = ?").use { ps ->
                ps.setObject(1, op.id.value)
                val rs = ps.executeQuery()
                assertThat(rs.next()).isTrue()
                val startedAt = rs.getTimestamp(1)
                assertThat(startedAt).isNotNull
                assertThat(startedAt.toInstant()).isEqualTo(fixedInstant)
            }
        }
    }

    @Test
    fun `markCompleted 는 Clock fixed 로 주입된 시각을 completed_at 에 기록한다`() {
        val fixedInstant = Instant.parse("2026-06-02T18:00:00Z")
        val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)
        val repoWithClock = BulkOperationRepository(dsl, objectMapper, fixedClock)

        val op = makeOperation()
        bulkRepo.insert(op)
        bulkRepo.claimForRun(op.id)
        repoWithClock.markCompleted(op.id)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT completed_at FROM bulk_operations WHERE id = ?").use { ps ->
                ps.setObject(1, op.id.value)
                val rs = ps.executeQuery()
                assertThat(rs.next()).isTrue()
                val completedAt = rs.getTimestamp(1)
                assertThat(completedAt).isNotNull
                assertThat(completedAt.toInstant()).isEqualTo(fixedInstant)
            }
        }
    }

    @Test
    fun `findCompletedBefore 는 미래 시각 기준 조회 시 PENDING 작업은 포함 안 함`() {
        val op = makeOperation()
        bulkRepo.insert(op) // PENDING — completed_at = NULL

        val result = bulkRepo.findCompletedBefore(Instant.now().plusSeconds(3600))
        val ids: List<BulkOperationId> = result.map { it.id }
        assertThat(ids).doesNotContain(op.id)
    }
}

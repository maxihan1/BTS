// BulkOperation Aggregate Root 단위 테스트 — 상태 전이, 카운트 집계 멱등, markItem, isTerminal

package com.bts.issue.bulk.domain

import com.bts.issue.domain.IssueKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [BulkOperation] Aggregate Root 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - transition_pending_to_running — PENDING 상태에서 start() 호출 시 RUNNING 으로 전이한다.
 * - transition_running_to_completed — RUNNING 상태에서 complete() 호출 시 COMPLETED 로 전이한다.
 * - transition_pending_to_failed — PENDING 상태에서 fail() 호출 시 FAILED 로 전이한다.
 * - reject_start_when_not_pending — PENDING 이 아닌 상태에서 start() 호출 시 예외를 던진다.
 * - reject_complete_when_not_running — RUNNING 이 아닌 상태에서 complete() 호출 시 예외를 던진다.
 * - reject_fail_when_terminal — COMPLETED/FAILED 상태에서 fail() 호출 시 예외를 던진다.
 * - recomputeCounts_aggregates_item_statuses — 항목 상태에 따라 processed/succeeded/failed 를 재계산한다.
 * - recomputeCounts_is_idempotent — 같은 항목 집합으로 2회 호출해도 동일 결과를 반환한다.
 * - recomputeCounts_counts_only_non_pending — PENDING 항목은 processed 에 포함되지 않는다.
 * - markItem_updates_item_to_succeeded — markItem 으로 SUCCEEDED 로 변경하면 해당 항목 상태가 갱신된다.
 * - markItem_updates_item_to_failed_with_reason — markItem 으로 FAILED 로 변경하면 reasonCode 가 저장된다.
 * - markItem_rejects_unknown_key — 존재하지 않는 IssueKey 로 markItem 호출 시 예외를 던진다.
 * - isTerminal_returns_true_for_succeeded — SUCCEEDED 항목은 isTerminal 이 true 다.
 * - isTerminal_returns_true_for_failed — FAILED 항목은 isTerminal 이 true 다.
 * - isTerminal_returns_false_for_pending — PENDING 항목은 isTerminal 이 false 다.
 */
class BulkOperationTest {

    private val operationId = BulkOperationId(UUID.randomUUID())
    private val actorId = UUID.randomUUID()
    private val key1 = IssueKey.of("PROJ", 1L)
    private val key2 = IssueKey.of("PROJ", 2L)
    private val key3 = IssueKey.of("PROJ", 3L)

    private fun makeItems(vararg keys: IssueKey): List<BulkOperationItem> =
        keys.map { BulkOperationItem(issueKey = it, status = ItemStatus.PENDING) }

    private fun pendingOp(vararg keys: IssueKey = arrayOf(key1, key2, key3)): BulkOperation =
        BulkOperation.create(
            id = operationId,
            actorId = actorId,
            type = BulkOperationType.BULK_EDIT,
            items = makeItems(*keys),
        )

    // ── 상태 전이 ──────────────────────────────────────────────────────────

    @Test
    fun `transition_pending_to_running — PENDING 상태에서 start() 호출 시 RUNNING 으로 전이한다`() {
        val op = pendingOp()
        val running = op.start()
        assertThat(running.status).isEqualTo(BulkOperationStatus.RUNNING)
    }

    @Test
    fun `transition_running_to_completed — RUNNING 상태에서 complete() 호출 시 COMPLETED 로 전이한다`() {
        val completed = pendingOp().start().complete()
        assertThat(completed.status).isEqualTo(BulkOperationStatus.COMPLETED)
    }

    @Test
    fun `transition_pending_to_failed — PENDING 상태에서 fail() 호출 시 FAILED 로 전이한다`() {
        val failed = pendingOp().fail()
        assertThat(failed.status).isEqualTo(BulkOperationStatus.FAILED)
    }

    @Test
    fun `reject_start_when_not_pending — PENDING 이 아닌 상태에서 start() 호출 시 예외를 던진다`() {
        val running = pendingOp().start()
        assertThatThrownBy { running.start() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `reject_complete_when_not_running — RUNNING 이 아닌 상태에서 complete() 호출 시 예외를 던진다`() {
        val pending = pendingOp()
        assertThatThrownBy { pending.complete() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `reject_fail_when_terminal — COMPLETED 또는 FAILED 상태에서 fail() 호출 시 예외를 던진다`() {
        val completed = pendingOp().start().complete()
        assertThatThrownBy { completed.fail() }
            .isInstanceOf(IllegalStateException::class.java)

        val failed = pendingOp().fail()
        assertThatThrownBy { failed.fail() }
            .isInstanceOf(IllegalStateException::class.java)
    }

    // ── 카운트 집계 ────────────────────────────────────────────────────────

    @Test
    fun `recomputeCounts_aggregates_item_statuses — 항목 상태에 따라 processed succeeded failed 를 재계산한다`() {
        val items = listOf(
            BulkOperationItem(issueKey = key1, status = ItemStatus.SUCCEEDED),
            BulkOperationItem(issueKey = key2, status = ItemStatus.FAILED, failureReasonCode = FailureReasonCode.NOT_FOUND),
            BulkOperationItem(issueKey = key3, status = ItemStatus.PENDING),
        )
        val op = pendingOp()
        val result = op.recomputeCounts(items)

        assertThat(result.processedCount).isEqualTo(2)
        assertThat(result.succeededCount).isEqualTo(1)
        assertThat(result.failedCount).isEqualTo(1)
    }

    @Test
    fun `recomputeCounts_is_idempotent — 같은 항목 집합으로 2회 호출해도 동일 결과를 반환한다`() {
        val items = listOf(
            BulkOperationItem(issueKey = key1, status = ItemStatus.SUCCEEDED),
            BulkOperationItem(issueKey = key2, status = ItemStatus.FAILED, failureReasonCode = FailureReasonCode.FORBIDDEN),
        )
        val op = pendingOp()
        val first = op.recomputeCounts(items)
        val second = op.recomputeCounts(items)

        assertThat(first.processedCount).isEqualTo(second.processedCount)
        assertThat(first.succeededCount).isEqualTo(second.succeededCount)
        assertThat(first.failedCount).isEqualTo(second.failedCount)
    }

    @Test
    fun `recomputeCounts_counts_only_non_pending — PENDING 항목은 processed 에 포함되지 않는다`() {
        val items = listOf(
            BulkOperationItem(issueKey = key1, status = ItemStatus.PENDING),
            BulkOperationItem(issueKey = key2, status = ItemStatus.PENDING),
        )
        val op = pendingOp()
        val result = op.recomputeCounts(items)

        assertThat(result.processedCount).isEqualTo(0)
        assertThat(result.succeededCount).isEqualTo(0)
        assertThat(result.failedCount).isEqualTo(0)
    }

    // ── markItem ───────────────────────────────────────────────────────────

    @Test
    fun `markItem_updates_item_to_succeeded — markItem 으로 SUCCEEDED 로 변경하면 해당 항목 상태가 갱신된다`() {
        val op = pendingOp(key1, key2)
        val (updated, item) = op.markItem(key1, ItemStatus.SUCCEEDED)

        assertThat(item.status).isEqualTo(ItemStatus.SUCCEEDED)
        assertThat(item.failureReasonCode).isNull()
        assertThat(updated.items.first { it.issueKey == key1 }.status).isEqualTo(ItemStatus.SUCCEEDED)
    }

    @Test
    fun `markItem_updates_item_to_failed_with_reason — markItem 으로 FAILED 로 변경하면 reasonCode 가 저장된다`() {
        val op = pendingOp(key1)
        val (_, item) = op.markItem(key1, ItemStatus.FAILED, FailureReasonCode.TRANSITION_NOT_ALLOWED)

        assertThat(item.status).isEqualTo(ItemStatus.FAILED)
        assertThat(item.failureReasonCode).isEqualTo(FailureReasonCode.TRANSITION_NOT_ALLOWED)
    }

    @Test
    fun `markItem_rejects_unknown_key — 존재하지 않는 IssueKey 로 markItem 호출 시 예외를 던진다`() {
        val op = pendingOp(key1)
        val unknownKey = IssueKey.of("PROJ", 99L)

        assertThatThrownBy { op.markItem(unknownKey, ItemStatus.SUCCEEDED) }
            .isInstanceOf(NoSuchElementException::class.java)
    }

    // ── isTerminal ─────────────────────────────────────────────────────────

    @Test
    fun `isTerminal_returns_true_for_succeeded — SUCCEEDED 항목은 isTerminal 이 true 다`() {
        val item = BulkOperationItem(issueKey = key1, status = ItemStatus.SUCCEEDED)
        assertThat(BulkOperation.isTerminal(item)).isTrue()
    }

    @Test
    fun `isTerminal_returns_true_for_failed — FAILED 항목은 isTerminal 이 true 다`() {
        val item = BulkOperationItem(
            issueKey = key1,
            status = ItemStatus.FAILED,
            failureReasonCode = FailureReasonCode.NOT_FOUND,
        )
        assertThat(BulkOperation.isTerminal(item)).isTrue()
    }

    @Test
    fun `isTerminal_returns_false_for_pending — PENDING 항목은 isTerminal 이 false 다`() {
        val item = BulkOperationItem(issueKey = key1, status = ItemStatus.PENDING)
        assertThat(BulkOperation.isTerminal(item)).isFalse()
    }
}

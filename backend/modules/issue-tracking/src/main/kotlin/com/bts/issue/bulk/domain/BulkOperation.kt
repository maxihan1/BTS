// BulkOperation Aggregate Root — 일괄 작업 전체 상태 머신, 카운트 집계, 항목 상태 갱신

package com.bts.issue.bulk.domain

import com.bts.issue.domain.IssueKey
import java.time.Instant
import java.util.UUID

/** 일괄 작업 한 번에 처리할 수 있는 이슈의 최대 개수. */
const val BULK_OPERATION_MAX_SIZE = 1000

/** 일괄 작업 청크(chunk) 처리 단위. */
const val BULK_OPERATION_CHUNK_SIZE = 50

/**
 * 일괄 작업의 내부 식별자 VO.
 *
 * @property value 내부 UUID.
 */
@JvmInline
value class BulkOperationId(val value: UUID)

/**
 * 일괄 작업(Bulk Operation) Aggregate Root.
 *
 * 여러 이슈에 동시에 적용하는 작업의 전체 수명주기를 관리한다.
 * 직접 생성자 대신 [BulkOperation.create] factory 를 통해 불변식을 검증하고 인스턴스를 얻는다.
 *
 * 상태 머신.
 * - [BulkOperationStatus.PENDING] → [start] → [BulkOperationStatus.RUNNING]
 * - [BulkOperationStatus.RUNNING] → [complete] → [BulkOperationStatus.COMPLETED]
 * - [BulkOperationStatus.PENDING] 또는 [BulkOperationStatus.RUNNING] → [fail] → [BulkOperationStatus.FAILED]
 * - [BulkOperationStatus.COMPLETED], [BulkOperationStatus.FAILED] 는 종단 상태.
 *
 * invariant.
 * - [items] 는 생성 시 1개 이상, [BULK_OPERATION_MAX_SIZE] 이하.
 * - [processedCount] = succeededCount + failedCount (recomputeCounts 후 항상 성립).
 *
 * @property id 일괄 작업 내부 식별자.
 * @property actorId 작업을 요청한 사용자 UUID.
 * @property type 작업 유형.
 * @property status 현재 상태.
 * @property items 처리 대상 이슈 항목 목록.
 * @property totalCount 총 항목 수. 생성 후 불변.
 * @property processedCount 처리 완료(성공+실패) 항목 수. recomputeCounts 로 갱신.
 * @property succeededCount 성공 항목 수. recomputeCounts 로 갱신.
 * @property failedCount 실패 항목 수. recomputeCounts 로 갱신.
 * @property createdAt 생성 시각.
 * @property updatedAt 마지막 갱신 시각.
 */
data class BulkOperation(
    val id: BulkOperationId,
    val actorId: UUID,
    val type: BulkOperationType,
    val status: BulkOperationStatus,
    val items: List<BulkOperationItem>,
    val totalCount: Int,
    val processedCount: Int,
    val succeededCount: Int,
    val failedCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * 새 일괄 작업을 생성한다.
         *
         * @param id 내부 식별자.
         * @param actorId 작업 요청자 UUID.
         * @param type 작업 유형.
         * @param items 처리 대상 항목 목록. 1개 이상, [BULK_OPERATION_MAX_SIZE] 이하.
         * @return PENDING 상태의 새 [BulkOperation] 인스턴스.
         * @throws IllegalArgumentException 항목 수 불변식 위반 시.
         */
        fun create(
            id: BulkOperationId,
            actorId: UUID,
            type: BulkOperationType,
            items: List<BulkOperationItem>,
        ): BulkOperation {
            require(items.isNotEmpty()) { "items must not be empty" }
            require(items.size <= BULK_OPERATION_MAX_SIZE) {
                "items must not exceed $BULK_OPERATION_MAX_SIZE, but was ${items.size}"
            }
            val now = Instant.now()
            return BulkOperation(
                id = id,
                actorId = actorId,
                type = type,
                status = BulkOperationStatus.PENDING,
                items = items,
                totalCount = items.size,
                processedCount = 0,
                succeededCount = 0,
                failedCount = 0,
                createdAt = now,
                updatedAt = now,
            )
        }

        /**
         * 항목의 상태가 종단(terminal)인지 판정한다.
         *
         * 종단 상태([ItemStatus.SUCCEEDED] 또는 [ItemStatus.FAILED])이면 재처리를 건너뛰는
         * 멱등 스킵 판정에 사용한다.
         *
         * @param item 판정 대상 항목.
         * @return 종단 상태면 true.
         */
        fun isTerminal(item: BulkOperationItem): Boolean =
            item.status == ItemStatus.SUCCEEDED || item.status == ItemStatus.FAILED
    }

    /**
     * 작업을 PENDING → RUNNING 으로 전이한다.
     *
     * @return RUNNING 상태의 새 [BulkOperation] 인스턴스.
     * @throws IllegalStateException 현재 상태가 [BulkOperationStatus.PENDING] 이 아닐 때.
     */
    fun start(): BulkOperation {
        check(status == BulkOperationStatus.PENDING) {
            "start() requires PENDING status, but was $status"
        }
        return copy(status = BulkOperationStatus.RUNNING, updatedAt = Instant.now())
    }

    /**
     * 작업을 RUNNING → COMPLETED 로 전이한다.
     *
     * @return COMPLETED 상태의 새 [BulkOperation] 인스턴스.
     * @throws IllegalStateException 현재 상태가 [BulkOperationStatus.RUNNING] 이 아닐 때.
     */
    fun complete(): BulkOperation {
        check(status == BulkOperationStatus.RUNNING) {
            "complete() requires RUNNING status, but was $status"
        }
        return copy(status = BulkOperationStatus.COMPLETED, updatedAt = Instant.now())
    }

    /**
     * 작업을 FAILED 로 전이한다.
     *
     * PENDING 또는 RUNNING 상태에서만 호출 가능하다(인프라 오류 포함).
     * 종단 상태([BulkOperationStatus.COMPLETED], [BulkOperationStatus.FAILED])에서는 거부한다.
     *
     * @return FAILED 상태의 새 [BulkOperation] 인스턴스.
     * @throws IllegalStateException 종단 상태에서 호출 시.
     */
    fun fail(): BulkOperation {
        check(
            status == BulkOperationStatus.PENDING || status == BulkOperationStatus.RUNNING,
        ) {
            "fail() requires PENDING or RUNNING status, but was $status"
        }
        return copy(status = BulkOperationStatus.FAILED, updatedAt = Instant.now())
    }

    /**
     * 제공된 항목 목록의 상태를 집계하여 카운트를 재계산한다.
     *
     * 증분(increment) 방식이 아니라 전체 재집계 방식이므로 멱등하다 — 같은 항목 집합으로
     * 몇 번 호출해도 동일한 결과를 반환한다.
     *
     * @param currentItems 재집계할 항목 목록.
     * @return 카운트가 갱신된 새 [BulkOperation] 인스턴스.
     */
    fun recomputeCounts(currentItems: List<BulkOperationItem>): BulkOperation {
        val succeeded = currentItems.count { it.status == ItemStatus.SUCCEEDED }
        val failed = currentItems.count { it.status == ItemStatus.FAILED }
        return copy(
            succeededCount = succeeded,
            failedCount = failed,
            processedCount = succeeded + failed,
            updatedAt = Instant.now(),
        )
    }

    /**
     * 지정한 이슈 키에 해당하는 항목의 상태를 갱신한다.
     *
     * 불변 Aggregate 이므로 항목 목록을 교체한 새 인스턴스와 갱신된 항목을 함께 반환한다.
     *
     * @param issueKey 갱신 대상 이슈 키.
     * @param newStatus 설정할 새 항목 상태 ([ItemStatus.SUCCEEDED] 또는 [ItemStatus.FAILED]).
     * @param reasonCode 실패 사유 코드. [ItemStatus.FAILED] 일 때 필수.
     * @return (갱신된 BulkOperation, 갱신된 항목) 쌍.
     * @throws NoSuchElementException [issueKey] 에 해당하는 항목이 없을 때.
     * @throws IllegalArgumentException [BulkOperationItem] 불변식 위반 시 (FAILED + reasonCode=null 등).
     */
    fun markItem(
        issueKey: IssueKey,
        newStatus: ItemStatus,
        reasonCode: FailureReasonCode? = null,
    ): Pair<BulkOperation, BulkOperationItem> {
        val idx = items.indexOfFirst { it.issueKey == issueKey }
        if (idx < 0) throw NoSuchElementException("Item not found for issueKey=${issueKey.value}")
        val updatedItem = items[idx].copy(status = newStatus, failureReasonCode = reasonCode)
        val newItems = items.mapIndexed { i, item -> if (i == idx) updatedItem else item }
        return copy(items = newItems, updatedAt = Instant.now()) to updatedItem
    }
}

// BulkOperationRepository — bulk_operations/bulk_operation_items 테이블 jOOQ DSL 접근

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
import com.bts.issue.jooq.tables.records.BulkOperationsRecord
import com.bts.issue.jooq.tables.references.BULK_OPERATIONS
import com.bts.issue.jooq.tables.references.BULK_OPERATION_ITEMS
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 일괄 작업 Repository.
 *
 * jOOQ DSLContext 를 통해 bulk_operations / bulk_operation_items 테이블에 접근한다.
 * 모든 public 쓰기 메서드는 @Transactional 를 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * **CAS(Compare-And-Swap) 패턴** — [claimForRun], [markCompleted] 는 WHERE status=? 조건을
 * 포함한 UPDATE 의 affected rows(영향받은 행 수)로 단일 진입을 보장한다.
 * advisory lock/CAS 후 affected rows 로 판정하여 TOCTOU(Time-Of-Check-Time-Of-Use)
 * 경쟁조건을 방지한다 (learnings: advisory-lock-bigint-TOCTOU).
 *
 * **별쿼리 2개 조회** — 작업과 항목을 JOIN 으로 한 번에 조회하면 N개 항목 × 1 작업 조합으로
 * cartesian product 위험이 있다. [findById] + [findItemsByOperationId] 분리로 해소
 * (learnings: jOOQ-cartesian-product).
 */
@Repository
@Suppress("TooManyFunctions")
class BulkOperationRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 일괄 작업 1건과 항목 N건을 bulk_operations / bulk_operation_items 에 삽입한다.
     *
     * 항목은 [dsl.batch] 를 통해 배치 INSERT 한다.
     *
     * @param operation 삽입할 [BulkOperation]. status=PENDING 이 보장되어야 한다.
     */
    @Transactional
    fun insert(operation: BulkOperation) {
        log.debug(
            "Inserting BulkOperation id={} type={} items={}",
            operation.id.value,
            operation.type,
            operation.items.size,
        )

        dsl.insertInto(BULK_OPERATIONS)
            .set(BULK_OPERATIONS.ID, operation.id.value)
            .set(BULK_OPERATIONS.OPERATION_TYPE, operation.type.name)
            .set(BULK_OPERATIONS.STATUS, operation.status.name)
            .set(BULK_OPERATIONS.ACTOR_ID, operation.actorId)
            .set(BULK_OPERATIONS.PAYLOAD, operation.payload.toJsonb())
            .set(BULK_OPERATIONS.TOTAL_COUNT, operation.totalCount)
            .set(BULK_OPERATIONS.PROCESSED_COUNT, operation.processedCount)
            .set(BULK_OPERATIONS.SUCCEEDED_COUNT, operation.succeededCount)
            .set(BULK_OPERATIONS.FAILED_COUNT, operation.failedCount)
            .set(BULK_OPERATIONS.CREATED_AT, operation.createdAt.toOffsetDateTime())
            .execute()

        insertItemsBatch(operation)
    }

    /**
     * id 로 일괄 작업 단건을 조회한다. 항목은 포함하지 않는다.
     *
     * 항목은 [findItemsByOperationId] 로 별도 조회한다 (cartesian product 방지).
     *
     * @param id 조회할 작업 식별자.
     * @return 작업이 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findById(id: BulkOperationId): BulkOperation? =
        dsl.selectFrom(BULK_OPERATIONS)
            .where(BULK_OPERATIONS.ID.eq(id.value))
            .fetchOne()
            ?.toOperation()

    /**
     * 작업 id 에 속한 항목 목록을 조회한다.
     *
     * [findById] 와 분리된 별쿼리로 cartesian product 를 방지한다 (learnings: jOOQ-cartesian-product).
     *
     * @param operationId 부모 작업 식별자.
     * @return 항목 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findItemsByOperationId(operationId: BulkOperationId): List<BulkOperationItem> =
        dsl.selectFrom(BULK_OPERATION_ITEMS)
            .where(BULK_OPERATION_ITEMS.BULK_OPERATION_ID.eq(operationId.value))
            .fetch()
            .map { record ->
                BulkOperationItem(
                    issueKey = IssueKey(record.issueKey),
                    status = enumValueOf(record.status),
                    failureReasonCode = record.failureReason?.let { enumValueOf<FailureReasonCode>(it) },
                )
            }

    /**
     * 작업 1건의 현재 상태를 조회한다 (경량 조회 — id + status 만 읽음).
     *
     * claim-false 후 메시지 처리 방법을 결정하는 데 사용한다.
     * 종단 상태이면 delete, RUNNING 이면 재전달 대기, null 이면 poison 처리.
     *
     * @param id 조회할 작업 식별자.
     * @return 현재 상태. 작업이 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findStatus(id: BulkOperationId): BulkOperationStatus? =
        dsl.select(BULK_OPERATIONS.STATUS)
            .from(BULK_OPERATIONS)
            .where(BULK_OPERATIONS.ID.eq(id.value))
            .fetchOne()
            ?.let { record ->
                val statusStr = record.get(BULK_OPERATIONS.STATUS) ?: return@let null
                enumValueOf<BulkOperationStatus>(statusStr)
            }

    /**
     * PENDING 상태 작업 또는 stale RUNNING 작업을 RUNNING 으로 전환한다 (CAS).
     *
     * 단일 UPDATE affected rows 로 원자적 단일 진입을 보장한다 (TOCTOU 방지).
     *
     * ### 선점 조건
     * ```sql
     * WHERE id = ?
     *   AND (
     *     status = 'PENDING'
     *     OR (status = 'RUNNING' AND started_at < ?staleThreshold)
     *   )
     * ```
     * - PENDING → 신규 처리 선점.
     * - RUNNING + started_at < staleThreshold → 크래시 후 방치된 stale 작업 재청.
     *
     * ### stale threshold 산정 근거
     * - 최대 처리 예산: (1000/50) × 200ms = 4초.
     * - vt = 60초. threshold = vt × 5 = **300초** — 정상 처리 중(4초)을 뺏지 않도록 충분히 큰 값.
     * - 300초 경과 후에도 RUNNING 이면 크래시로 확정하여 재청 허용.
     *
     * **TOCTOU 방지** — 상태 조회 후 별도 UPDATE 방식은 경쟁조건 유발 (learnings: advisory-lock-bigint-TOCTOU).
     * affected rows 가 유일한 단일성 근거.
     *
     * @param id 전환할 작업 식별자.
     * @return 선점 성공이면 true, 타 워커 활성 선점이면 false.
     */
    @Transactional
    fun claimForRun(id: BulkOperationId): Boolean {
        log.debug("claimForRun id={}", id.value)
        val now = OffsetDateTime.now(clock)
        val staleThreshold = now.minusSeconds(STALE_RUNNING_THRESHOLD_SECONDS)
        val affected =
            dsl.update(BULK_OPERATIONS)
                .set(BULK_OPERATIONS.STATUS, BulkOperationStatus.RUNNING.name)
                .set(BULK_OPERATIONS.STARTED_AT, now)
                .where(BULK_OPERATIONS.ID.eq(id.value))
                .and(
                    BULK_OPERATIONS.STATUS.eq(BulkOperationStatus.PENDING.name)
                        .or(
                            BULK_OPERATIONS.STATUS.eq(BulkOperationStatus.RUNNING.name)
                                .and(BULK_OPERATIONS.STARTED_AT.lt(staleThreshold)),
                        ),
                )
                .execute()
        return affected == 1
    }

    /**
     * 항목 1건의 처리 결과를 기록한다 (PENDING 가드 — 멱등 보장).
     *
     * `WHERE bulk_operation_id=? AND issue_key=? AND status='PENDING'` 조건으로
     * 이미 종료(SUCCEEDED/FAILED)된 항목은 재갱신하지 않는다.
     * 0 row 반환 시 호출자는 멱등 스킵으로 처리한다.
     *
     * @param operationId 부모 작업 식별자.
     * @param issueKey 갱신 대상 이슈 키.
     * @param status 새 항목 상태.
     * @param reasonCode 실패 사유. [ItemStatus.FAILED] 일 때 non-null.
     * @return 갱신된 행 수 (성공=1, 멱등 스킵=0).
     */
    @Transactional
    fun updateItemResult(
        operationId: BulkOperationId,
        issueKey: IssueKey,
        status: ItemStatus,
        reasonCode: FailureReasonCode?,
    ): Int {
        log.debug("updateItemResult operationId={} issueKey={} status={}", operationId.value, issueKey.value, status)
        return dsl.update(BULK_OPERATION_ITEMS)
            .set(BULK_OPERATION_ITEMS.STATUS, status.name)
            .set(BULK_OPERATION_ITEMS.FAILURE_REASON, reasonCode?.name)
            .set(BULK_OPERATION_ITEMS.PROCESSED_AT, OffsetDateTime.now(clock))
            .where(BULK_OPERATION_ITEMS.BULK_OPERATION_ID.eq(operationId.value))
            .and(BULK_OPERATION_ITEMS.ISSUE_KEY.eq(issueKey.value))
            .and(BULK_OPERATION_ITEMS.STATUS.eq(ItemStatus.PENDING.name))
            .execute()
    }

    /**
     * 항목 상태를 전체 재집계하여 bulk_operations 의 카운터를 갱신한다.
     *
     * 증분이 아닌 전체 재집계 — 멱등하다. 같은 상태로 여러 번 호출해도 동일한 결과.
     * 스칼라 서브쿼리로 집계하여 cartesian product 없이 단일 UPDATE 로 처리한다.
     *
     * @param operationId 카운터를 재계산할 작업 식별자.
     */
    @Transactional
    fun recomputeAndPersistCounts(operationId: BulkOperationId) {
        log.debug("recomputeAndPersistCounts operationId={}", operationId.value)

        val succeededSub =
            dsl.selectCount()
                .from(BULK_OPERATION_ITEMS)
                .where(BULK_OPERATION_ITEMS.BULK_OPERATION_ID.eq(operationId.value))
                .and(BULK_OPERATION_ITEMS.STATUS.eq(ItemStatus.SUCCEEDED.name))

        val failedSub =
            dsl.selectCount()
                .from(BULK_OPERATION_ITEMS)
                .where(BULK_OPERATION_ITEMS.BULK_OPERATION_ID.eq(operationId.value))
                .and(BULK_OPERATION_ITEMS.STATUS.eq(ItemStatus.FAILED.name))

        dsl.update(BULK_OPERATIONS)
            .set(BULK_OPERATIONS.SUCCEEDED_COUNT, succeededSub.asField<Int>())
            .set(BULK_OPERATIONS.FAILED_COUNT, failedSub.asField<Int>())
            .set(
                BULK_OPERATIONS.PROCESSED_COUNT,
                succeededSub.asField<Int>().add(failedSub.asField<Int>()),
            )
            .where(BULK_OPERATIONS.ID.eq(operationId.value))
            .execute()
    }

    /**
     * RUNNING 상태 작업을 COMPLETED 로 전환한다 (CAS — 1회 완료 보장).
     *
     * `WHERE id=? AND status='RUNNING'` 조건으로 이미 COMPLETED 인 경우 0 row 반환.
     *
     * **CAS 의도** — "내가 RUNNING 으로 claimForRun 한 워커만 COMPLETED 로 전환 가능"을
     * DB 레벨에서 보장한다. affected rows 1 = 성공, 0 = 이미 다른 경로로 상태 변경 완료.
     *
     * @param id 완료할 작업 식별자.
     * @return 완료 전환 성공이면 true, 이미 완료됐으면 false.
     */
    @Transactional
    fun markCompleted(id: BulkOperationId): Boolean {
        log.debug("markCompleted id={}", id.value)
        val affected =
            dsl.update(BULK_OPERATIONS)
                .set(BULK_OPERATIONS.STATUS, BulkOperationStatus.COMPLETED.name)
                .set(BULK_OPERATIONS.COMPLETED_AT, OffsetDateTime.now(clock))
                .where(BULK_OPERATIONS.ID.eq(id.value))
                .and(BULK_OPERATIONS.STATUS.eq(BulkOperationStatus.RUNNING.name))
                .execute()
        return affected == 1
    }

    /**
     * 지정 시각 이전에 completed_at 이 설정된 작업 목록을 반환한다.
     *
     * TTL 기반 cleanup 용 — [com.bts.issue.bulk.worker.BulkOperationCleanupWorker] 가 소비한다.
     * 항목은 포함하지 않는다 (필요 시 [findItemsByOperationId] 로 별도 조회).
     *
     * ### 설계 노트 — operation-level FAILED 현황
     * 현재 코드에서 BulkOperation 전체를 FAILED 로 set 하는 경로가 없다.
     * (항목 개별 실패는 BulkOperationItem.status=FAILED 로 기록, 작업 전체는 COMPLETED 로 종료.)
     * 따라서 cleanup 대상은 사실상 COMPLETED 작업뿐이다.
     * 향후 작업레벨 FAILED 가 도입되면 completed_at 대신 별도 종단시각 컬럼 또는
     * `status IN (COMPLETED, FAILED) AND updated_at < before` 쿼리로 전환한다.
     *
     * ### LIMIT 추가 이유
     * 장시간 서비스 후 만료 작업이 대량 누적될 때 단일 트랜잭션 락이 길어지는 것을 방지한다.
     * [CLEANUP_BATCH_LIMIT] 건씩 배치 삭제하며 다음 cron 실행 시 나머지를 처리한다.
     *
     * @param before 기준 시각. 이 시각보다 이전에 completed_at 이 찍힌 작업만 반환.
     * @return 완료된 작업 목록. 없으면 빈 리스트. 최대 [CLEANUP_BATCH_LIMIT] 건.
     */
    @Transactional(readOnly = true)
    fun findCompletedBefore(before: Instant): List<BulkOperation> =
        dsl.selectFrom(BULK_OPERATIONS)
            .where(BULK_OPERATIONS.COMPLETED_AT.lt(before.toOffsetDateTime()))
            .limit(CLEANUP_BATCH_LIMIT)
            .fetch()
            .map { it.toOperation() }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 항목 목록을 배치 INSERT 한다.
     *
     * jOOQ batch 를 이용해 단일 PreparedStatement 로 N건을 한 번에 전송하여
     * 1건씩 INSERT 하는 것 대비 DB 왕복(round-trip)을 최소화한다.
     */
    private fun insertItemsBatch(operation: BulkOperation) {
        val batch =
            dsl.batch(
                dsl.insertInto(
                    BULK_OPERATION_ITEMS,
                    BULK_OPERATION_ITEMS.ID,
                    BULK_OPERATION_ITEMS.BULK_OPERATION_ID,
                    BULK_OPERATION_ITEMS.ISSUE_KEY,
                    BULK_OPERATION_ITEMS.STATUS,
                ).values(null as UUID?, null, null, null),
            )
        operation.items.forEach { item ->
            batch.bind(UUID.randomUUID(), operation.id.value, item.issueKey.value, item.status.name)
        }
        batch.execute()
    }

    /**
     * [BulkOperationsRecord] 를 도메인 [BulkOperation] 으로 변환한다.
     *
     * items 는 빈 리스트로 초기화 — 별쿼리 패턴으로 [findItemsByOperationId] 가 채운다.
     * updatedAt 은 completed_at 이 있으면 그 값을, 없으면 created_at 으로 대체한다.
     * payload 는 JSONB 에서 operationType 에 따라 역직렬화한다.
     */
    private fun BulkOperationsRecord.toOperation(): BulkOperation =
        BulkOperation(
            id = BulkOperationId(id ?: error("bulk_operations.id must not be null")),
            actorId = actorId,
            type = enumValueOf(operationType),
            status = enumValueOf(status),
            payload = payload.toPayload(enumValueOf(operationType)),
            items = emptyList(),
            totalCount = totalCount,
            processedCount = processedCount ?: 0,
            succeededCount = succeededCount ?: 0,
            failedCount = failedCount ?: 0,
            createdAt = createdAt.toInstant(),
            updatedAt = completedAt?.toInstant() ?: createdAt.toInstant(),
        )

    /** [BulkOperationPayload] 를 JSONB 로 직렬화한다. */
    private fun BulkOperationPayload.toJsonb(): JSONB = JSONB.valueOf(objectMapper.writeValueAsString(this))

    /**
     * JSONB 컬럼 값을 [BulkOperationType] 에 따라 [BulkOperationPayload] 로 역직렬화한다.
     *
     * payload 컬럼이 null 이거나 빈 JSON 인 경우 — 이전 데이터 호환성을 위해 기본값을 반환한다.
     */
    private fun JSONB?.toPayload(type: BulkOperationType): BulkOperationPayload {
        val json = this?.data()
        if (json.isNullOrBlank() || json == "{}") {
            return when (type) {
                BulkOperationType.BULK_EDIT -> BulkOperationPayload.Edit(priority = null, impact = null)
                BulkOperationType.BULK_TRANSITION -> BulkOperationPayload.Transition(toStateKey = "")
            }
        }
        return when (type) {
            BulkOperationType.BULK_EDIT ->
                objectMapper.readValue(json, BulkOperationPayload.Edit::class.java)
            BulkOperationType.BULK_TRANSITION ->
                objectMapper.readValue(json, BulkOperationPayload.Transition::class.java)
        }
    }

    companion object {
        /**
         * stale RUNNING 작업 재청 기준 (초).
         *
         * 이 시간이 경과했음에도 status=RUNNING 인 작업은 크래시로 방치된 것으로 간주하여
         * [claimForRun] 이 재선점을 허용한다.
         *
         * **산정 근거**.
         * - 최대 처리 예산: (1000/50) × 200ms = 4초.
         * - vt(visibility timeout) = 60초.
         * - threshold = vt × 5 = 300초 — 정상 처리 중(최대 4초)을 뺏지 않도록 충분히 큰 값.
         */
        const val STALE_RUNNING_THRESHOLD_SECONDS: Long = 300L

        /**
         * cleanup 1회 배치 최대 처리 건수.
         *
         * 장기간 누적된 만료 작업을 한 번에 전부 삭제하면 단일 트랜잭션 락이 길어진다.
         * 한 번에 최대 이 건수만 처리하고, 나머지는 다음 cron 실행에 위임한다.
         */
        const val CLEANUP_BATCH_LIMIT: Int = 1000
    }
}

// ── file-level helpers ─────────────────────────────────────────────────────────

/** [Instant] 를 UTC [OffsetDateTime] 으로 변환한다. */
private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

// BulkOperationRepository — bulk_operations/bulk_operation_items 테이블 jOOQ DSL 접근

package com.bts.issue.bulk.repository

import com.bts.issue.bulk.domain.BULK_OPERATION_MAX_SIZE
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
import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.PROJECTS
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.Condition
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
     * [BulkOperationType.STATUS_MIGRATION] 의 대상을 **실행 시점에 다시 긁어** 항목으로 적재하고
     * `total_count` 를 확정한다.
     *
     * 대상은 `current_state_key` ∈ [BulkOperationPayload.StatusMigration.mappings] 의 출발 상태 ∩
     * 프로젝트 ∈ [BulkOperationPayload.StatusMigration.projectKeys] 다. 상태 키는 사이트 전역이라
     * 범위가 없으면 남의 프로젝트 이슈까지 함께 옮겨진다.
     *
     * ### 왜 큐잉이 아니라 여기인가 (F15)
     * 큐잉 시점에 대상을 굳히면 큐잉 → 실행 사이에 그 상태로 들어온 이슈를 통째로 버린다.
     * 「세고 나서 옮긴다」가 아니라 **「옮기면서 센다」** 가 이 메서드의 존재 이유다.
     *
     * ### 멱등 — 새 중복 방지 코드를 만들지 않는다 (F16)
     * 워커가 적재 도중 죽고 재시작해도 같은 대상을 다시 긁는다. 중복은 V008 의
     * `UNIQUE (bulk_operation_id, issue_key)` 가 흡수한다 — `ON CONFLICT DO NOTHING` 은 그 제약에
     * 기대는 선언일 뿐 별도의 중복 판정 로직이 아니다.
     *
     * ### 상한 판정과 적재 대상은 **한 번의 읽기**에서 나온다
     * 세는 질의와 담는 질의를 나누면 그 사이가 TOCTOU 창이 된다. 그래서 상한+1 건까지만 읽어
     * 그 결과로 초과 여부와 적재 대상을 동시에 정한다. 초과일 때만 정확한 대상 수를 다시 센다 —
     * 운영자가 「어떻게 나눌지」를 정하려면 근사치가 아니라 진짜 수가 필요하기 때문이다.
     *
     * ### 정렬은 걸지 않는다
     * 결과는 **집합과 개수**로만 쓰이고(항목 배치 INSERT · 상한 비교) 초과 분기는 읽은 것을 통째로
     * 버린다. `issues.key` 는 UNIQUE 라 그 열로 정렬하면 플래너가 [statusMigrationTargets] 의 조건에
     * 맞는 인덱스 대신 그 유니크 인덱스를 걷는 쪽을 고를 여지가 생긴다 — 결과에 필요하지 않은 정렬이
     * 접근 경로를 바꾸는 것은 손해뿐이다.
     *
     * ### 소프트 삭제는 수동 필터다 (DATA.md §3)
     * 자동 필터가 없으므로 `issues` 와 `projects` 양쪽에 `deleted_at IS NULL` 을 직접 붙인다.
     * 빠뜨리면 삭제된 이슈가 이관 대상이 되고, 삭제된 프로젝트가 범위로 되살아난다.
     *
     * @param operationId 항목을 채울 작업 식별자.
     * @param payload 대상 조건. 출발 상태 매핑과 프로젝트 범위.
     * @return 적재 후 이 작업의 **항목 총수**(= 확정된 `total_count`). 다만 대상이
     *   [BULK_OPERATION_MAX_SIZE] 를 넘으면 **아무것도 적재하지 않고 실제 대상 수**를 돌려준다 —
     *   호출자가 상한과 비교해 초과를 판정하고 작업을 [markFailed] 한다. 조용히 자르지 않는다(E4).
     */
    @Transactional
    fun materializeStatusMigrationItems(
        operationId: BulkOperationId,
        payload: BulkOperationPayload.StatusMigration,
    ): Int {
        val targets = statusMigrationTargets(payload)
        val targetKeys =
            dsl.select(ISSUES.KEY)
                .from(ISSUES)
                .where(targets)
                .limit(BULK_OPERATION_MAX_SIZE + 1)
                .fetch(ISSUES.KEY)
                .filterNotNull()

        if (targetKeys.size > BULK_OPERATION_MAX_SIZE) {
            val exactCount = dsl.fetchCount(ISSUES, targets)
            log.warn(
                "status_migration_targets_over_limit id={} targetCount={} maxSize={}",
                operationId.value,
                exactCount,
                BULK_OPERATION_MAX_SIZE,
            )
            return exactCount
        }

        insertMigrationItemsBatch(operationId, targetKeys)

        val totalCount =
            dsl.fetchCount(
                BULK_OPERATION_ITEMS,
                BULK_OPERATION_ITEMS.BULK_OPERATION_ID.eq(operationId.value),
            )
        dsl.update(BULK_OPERATIONS)
            .set(BULK_OPERATIONS.TOTAL_COUNT, totalCount)
            .where(BULK_OPERATIONS.ID.eq(operationId.value))
            .execute()

        log.info(
            "status_migration_items_materialized id={} targets={} totalCount={}",
            operationId.value,
            targetKeys.size,
            totalCount,
        )
        return totalCount
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
     * RUNNING 상태 작업을 FAILED 로 전환한다 (CAS — 1회 종료 보장).
     *
     * `WHERE id=? AND status='RUNNING'` 조건이라 이미 종단인 작업은 0 row 를 돌려준다.
     * [markCompleted] 와 같은 형태다 — 「내가 claim 한 작업만 내가 끝낸다」를 DB 가 보장한다.
     *
     * ### 언제 쓰나 — 항목 실패와 다르다
     * 항목 1건의 실패는 [updateItemResult] 로 남고 작업은 그대로 COMPLETED 로 끝난다(best-effort).
     * 이 메서드는 **항목을 하나도 시작할 수 없는** 작업 전체의 실패용이다. 현재 유일한 호출 경로는
     * [BulkOperationType.STATUS_MIGRATION] 의 실행 시점 상한 초과다(E4) — 대상을 조용히 자르면
     * 잘린 나머지가 옛 상태에 남아 유령이 되는데 화면은 「완료」로 보인다.
     *
     * `completed_at` 을 함께 찍는다. V008 의 컬럼 주석이 「완료(성공 또는 실패)된 시각」이고,
     * TTL cleanup([findCompletedBefore])도 그 값으로 대상을 고른다.
     *
     * @param id 실패로 종료할 작업 식별자.
     * @return 전환 성공이면 true, 이미 종단이면 false.
     */
    @Transactional
    fun markFailed(id: BulkOperationId): Boolean {
        log.debug("markFailed id={}", id.value)
        val affected =
            dsl.update(BULK_OPERATIONS)
                .set(BULK_OPERATIONS.STATUS, BulkOperationStatus.FAILED.name)
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
     * ### 설계 노트 — operation-level FAILED
     * 항목 개별 실패는 BulkOperationItem.status=FAILED 로 기록하고 작업 전체는 COMPLETED 로 끝난다.
     * 작업 전체를 FAILED 로 두는 경로는 [markFailed] 하나뿐이며 그것도 `completed_at` 을 찍으므로
     * 이 질의가 COMPLETED 와 FAILED 를 모두 집는다 — 종단 작업은 어느 쪽이든 TTL 로 정리된다.
     * 종단 시각을 상태별로 나눠야 할 필요가 생기면 별도 컬럼 또는
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
     * 항목 목록을 배치 INSERT 한다. 항목이 0건이면 아무것도 실행하지 않는다.
     *
     * jOOQ batch 를 이용해 단일 PreparedStatement 로 N건을 한 번에 전송하여
     * 1건씩 INSERT 하는 것 대비 DB 왕복(round-trip)을 최소화한다.
     *
     * ### 빈 배치 가드가 왜 필요한가 — 방어 코드가 아니다
     * jOOQ `BatchSingle.execute()` 는 바인딩이 0개일 때 no-op 이 아니다.
     * `BatchSingle:171` 이 `BatchMultiple:160` 으로 위임하면서 **템플릿 쿼리를 NULL 바인딩 그대로 1회 실행**한다.
     * 즉 items 0건은 실질적으로 `insert into bulk_operation_items values (null, null, null, null)` 이 되어
     * NOT NULL 제약에 걸린다. 실측 예외 원문 —
     * `IntegrityConstraintViolationException: null value in column "id" of relation
     * "bulk_operation_items" violates not-null constraint`.
     *
     * ### items 0건이 정상인 경로
     * [BulkOperationType.STATUS_MIGRATION] 은 **큐잉 시점에 items 가 0건인 것이 정상**이다.
     * 이관 대상은 워커가 실행 시점에 다시 긁는다 (F15 — 「세고 나서 옮긴다」가 아니라 「옮기면서 센다」).
     * [BulkOperationType.BULK_EDIT] · [BulkOperationType.BULK_TRANSITION] 은 큐잉 시점에 항목이
     * 1건 이상임이 도메인에서 보장되므로 이 가드에 걸리지 않는다.
     * 따라서 이 한 줄은 불필요한 방어가 아니라 STATUS_MIGRATION 큐잉 경로의 정상 분기다. 지우지 말 것.
     */
    private fun insertItemsBatch(operation: BulkOperation) {
        if (operation.items.isEmpty()) return

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
     * 이관 대상 조건 — 출발 상태 ∩ 프로젝트 범위 ∩ 살아 있는 행.
     *
     * V029 에 `idx_issues_project_state_active (project_id, current_state_key) WHERE deleted_at IS NULL`
     * 이 있고 이 조건은 그 세 열을 그대로 쓴다. **실행 계획을 이 PR 이 EXPLAIN 으로 확인하지는 않았다** —
     * 대응 인덱스가 있다는 사실 진술이지 「전역 스캔으로 흐르지 않는다」는 보장이 아니다.
     * 프로젝트는 키로 받으므로 서브쿼리로 id 를 좁힌다 — JOIN 이 아니라 서브쿼리인 이유는 이슈 1행이
     * 프로젝트 1행에 대응해도 조인 결과를 세는 순간 실수하기 쉽기 때문이다(learnings: jOOQ-cartesian-product).
     */
    private fun statusMigrationTargets(payload: BulkOperationPayload.StatusMigration): Condition =
        ISSUES.CURRENT_STATE_KEY.`in`(payload.mappings.keys)
            .and(ISSUES.DELETED_AT.isNull)
            .and(
                ISSUES.PROJECT_ID.`in`(
                    dsl.select(PROJECTS.ID)
                        .from(PROJECTS)
                        .where(PROJECTS.KEY.`in`(payload.projectKeys))
                        .and(PROJECTS.DELETED_AT.isNull),
                ),
            )

    /**
     * 이관 대상 키를 PENDING 항목으로 배치 INSERT 한다. 이미 있는 키는 건너뛴다.
     *
     * [insertItemsBatch] 와 나눠 둔 이유는 **충돌 처리가 반대**이기 때문이다. 접수 경로의 중복 키는
     * 요청이 잘못 만들어졌다는 뜻이라 그대로 터져야 하고, 이 경로의 중복 키는 재시작이 정상 동작했다는
     * 뜻이라 흡수해야 한다(F16). 한 함수에 플래그로 합치면 그 차이가 호출부에서 안 보인다.
     */
    private fun insertMigrationItemsBatch(
        operationId: BulkOperationId,
        issueKeys: List<String>,
    ) {
        if (issueKeys.isEmpty()) return

        val batch =
            dsl.batch(
                dsl.insertInto(
                    BULK_OPERATION_ITEMS,
                    BULK_OPERATION_ITEMS.ID,
                    BULK_OPERATION_ITEMS.BULK_OPERATION_ID,
                    BULK_OPERATION_ITEMS.ISSUE_KEY,
                    BULK_OPERATION_ITEMS.STATUS,
                ).values(null as UUID?, null, null, null)
                    .onConflict(BULK_OPERATION_ITEMS.BULK_OPERATION_ID, BULK_OPERATION_ITEMS.ISSUE_KEY)
                    .doNothing(),
            )
        issueKeys.forEach { issueKey ->
            batch.bind(UUID.randomUUID(), operationId.value, issueKey, ItemStatus.PENDING.name)
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
                BulkOperationType.STATUS_MIGRATION ->
                    BulkOperationPayload.StatusMigration(mappings = emptyMap(), projectKeys = emptySet())
            }
        }
        return when (type) {
            BulkOperationType.BULK_EDIT ->
                objectMapper.readValue(json, BulkOperationPayload.Edit::class.java)
            BulkOperationType.BULK_TRANSITION ->
                objectMapper.readValue(json, BulkOperationPayload.Transition::class.java)
            BulkOperationType.STATUS_MIGRATION ->
                objectMapper.readValue(json, BulkOperationPayload.StatusMigration::class.java)
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

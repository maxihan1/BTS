// IssueStatusMigrationPort prod 어댑터 — 상태 이관을 bulk_operations 1건 + pgmq 로 큐잉 (FR-WF-07 D2·D4)

package com.bts.issue.adapter.outbound.workflow

import com.bts.issue.bulk.domain.BulkOperation
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.event.BulkOperationEnqueuePublisher
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.shared.issue.IssueStatusMigrationPort
import com.bts.shared.issue.StatusMigrationCommand
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [IssueStatusMigrationPort] 구현체 — 상태 이관 큐잉 (FR-WF-07 D2·D4 · 로드맵 PR 7).
 *
 * 워크플로우 정의에서 상태가 빠질 때 그 상태에 남은 이슈를 다른 상태로 옮기는 일괄작업을
 * `bulk_operations` 1건([BulkOperationType.STATUS_MIGRATION])으로 만들고 pgmq
 * `q_bulk_operations` 에 넣는다. 실제 이관은 워커가 나중에 수행한다.
 *
 * ### ★왜 여기서 `bulk_operation_items` 를 만들지 않는가
 *
 * 기존 두 타입(BULK_EDIT · BULK_TRANSITION)은 접수 시점에 이슈 키를 호출자에게 받으므로 항목을
 * 그때 적재한다. 이 타입은 다르다 — **대상은 상태와 프로젝트 범위로만 기술**되고, 그 조건을 만족하는
 * 이슈 집합은 시간에 따라 변한다.
 *
 * 큐잉 시점에 대상을 조회해 스냅샷으로 굳히면 **큐잉 → 실행 사이에 그 상태로 들어온 이슈를 통째로
 * 버린다.** 그 이슈들은 정의가 교체된 뒤 사라진 상태를 가리키는 유령이 되고, 작업은 `COMPLETED`
 * 로 보인다 — 화면은 「이관 완료」인데 실제로는 안 옮겨진 이슈가 남는다. 장부 `TODOS.md` 부채 **143**
 * (이관 판정과 교체 사이 TOCTOU)이 처방한 것이 정확히 이것이며, 그 처방 문구가
 * 「**세고 나서 옮긴다가 아니라 옮기면서 센다**」다.
 *
 * 그래서 이 어댑터는 `bulk_operations` 1건만 만들고 `total_count` 를 **0** 으로 둔다. 대상 조회 ·
 * 항목 적재 · `total_count` 확정은 워커가 claim 시점에 한다(F15). 항목 적재의 멱등은 V008 의
 * `UNIQUE (bulk_operation_id, issue_key)` 가 이미 보장하므로 별도 중복 방지 코드를 두지 않는다(F16).
 *
 * 남은 창 1개는 숨기지 않고 적는다 — **이관 완료 이후 정의 교체 전에** 또 들어오는 이슈는 여전히
 * 놓친다(E15). 그 창은 발행 경로가 「이관 → 재확인 → 교체」 루프를 돌아야 닫히고 그 루프는
 * project-workflow 소관이라 PR 7b 다.
 *
 * ### 큐잉 시점 검증은 구조 5종뿐이다
 *
 * 대상 건수도 상한 초과 여부도 이 시점에는 알 수 없다(위 문단). 그러므로 **커맨드 자체가
 * 앞뒤가 맞는지**만 본다 — E5(빈 매핑) · E5b(빈 범위) · E7(같은 출발 중복) · E1(출발 == 대상) ·
 * E2(대상이 상태 카탈로그에 없음). 상한 초과 판정은 실행 시점 몫이다(E4).
 *
 * **E6 은 허용한다** — 여러 출발이 같은 대상으로 몰리는 것은 막지 않는다. 지라도 막지 않고(J7 은
 * 대상 유일성을 요구하지 않는다), 「사라지는 상태 3개를 전부 `todo` 로」가 정상적인 운영 요청이다.
 * 막지 않는 것도 결정이므로 여기 적는다 — 중복 판정을 「매핑 키 전부 유일」로 넓히면 이 요청이 깨진다.
 *
 * ### 상태 카탈로그 조회 — 동적 참조 (BC 격리)
 *
 * `statuses` 는 project-workflow 소유 테이블이라 issue-tracking jOOQ codegen 범위 밖이다.
 * **읽기 전용 존재 확인**만 하며 [DSL.table] 동적 참조를 쓴다 — 반대 방향의 선례
 * (project-workflow 의 `IssueStatusUsageAdapter` 가 `issues` 를 같은 방식으로 센다)와 동형이다.
 * 소프트 삭제는 자동 필터가 없으므로 `deleted_at IS NULL` 을 직접 붙인다(DATA.md §3).
 *
 * 대상 상태가 **새 워크플로우 정의에** 실제로 있는지는 확인하지 않는다 — issue-tracking 은
 * 워크플로우 정의를 모르고, 그 보증은 호출자 책임이다. 여기서 막는 것은 카탈로그에 아예 없는 키다.
 *
 * ### 이 타입을 만드는 경로는 이 어댑터 하나뿐이다
 *
 * 공개 REST 접수(`POST /api/v1/bulk-operations`)는
 * `BulkOperationApplicationService.validateRequest` 가 `STATUS_MIGRATION` 을 명시 거부한다.
 * 즉 사용자가 임의로 이 작업을 만들 수 없고, 신뢰 모델이 이 클래스 한 자리에 모인다.
 *
 * ### 권한 — per-issue 전환 권한을 검사하지 않는다 (편차 X4)
 *
 * [StatusMigrationCommand.actorUserId] 를 그대로 `bulk_operations.actor_id` 에 넣는다. 이슈 하나하나의
 * TRANSITION 권한은 보지 않는다 — 보면 관리자가 건드릴 수 없는 이슈만 사라진 상태에 남아, 이 기능이
 * 막으려던 유령 상태를 이 기능이 만든다. **위조 차단은 호출자의 발행(PUBLISH) 권한 책임**이며
 * 그 계약은 [IssueStatusMigrationPort] KDoc 이 정본이다.
 *
 * ### 트랜잭션 — 영속과 enqueue 가 한 트랜잭션 (outbox)
 *
 * [BulkOperationEnqueuePublisher] 는 `Propagation.MANDATORY` 라 호출자 트랜잭션을 요구한다.
 * 작업 행이 커밋됐는데 메시지가 없거나 그 반대인 상태를 만들지 않기 위해 이 메서드가 경계를 연다
 * (DATA.md §7.2).
 *
 * @param dsl 상태 카탈로그(`statuses`) 존재 확인용 jOOQ [DSLContext].
 * @param repo `bulk_operations` 영속 대상.
 * @param enqueuePublisher pgmq `q_bulk_operations` enqueue 어댑터.
 */
@Component
class WorkflowStatusMigrationAdapter(
    private val dsl: DSLContext,
    private val repo: BulkOperationRepository,
    private val enqueuePublisher: BulkOperationEnqueuePublisher,
) : IssueStatusMigrationPort {
    private val log = LoggerFactory.getLogger(javaClass)

    // project-workflow 테이블 — jOOQ codegen 범위 밖이라 동적 참조. 클래스 KDoc §상태 카탈로그 조회 참조.
    private val statusesTable = DSL.table("statuses")
    private val statusKeyField = DSL.field("key", String::class.java)
    private val statusDeletedAtField = DSL.field("deleted_at")

    /**
     * 상태 이관을 큐잉하고 일괄작업 id 를 돌려준다.
     *
     * 구조 검증 5종을 통과하면 `bulk_operations` 1건(PENDING · `total_count=0`)을 만들고 pgmq 에
     * 넣는다. **항목은 만들지 않는다** — 사유는 클래스 KDoc.
     *
     * @param cmd 이관 커맨드. actor · 대상 프로젝트 범위 · 상태별 매핑 목록.
     * @return 생성된 일괄작업 id.
     * @throws IllegalArgumentException 구조 검증 5종 중 하나라도 위반일 때. 메시지가 어느 가드인지 밝힌다.
     */
    @Transactional
    override fun enqueueStatusMigration(cmd: StatusMigrationCommand): UUID {
        val mappings = validateAndBuildMappings(cmd)

        val operationId = BulkOperationId(UUID.randomUUID())
        val operation =
            BulkOperation.create(
                id = operationId,
                actorId = cmd.actorUserId,
                type = BulkOperationType.STATUS_MIGRATION,
                items = emptyList(),
                payload = BulkOperationPayload.StatusMigration(mappings = mappings, projectKeys = cmd.projectKeys),
            )

        repo.insert(operation)
        enqueuePublisher.enqueue(operationId)

        log.info(
            "status_migration_enqueued id={} actor={} mappingCount={} projectCount={}",
            operationId.value,
            cmd.actorUserId,
            mappings.size,
            cmd.projectKeys.size,
        )
        return operationId.value
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 커맨드의 구조 검증 5종을 수행하고 payload 에 실을 `출발 → 대상` 매핑을 만든다.
     *
     * 각 가드는 **서로 다른 메시지**를 던진다 — 메시지가 같으면 어느 조건이 발동했는지 운영자도
     * 테스트도 구분하지 못한다.
     *
     * @throws IllegalArgumentException E5 · E5b · E7 · E1 · E2 중 하나 위반 시.
     */
    private fun validateAndBuildMappings(cmd: StatusMigrationCommand): Map<String, String> {
        require(cmd.mappings.isNotEmpty()) { "statusMigration mappings must not be empty" }
        require(cmd.projectKeys.isNotEmpty()) { "statusMigration projectKeys must not be empty" }

        val duplicated =
            cmd.mappings.groupingBy { it.fromStatusKey }.eachCount().filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "statusMigration mappings have duplicate fromStatusKey: $duplicated"
        }

        val selfMapped = cmd.mappings.filter { it.fromStatusKey == it.toStatusKey }.map { it.fromStatusKey }
        require(selfMapped.isEmpty()) {
            "statusMigration fromStatusKey must differ from toStatusKey: $selfMapped"
        }

        val targets = cmd.mappings.map { it.toStatusKey }.toSet()
        val missing = targets - findKnownStatusKeys(targets)
        require(missing.isEmpty()) {
            "statusMigration toStatusKey not found in status catalog: $missing"
        }

        return cmd.mappings.associate { it.fromStatusKey to it.toStatusKey }
    }

    /**
     * [keys] 중 전역 상태 카탈로그에 살아 있는 키만 돌려준다.
     *
     * 출발 상태([com.bts.shared.issue.StatusMigrationMapping.fromStatusKey])는 **확인하지 않는다** —
     * 정의에서 빠지는 중이라 카탈로그에서 이미 소프트 삭제됐을 수 있고, 그것이 정상 경로다.
     */
    private fun findKnownStatusKeys(keys: Set<String>): Set<String> =
        dsl.select(statusKeyField)
            .from(statusesTable)
            .where(statusKeyField.`in`(keys))
            .and(statusDeletedAtField.isNull)
            .fetchSet(statusKeyField)
}

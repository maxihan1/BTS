// 워크플로우 쓰기 Repository — 생성·수정·소프트삭제·복제와 참조 카운트

package com.bts.workflow.repository

import com.bts.workflow.application.command.WorkflowStatusSeed
import com.bts.workflow.jooq.tables.Statuses.Companion.STATUSES
import com.bts.workflow.jooq.tables.WorkflowStates.Companion.WORKFLOW_STATES
import com.bts.workflow.jooq.tables.WorkflowStatuses.Companion.WORKFLOW_STATUSES
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 워크플로우 **쓰기** 전용 Repository.
 *
 * ### 왜 [WorkflowRepository] 와 나누는가
 * 그쪽은 aggregate 복원(읽기)만 담당하고 이미 235줄이다. 쓰기까지 합치면 파일 300줄 한도
 * (`DEVELOPMENT.md §2.1`)를 넘고, 읽기 경로의 2단 join 로직과 쓰기 SQL 이 한 파일에서 섞인다.
 *
 * ### 소프트 삭제 규약
 * 모든 조회는 `deleted_at IS NULL` 을 **명시적으로** 붙인다. 이 저장소에는 공통 필터 래퍼가
 * 없다(`DATA.md §3`) — 빠뜨리면 삭제된 행이 그대로 노출된다.
 */
@Repository
class WorkflowWriteRepository(
    private val dsl: DSLContext,
) {
    /** 살아 있는 워크플로우 중 같은 key 가 있는지. 소프트 삭제된 것은 세지 않는다. */
    fun existsByKey(key: String): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(WORKFLOWS).where(WORKFLOWS.KEY.eq(key)).and(WORKFLOWS.DELETED_AT.isNull),
        )

    /** 살아 있는 워크플로우의 id. 없으면 null. */
    fun findLiveIdByKey(key: String): UUID? =
        dsl
            .select(WORKFLOWS.ID)
            .from(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(key))
            .and(WORKFLOWS.DELETED_AT.isNull)
            .fetchOne(WORKFLOWS.ID)

    /** 편집 잠금 여부. 대상이 없으면 false. */
    fun isLocked(workflowId: UUID): Boolean =
        dsl
            .select(WORKFLOWS.IS_LOCKED)
            .from(WORKFLOWS)
            .where(WORKFLOWS.ID.eq(workflowId))
            .fetchOne(WORKFLOWS.IS_LOCKED) ?: false

    /**
     * 이 워크플로우를 참조하는 스킴 매핑 수.
     *
     * 하드 삭제였다면 FK `ON DELETE RESTRICT` 가 막아 줬겠지만 소프트 삭제는 DB 가 개입하지 않는다.
     */
    fun countSchemeReferences(workflowId: UUID): Int =
        dsl
            .selectCount()
            // 스킴 테이블은 코드젠 미러 밖이라 jOOQ 상수가 없다 — DSL.table()/DSL.field() 동적 참조를
            // 쓰는 것이 이 BC 의 관례다(SchemeIssueTypeMappingRepository.kt:75-77).
            .from(DSL.table("workflow_scheme_issue_type_mappings"))
            .where(DSL.field("workflow_id", UUID::class.java).eq(workflowId))
            .fetchOne(0, Int::class.java) ?: 0

    /** 워크플로우 행 1건. `origin='CUSTOM'` · `version=0` · `is_locked=false` 로 시작한다. */
    fun insertWorkflow(
        key: String,
        name: String,
        description: String?,
    ): UUID =
        dsl
            .insertInto(WORKFLOWS)
            .set(WORKFLOWS.KEY, key)
            .set(WORKFLOWS.NAME, name)
            .set(WORKFLOWS.DESCRIPTION, description)
            .set(WORKFLOWS.ORIGIN, "CUSTOM")
            .returning(WORKFLOWS.ID)
            .fetchOne(WORKFLOWS.ID)
            ?: error("workflows INSERT 가 id 를 돌려주지 않았다 — RETURNING 절을 확인할 것")

    /**
     * 상태 씨앗을 전역 카탈로그에 넣고(있으면 재사용) 워크플로우에 편성한다.
     *
     * `ON CONFLICT` 에 부분 인덱스 술어를 붙인다 — `V206` 이후 `statuses.key` 는
     * `WHERE deleted_at IS NULL` 부분 유니크라 술어 없이는 추론이 안 된다.
     */
    fun attachStatuses(
        workflowId: UUID,
        seeds: List<WorkflowStatusSeed>,
    ) {
        seeds.forEach { seed ->
            val statusId =
                dsl
                    .insertInto(STATUSES)
                    .set(STATUSES.KEY, seed.key)
                    .set(STATUSES.NAME, seed.name)
                    .set(STATUSES.CATEGORY, seed.category)
                    .onConflict(STATUSES.KEY)
                    .where(STATUSES.DELETED_AT.isNull)
                    .doUpdate()
                    .set(STATUSES.NAME, seed.name)
                    .returning(STATUSES.ID)
                    .fetchOne(STATUSES.ID)
                    ?: error("statuses upsert 가 id 를 돌려주지 않았다 — ON CONFLICT 술어가 부분 인덱스와 맞는지 확인할 것")

            dsl
                .insertInto(WORKFLOW_STATUSES)
                .set(WORKFLOW_STATUSES.WORKFLOW_ID, workflowId)
                .set(WORKFLOW_STATUSES.STATUS_ID, statusId)
                .set(WORKFLOW_STATUSES.DISPLAY_ORDER, seed.displayOrder)
                .onConflict(WORKFLOW_STATUSES.WORKFLOW_ID, WORKFLOW_STATUSES.STATUS_ID)
                .doUpdate()
                .set(WORKFLOW_STATUSES.DISPLAY_ORDER, seed.displayOrder)
                .execute()
        }
    }

    /** 이름·설명을 고치고 낙관적 락 버전을 올린다. 버전 충돌 판정은 로드맵 PR 6 이 맡는다. */
    fun updateNameAndDescription(
        workflowId: UUID,
        name: String,
        description: String?,
    ) {
        dsl
            .update(WORKFLOWS)
            .set(WORKFLOWS.NAME, name)
            .set(WORKFLOWS.DESCRIPTION, description)
            .set(WORKFLOWS.VERSION, WORKFLOWS.VERSION.plus(1))
            .set(WORKFLOWS.UPDATED_AT, OffsetDateTime.now())
            .where(WORKFLOWS.ID.eq(workflowId))
            .execute()
    }

    /** 소프트 삭제. 행을 지우지 않고 `deleted_at` 만 채운다(`DATA.md §1.2`). */
    fun softDelete(workflowId: UUID) {
        dsl
            .update(WORKFLOWS)
            .set(WORKFLOWS.DELETED_AT, OffsetDateTime.now())
            .set(WORKFLOWS.UPDATED_AT, OffsetDateTime.now())
            .where(WORKFLOWS.ID.eq(workflowId))
            .execute()
    }

    /** 원본의 상태 편성을 그대로 복사한다. 전환 복사는 [copyTransitions] 가 맡는다. */
    fun copyStatusComposition(
        sourceId: UUID,
        targetId: UUID,
    ) {
        dsl
            .insertInto(WORKFLOW_STATUSES)
            .columns(
                WORKFLOW_STATUSES.WORKFLOW_ID,
                WORKFLOW_STATUSES.STATUS_ID,
                WORKFLOW_STATUSES.DISPLAY_ORDER,
            ).select(
                dsl
                    .select(
                        DSL.value(targetId),
                        WORKFLOW_STATUSES.STATUS_ID,
                        WORKFLOW_STATUSES.DISPLAY_ORDER,
                    ).from(WORKFLOW_STATUSES)
                    .where(WORKFLOW_STATUSES.WORKFLOW_ID.eq(sourceId)),
            ).execute()
    }

    /**
     * 원본의 구형 상태 행과 전환을 복사한다. 전역 카탈로그 편성은 [copyStatusComposition] 이 먼저 한다.
     *
     * 구형 `workflow_states` 도 함께 복사한다 — 전환은 더 이상 그 테이블을 참조하지 않지만
     * `PostActionTransitionResolver` 가 아직 상태 키를 그 테이블로 해석한다. add → backfill → drop
     * 의 3단(DROP)이 오기 전까지는 두 세대를 나란히 유지하는 편이 안전하다.
     */
    fun copyLegacyStatesAndTransitions(
        sourceId: UUID,
        targetId: UUID,
    ) {
        dsl.copyLegacyStates(sourceId, targetId)
        dsl.copyTransitions(sourceId, targetId)
    }
}

/** 구형 `workflow_states` 행 복사. 전환 FK 는 더 이상 이 테이블을 쓰지 않으므로 id 매핑이 필요 없다. */
private fun DSLContext.copyLegacyStates(
    sourceId: UUID,
    targetId: UUID,
) {
    insertInto(WORKFLOW_STATES)
        .columns(
            WORKFLOW_STATES.WORKFLOW_ID,
            WORKFLOW_STATES.KEY,
            WORKFLOW_STATES.NAME,
            WORKFLOW_STATES.CATEGORY,
            WORKFLOW_STATES.DISPLAY_ORDER,
        ).select(
            select(
                DSL.value(targetId),
                WORKFLOW_STATES.KEY,
                WORKFLOW_STATES.NAME,
                WORKFLOW_STATES.CATEGORY,
                WORKFLOW_STATES.DISPLAY_ORDER,
            ).from(WORKFLOW_STATES)
                .where(WORKFLOW_STATES.WORKFLOW_ID.eq(sourceId)),
        ).execute()
}

/**
 * 전환을 복사하며 출발·도착을 **복제본의** `workflow_statuses` 행으로 다시 잇는다 (V207).
 *
 * 두 세대를 잇는 다리는 전역 카탈로그의 `status_id` 다. 원본 편성 행 → 그 status_id →
 * 복제본에서 같은 status_id 를 가진 편성 행, 순서로 찾는다.
 *
 * 출발지 join 만 LEFT 인 것은 GLOBAL·INITIAL 전환의 `from_status_id` 가 NULL 이기 때문이다.
 * NORMAL 인데 대응 행을 못 찾으면 NULL 이 되어 `ck_transition_kind_from` 이 즉시 막는다 —
 * 조용히 끊어진 전환을 만드는 것보다 복제를 실패시키는 편이 싸다.
 */
private fun DSLContext.copyTransitions(
    sourceId: UUID,
    targetId: UUID,
) {
    val sourceFrom = WORKFLOW_STATUSES.`as`("src_from")
    val targetFrom = WORKFLOW_STATUSES.`as`("tgt_from")
    val sourceTo = WORKFLOW_STATUSES.`as`("src_to")
    val targetTo = WORKFLOW_STATUSES.`as`("tgt_to")

    insertInto(WORKFLOW_TRANSITIONS)
        .columns(
            WORKFLOW_TRANSITIONS.WORKFLOW_ID,
            WORKFLOW_TRANSITIONS.KIND,
            WORKFLOW_TRANSITIONS.NAME,
            WORKFLOW_TRANSITIONS.FROM_STATUS_ID,
            WORKFLOW_TRANSITIONS.TO_STATUS_ID,
            WORKFLOW_TRANSITIONS.DISPLAY_ORDER,
        ).select(
            select(
                DSL.value(targetId),
                WORKFLOW_TRANSITIONS.KIND,
                WORKFLOW_TRANSITIONS.NAME,
                targetFrom.ID,
                targetTo.ID,
                WORKFLOW_TRANSITIONS.DISPLAY_ORDER,
            ).from(WORKFLOW_TRANSITIONS)
                .leftJoin(sourceFrom).on(sourceFrom.ID.eq(WORKFLOW_TRANSITIONS.FROM_STATUS_ID))
                .leftJoin(targetFrom)
                .on(targetFrom.WORKFLOW_ID.eq(targetId).and(targetFrom.STATUS_ID.eq(sourceFrom.STATUS_ID)))
                .join(sourceTo).on(sourceTo.ID.eq(WORKFLOW_TRANSITIONS.TO_STATUS_ID))
                .join(targetTo)
                .on(targetTo.WORKFLOW_ID.eq(targetId).and(targetTo.STATUS_ID.eq(sourceTo.STATUS_ID)))
                .where(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(sourceId)),
        ).execute()
}

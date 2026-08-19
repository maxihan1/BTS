// 워크플로우 쓰기 Repository — 생성·수정·소프트삭제·복제와 참조 카운트

package com.bts.workflow.repository

import com.bts.workflow.application.command.WorkflowStatusSeed
import com.bts.workflow.jooq.tables.Statuses.Companion.STATUSES
import com.bts.workflow.jooq.tables.WorkflowStatuses.Companion.WORKFLOW_STATUSES
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
            .fetchOne(WORKFLOWS.ID)!!

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
                    .fetchOne(STATUSES.ID)!!

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
     * 원본의 상태·전환을 복사한다.
     *
     * 전환은 아직 구형 `workflow_states.id` 를 참조하므로(로드맵 PR 4 가 재지정) 그 테이블의
     * 행도 함께 복사해야 from/to 가 이어진다. 원시 SQL 을 쓰는 이유 —
     * `workflow_states` 는 코드젠 미러에 있으나 이 복사는 **id 매핑을 DB 안에서 끝내야** 한다.
     */
    fun copyLegacyStatesAndTransitions(
        sourceId: UUID,
        targetId: UUID,
    ) {
        dsl.execute(
            """
            WITH copied AS (
                INSERT INTO workflow_states (workflow_id, key, name, category, display_order)
                SELECT ?, key, name, category, display_order FROM workflow_states WHERE workflow_id = ?
                RETURNING id, key
            )
            INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name)
            SELECT ?, f.id, t.id, wt.name
              FROM workflow_transitions wt
              JOIN workflow_states src_f ON src_f.id = wt.from_state_id
              JOIN workflow_states src_t ON src_t.id = wt.to_state_id
              JOIN copied f ON f.key = src_f.key
              JOIN copied t ON t.key = src_t.key
             WHERE wt.workflow_id = ?
            """.trimIndent(),
            targetId,
            sourceId,
            targetId,
            sourceId,
        )
    }
}

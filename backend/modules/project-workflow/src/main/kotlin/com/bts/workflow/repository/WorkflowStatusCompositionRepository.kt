// 워크플로우↔상태 편성 Repository — 추가·제거·순서변경. display_order 만 다룬다

package com.bts.workflow.repository

import com.bts.workflow.jooq.tables.Statuses.Companion.STATUSES
import com.bts.workflow.jooq.tables.WorkflowStatuses.Companion.WORKFLOW_STATUSES
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

/** 워크플로우에 편성된 상태 1건. */
data class ComposedStatus(
    val statusId: UUID,
    val statusKey: String,
    val displayOrder: Int,
)

/**
 * `workflow_statuses` 편성 Repository.
 *
 * ### `layout_x` · `layout_y` 를 건드리지 않는다
 * 그 두 컬럼은 로드맵 **PR 9**(xyflow 다이어그램)의 것이다. 편성이나 순서를 바꿀 때
 * 좌표를 함께 손대면 사용자가 배치해 둔 다이어그램이 조용히 흐트러진다.
 */
@Repository
class WorkflowStatusCompositionRepository(
    private val dsl: DSLContext,
) {
    /** 이 워크플로우에 편성된 상태 전부. 표시 순서대로 준다. 삭제된 카탈로그 항목은 뺀다. */
    fun findComposition(workflowId: UUID): List<ComposedStatus> =
        dsl
            .select(WORKFLOW_STATUSES.STATUS_ID, STATUSES.KEY, WORKFLOW_STATUSES.DISPLAY_ORDER)
            .from(WORKFLOW_STATUSES)
            .join(STATUSES)
            .on(STATUSES.ID.eq(WORKFLOW_STATUSES.STATUS_ID))
            .where(WORKFLOW_STATUSES.WORKFLOW_ID.eq(workflowId))
            .and(STATUSES.DELETED_AT.isNull)
            .orderBy(WORKFLOW_STATUSES.DISPLAY_ORDER)
            .fetch()
            .map {
                ComposedStatus(
                    statusId = it.required(WORKFLOW_STATUSES.STATUS_ID),
                    statusKey = it.required(STATUSES.KEY),
                    displayOrder = it.required(WORKFLOW_STATUSES.DISPLAY_ORDER),
                )
            }

    /** 상태를 편성한다. 이미 있으면 표시 순서만 갱신한다 — 같은 요청을 두 번 보내도 중복되지 않는다. */
    fun attach(
        workflowId: UUID,
        statusId: UUID,
        displayOrder: Int,
    ) {
        dsl
            .insertInto(WORKFLOW_STATUSES)
            .set(WORKFLOW_STATUSES.WORKFLOW_ID, workflowId)
            .set(WORKFLOW_STATUSES.STATUS_ID, statusId)
            .set(WORKFLOW_STATUSES.DISPLAY_ORDER, displayOrder)
            .onConflict(WORKFLOW_STATUSES.WORKFLOW_ID, WORKFLOW_STATUSES.STATUS_ID)
            .doUpdate()
            .set(WORKFLOW_STATUSES.DISPLAY_ORDER, displayOrder)
            .execute()
    }

    /** 편성을 뗀다. 카탈로그의 상태 자체는 그대로 남는다. */
    fun detach(
        workflowId: UUID,
        statusId: UUID,
    ) {
        dsl
            .deleteFrom(WORKFLOW_STATUSES)
            .where(WORKFLOW_STATUSES.WORKFLOW_ID.eq(workflowId))
            .and(WORKFLOW_STATUSES.STATUS_ID.eq(statusId))
            .execute()
    }

    /** 표시 순서만 갱신한다. 좌표는 건드리지 않는다. */
    fun updateDisplayOrder(
        workflowId: UUID,
        statusId: UUID,
        displayOrder: Int,
    ) {
        dsl
            .update(WORKFLOW_STATUSES)
            .set(WORKFLOW_STATUSES.DISPLAY_ORDER, displayOrder)
            .where(WORKFLOW_STATUSES.WORKFLOW_ID.eq(workflowId))
            .and(WORKFLOW_STATUSES.STATUS_ID.eq(statusId))
            .execute()
    }
}

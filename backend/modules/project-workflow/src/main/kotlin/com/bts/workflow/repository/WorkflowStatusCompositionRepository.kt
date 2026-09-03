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
 * 편성된 상태 1건의 다이어그램 노드 좌표.
 *
 * ### 왜 둘 다 nullable 인가
 * `workflow_statuses.layout_x`·`layout_y` 는 nullable `REAL` 이고 「NULL 이면 자동 배치」가 그
 * 컬럼의 계약이다(V203). 0.0 으로 접으면 「아직 배치한 적 없음」이 「원점에 두었음」과 같아져
 * 편집기가 배치 안 된 노드를 전부 원점에 겹쳐 그린다.
 *
 * @property x X 좌표. 아직 배치하지 않았으면 null.
 * @property y Y 좌표. 위와 같다.
 */
data class StatusLayout(
    val x: Double?,
    val y: Double?,
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

    /**
     * 편성된 상태의 다이어그램 좌표. 상태 키 → 좌표.
     *
     * ### 왜 [findComposition] 에 얹지 않고 따로 읽는가
     * 두 조회의 소비자가 다르다. [findComposition] 은 편성 가드(마지막 상태·순서 전체집합)가 쓰고,
     * 좌표는 초안 편집기만 쓴다. 한 함수로 묶으면 가드가 도는 모든 요청이 쓰지도 않는 두 컬럼을
     * 함께 실어 나르고, `ComposedStatus` 가 편집기 전용 필드를 지고 다니게 된다.
     *
     * ### 소프트 삭제된 카탈로그 항목은 뺀다
     * [findComposition] 과 같은 필터다. 두 결과의 키 집합이 갈리면 초안 조회가 「편성에는 없는데
     * 좌표만 있는」 상태를 만들어 낸다.
     *
     * @param workflowId 좌표를 읽을 워크플로우.
     * @return 상태 키 → 좌표. 편성 행이 없는 상태는 맵에 없다.
     */
    fun findLayouts(workflowId: UUID): Map<String, StatusLayout> =
        dsl
            .select(STATUSES.KEY, WORKFLOW_STATUSES.LAYOUT_X, WORKFLOW_STATUSES.LAYOUT_Y)
            .from(WORKFLOW_STATUSES)
            .join(STATUSES)
            .on(STATUSES.ID.eq(WORKFLOW_STATUSES.STATUS_ID))
            .where(WORKFLOW_STATUSES.WORKFLOW_ID.eq(workflowId))
            .and(STATUSES.DELETED_AT.isNull)
            .fetch()
            .associate {
                // 컬럼은 REAL(float4)이고 초안 JSON 은 Double 이다. 좁힘이 아니라 넓힘이라 값이
                // 상하지 않는다 — 반대 방향(Double → Float)은 발행 경로가 명시적으로 한다.
                it.required(STATUSES.KEY) to
                    StatusLayout(
                        x = it[WORKFLOW_STATUSES.LAYOUT_X]?.toDouble(),
                        y = it[WORKFLOW_STATUSES.LAYOUT_Y]?.toDouble(),
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

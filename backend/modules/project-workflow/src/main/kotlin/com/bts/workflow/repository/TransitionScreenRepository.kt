// 전환 화면 읽기 Repository — 발행된 전환마다 대화상자로 받을 필드 (FR-WF-01 D10)

package com.bts.workflow.repository

import com.bts.workflow.domain.DraftScreenFieldDto
import com.bts.workflow.jooq.tables.WorkflowTransitionScreenFields.Companion.WORKFLOW_TRANSITION_SCREEN_FIELDS
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * `workflow_transition_screen_fields` 읽기. 쓰기는 발행([WorkflowPublishRepository])과 시드가 한다.
 *
 * 워크플로우 단위로 한 번에 읽는다 — 규칙처럼 전환마다 읽으면 편집기를 열 때 전환 수만큼 쿼리가 늘어난다.
 */
@Repository
class TransitionScreenRepository(
    private val dsl: DSLContext,
) {
    /**
     * 그 워크플로우의 전환 id → 화면 필드. 화면이 없는 전환은 키가 없다.
     * 필드는 [DraftScreenFieldDto.FIELDS] 순서로 정렬한다 — 테이블에 순서 컬럼이 없다(V212).
     */
    fun findByWorkflowId(workflowId: UUID): Map<UUID, List<DraftScreenFieldDto>> =
        dsl
            .select(
                WORKFLOW_TRANSITION_SCREEN_FIELDS.TRANSITION_ID,
                WORKFLOW_TRANSITION_SCREEN_FIELDS.FIELD,
                WORKFLOW_TRANSITION_SCREEN_FIELDS.REQUIRED,
            )
            .from(WORKFLOW_TRANSITION_SCREEN_FIELDS)
            .join(WORKFLOW_TRANSITIONS)
            .on(WORKFLOW_TRANSITIONS.ID.eq(WORKFLOW_TRANSITION_SCREEN_FIELDS.TRANSITION_ID))
            .where(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(workflowId))
            .fetch()
            .groupBy(
                { it.required(WORKFLOW_TRANSITION_SCREEN_FIELDS.TRANSITION_ID) },
                {
                    DraftScreenFieldDto(
                        field = it.required(WORKFLOW_TRANSITION_SCREEN_FIELDS.FIELD),
                        required = it.required(WORKFLOW_TRANSITION_SCREEN_FIELDS.REQUIRED),
                    )
                },
            )
            .mapValues { (_, fields) -> fields.sortedBy { field -> DraftScreenFieldDto.FIELDS.indexOf(field.field) } }
}

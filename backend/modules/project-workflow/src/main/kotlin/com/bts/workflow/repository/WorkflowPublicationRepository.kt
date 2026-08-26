// 발행 이력 Repository — append-only. 쓰기는 삽입뿐이고 수정·삭제 경로를 두지 않는다

package com.bts.workflow.repository

import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.jooq.tables.WorkflowPublications.Companion.WORKFLOW_PUBLICATIONS
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * `workflow_publications` 접근.
 *
 * ### 이 클래스에 update·delete 가 없는 것은 실수가 아니다
 * 발행 이력은 append-only 다. 이력을 고치면 「무엇을 언제 발행했는가」가 사라지고, 되돌리기의
 * 원본도 함께 사라진다. 되돌리려면 이전 정의를 **새 발행으로 다시 올린다**.
 *
 * 다만 그 규칙을 이 클래스가 지고 있지는 않다 — 여기에 메서드를 하나 더하면 그만이기 때문이다.
 * 실제 강제는 V208 의 `trg_workflow_publications_append_only` 트리거가 한다. 이 KDoc 은 왜
 * 메서드가 없는지를 설명할 뿐이고, 계약은 DB 에 있다.
 */
@Repository
class WorkflowPublicationRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 다음 발행 회차. 첫 발행이면 1.
     *
     * 전역 시퀀스를 쓰지 않는 것은 「이 워크플로우의 3번째 발행」이 사람이 읽는 단위이기 때문이다.
     * 동시 발행 경합은 이 값이 아니라 `workflows.version` 낙관적 락이 막는다 — 두 세션이 같은
     * 회차를 계산해도 버전 bump 에서 하나가 떨어진다. 그래도 새 나가는 경우는
     * `uq_workflow_publications_version` 이 마지막으로 잡는다.
     */
    fun nextVersionNo(workflowId: UUID): Int =
        (
            dsl.select(DSL.max(WORKFLOW_PUBLICATIONS.VERSION_NO))
                .from(WORKFLOW_PUBLICATIONS)
                .where(WORKFLOW_PUBLICATIONS.WORKFLOW_ID.eq(workflowId))
                .fetchOne(0, Int::class.java) ?: 0
        ) + 1

    /**
     * 발행 스냅샷을 적재한다.
     *
     * @param versionNo [nextVersionNo] 가 돌려준 회차.
     * @param definition 발행 시점의 정의. 이후 편집에 영향받지 않는 사본이라야 되돌리기의 원본이 된다.
     * @param publishedBy 발행자 user id. 없으면 null.
     */
    fun insert(
        workflowId: UUID,
        versionNo: Int,
        definition: WorkflowDraftDefinition,
        publishedBy: UUID?,
    ) {
        dsl.insertInto(WORKFLOW_PUBLICATIONS)
            .set(WORKFLOW_PUBLICATIONS.WORKFLOW_ID, workflowId)
            .set(WORKFLOW_PUBLICATIONS.VERSION_NO, versionNo)
            .set(WORKFLOW_PUBLICATIONS.DEFINITION, JSONB.valueOf(objectMapper.writeValueAsString(definition)))
            .set(WORKFLOW_PUBLICATIONS.PUBLISHED_BY, publishedBy)
            .execute()
    }

    /** 이 워크플로우의 발행 횟수. 발행된 적이 없으면 0. */
    fun countByWorkflowId(workflowId: UUID): Int {
        val ofWorkflow = WORKFLOW_PUBLICATIONS.WORKFLOW_ID.eq(workflowId)
        return dsl.fetchCount(WORKFLOW_PUBLICATIONS, ofWorkflow)
    }
}

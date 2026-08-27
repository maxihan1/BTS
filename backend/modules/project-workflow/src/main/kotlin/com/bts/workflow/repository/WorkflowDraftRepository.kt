// 워크플로우 초안 Repository — definition JSONB 왕복과 워크플로우당 1행 upsert

package com.bts.workflow.repository

import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.jooq.tables.WorkflowDrafts.Companion.WORKFLOW_DRAFTS
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * `workflow_drafts` 접근.
 *
 * ### 왜 upsert 인가
 * `workflow_id` 가 PK 라 워크플로우당 초안이 하나다. 「있으면 갱신, 없으면 삽입」을 호출부가
 * 매번 판단하면 그 사이에 다른 세션이 초안을 만들었을 때 PK 위반으로 죽는다.
 * `ON CONFLICT DO UPDATE` 가 그 경합을 DB 한 번의 왕복으로 닫는다.
 *
 * ### 파싱 실패를 빈 정의로 접지 않는다
 * 형제인 [com.bts.workflow.transition.TransitionRuleRepository] 는 JSONB 파싱이 실패하면
 * `log.warn` 후 빈 Map 을 돌려준다. 규칙 config 는 그래도 되지만 **초안은 다르다** —
 * 빈 정의를 돌려주면 관리자에게는 편집하던 내용이 사라진 것으로 보이고, 그 화면에서 저장하는
 * 순간 진짜로 사라진다. 되돌릴 수 없는 손실이라 여기서는 멈추는 편이 싸다.
 */
@Repository
class WorkflowDraftRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 초안을 저장한다. 이미 있으면 정의만 덮어쓴다.
     *
     * ### `base_version` 은 INSERT 만 쓴다 — 갱신 경로가 없다
     * 낙관적 락의 기준값이라 한 번 정해지면 바뀌면 안 된다. 그것을 「호출부가 옛 값을 다시
     * 넣어 준다」로 지키면 읽기와 쓰기가 두 문장으로 갈려 그 사이가 열리고, 무엇보다 **호출부
     * 하나가 규칙을 어기면 조용히 무너진다.** `DO UPDATE` 절에서 이 컬럼을 아예 빼면 갱신
     * 경로 자체가 없어져 「처음 한 번만 기록」이 SQL 로 강제된다.
     *
     * @param workflowId 초안을 붙일 워크플로우.
     * @param definition 초안 정의 전체.
     * @param baseVersion 편집기가 보고 있던 `workflows.version`. **행이 새로 생길 때만** 쓰인다.
     * @param updatedBy 편집자 user id. 없으면 null.
     */
    fun upsert(
        workflowId: UUID,
        definition: WorkflowDraftDefinition,
        baseVersion: Long,
        updatedBy: UUID?,
    ) {
        val json = JSONB.valueOf(objectMapper.writeValueAsString(definition))
        val now = OffsetDateTime.now()

        dsl.insertInto(WORKFLOW_DRAFTS)
            .set(WORKFLOW_DRAFTS.WORKFLOW_ID, workflowId)
            .set(WORKFLOW_DRAFTS.DEFINITION, json)
            .set(WORKFLOW_DRAFTS.BASE_VERSION, baseVersion)
            .set(WORKFLOW_DRAFTS.UPDATED_AT, now)
            .set(WORKFLOW_DRAFTS.UPDATED_BY, updatedBy)
            .onConflict(WORKFLOW_DRAFTS.WORKFLOW_ID)
            .doUpdate()
            .set(WORKFLOW_DRAFTS.DEFINITION, json)
            .set(WORKFLOW_DRAFTS.UPDATED_AT, now)
            .set(WORKFLOW_DRAFTS.UPDATED_BY, updatedBy)
            .execute()
    }

    /**
     * 초안을 읽는다. 없으면 null.
     *
     * @throws IllegalStateException 저장된 JSONB 가 초안 정의로 읽히지 않을 때
     */
    fun findByWorkflowId(workflowId: UUID): WorkflowDraftRow? {
        val record =
            dsl.select(
                WORKFLOW_DRAFTS.DEFINITION,
                WORKFLOW_DRAFTS.BASE_VERSION,
                WORKFLOW_DRAFTS.UPDATED_AT,
                WORKFLOW_DRAFTS.UPDATED_BY,
            )
                .from(WORKFLOW_DRAFTS)
                .where(WORKFLOW_DRAFTS.WORKFLOW_ID.eq(workflowId))
                .fetchOne() ?: return null

        return WorkflowDraftRow(
            workflowId = workflowId,
            definition = parseDefinition(workflowId, record.get(WORKFLOW_DRAFTS.DEFINITION)),
            baseVersion = record.get(WORKFLOW_DRAFTS.BASE_VERSION) ?: 0,
            updatedAt = record.get(WORKFLOW_DRAFTS.UPDATED_AT),
            updatedBy = record.get(WORKFLOW_DRAFTS.UPDATED_BY),
        )
    }

    /**
     * 초안을 폐기한다.
     *
     * @return 지운 행이 있으면 true. 호출부가 204 와 404 를 가르는 근거다 — 없는 것을 지웠다고
     *   성공을 보고하면 화면이 「폐기됨」을 표시하고 관리자는 초안이 있었다고 오해한다.
     */
    fun deleteByWorkflowId(workflowId: UUID): Boolean =
        dsl.deleteFrom(WORKFLOW_DRAFTS)
            .where(WORKFLOW_DRAFTS.WORKFLOW_ID.eq(workflowId))
            .execute() > 0

    private fun parseDefinition(
        workflowId: UUID,
        jsonb: JSONB?,
    ): WorkflowDraftDefinition {
        val raw =
            jsonb?.data()?.takeIf { it.isNotBlank() }
                ?: error("워크플로우 $workflowId 의 초안 정의가 비어 있다. 저장 경로가 definition 을 채우지 않았다")

        return try {
            objectMapper.readValue(raw, WorkflowDraftDefinition::class.java)
        } catch (ex: JsonProcessingException) {
            throw IllegalStateException(
                "워크플로우 $workflowId 의 초안 정의를 읽을 수 없다. " +
                    "빈 초안으로 접으면 편집 내용이 사라진 것처럼 보이고 그대로 저장되면 실제로 사라진다. " +
                    "원인: ${ex.message}",
                ex,
            )
        }
    }
}

/**
 * `workflow_drafts` 한 행.
 *
 * @property workflowId 소속 워크플로우.
 * @property definition 초안 정의 전체.
 * @property baseVersion 초안을 뜬 시점의 `workflows.version`.
 * @property updatedAt 마지막 편집 시각.
 * @property updatedBy 마지막 편집자. 없으면 null.
 */
data class WorkflowDraftRow(
    val workflowId: UUID,
    val definition: WorkflowDraftDefinition,
    val baseVersion: Long,
    val updatedAt: OffsetDateTime?,
    val updatedBy: UUID?,
)

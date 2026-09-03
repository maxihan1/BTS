// 발행 Repository — 낙관적 락 버전 판정과 정규 테이블 재작성

package com.bts.workflow.repository

import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.jooq.tables.Statuses.Companion.STATUSES
import com.bts.workflow.jooq.tables.WorkflowStatuses.Companion.WORKFLOW_STATUSES
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 발행 시 정규 테이블을 초안대로 갈아 끼운다.
 *
 * ### 왜 diff 가 아니라 전량 교체인가
 * 초안은 정의 **전체**를 담는다. diff 를 계산하려면 「무엇이 같은 전환인가」를 정해야 하는데,
 * 초안의 전환에는 아직 identity 가 없다(DB 가 발행 시점에 정한다). 같음을 (from,to,name) 으로
 * 추정하면 이름만 바꾼 편집이 삭제+생성으로 보이고, 그 반대도 마찬가지다. 전량 교체는 그 추정을
 * 아예 하지 않는다.
 *
 * 대가는 전환 id 가 발행마다 바뀐다는 것이다. 그 id 를 밖에서 들고 있는 곳은 없다 —
 * 이슈의 상태는 `current_state_key`(문자열)로 저장되고 전환 id 를 참조하지 않는다.
 *
 * ### 상태는 만들지 않는다
 * 초안이 전역 카탈로그에 없는 상태 키를 가리키면 발행을 **거부**한다. 상태 생성은 별도 API
 * (`POST /api/v1/statuses`)의 관심사이고, 발행이 상태를 슬쩍 만들면 「이름 대소문자 무시 유일」
 * (V203 `uq_statuses_lower_name`) 같은 카탈로그 규칙을 우회하는 두 번째 경로가 생긴다.
 */
@Repository
class WorkflowPublishRepository(
    private val dsl: DSLContext,
) {
    /**
     * 살아 있는 워크플로우의 id 와 낙관적 락 버전. 없거나 소프트 삭제됐으면 null.
     *
     * 둘을 한 번에 읽는 것은 발행 경로가 **항상 함께** 필요로 하기 때문이다. 나눠 두면
     * `findLiveIdByKey` 와 `findVersion` 이 같은 행을 두 번 읽고, 그 사이에 다른 세션이 발행하면
     * id 는 옛 것이고 버전은 새 것인 조합이 나온다.
     */
    fun findLiveByKey(key: String): WorkflowVersionRow? =
        dsl.select(WORKFLOWS.ID, WORKFLOWS.VERSION, WORKFLOWS.ORIGIN)
            .from(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(key))
            .and(WORKFLOWS.DELETED_AT.isNull)
            .fetchOne()
            ?.let {
                WorkflowVersionRow(
                    id = it[WORKFLOWS.ID]!!,
                    version = it[WORKFLOWS.VERSION] ?: 0,
                    origin = it[WORKFLOWS.ORIGIN] ?: "CUSTOM",
                )
            }

    /**
     * 버전이 [expectedVersion] 과 같을 때만 bump 한다.
     *
     * `SprintRepository.update` 와 같은 형태다 — affected 0 이 「없음」과 「충돌」 둘 다를 뜻하므로
     * 호출부가 존재 여부를 재조회해 404 와 409 를 가른다.
     *
     * @return bump 했으면 true. 대상이 없거나 버전이 어긋나면 false.
     */
    fun bumpVersionIfMatches(
        workflowId: UUID,
        expectedVersion: Long,
    ): Boolean =
        dsl.update(WORKFLOWS)
            .set(WORKFLOWS.VERSION, expectedVersion + 1)
            .set(WORKFLOWS.UPDATED_AT, OffsetDateTime.now())
            .where(WORKFLOWS.ID.eq(workflowId))
            .and(WORKFLOWS.VERSION.eq(expectedVersion))
            .and(WORKFLOWS.DELETED_AT.isNull)
            .execute() > 0

    /**
     * 살아 있는 전역 카탈로그의 상태 키 → id·이름·카테고리.
     *
     * id 만이 아니라 **이름과 카테고리도 함께** 읽는다. 초안이 그 둘을 담고 있는데 발행은 쓰지
     * 않으므로(편성 INSERT 는 id 와 순서만 쓴다), 대조하지 않으면 관리자가 초안에서 고친 이름이
     * 발행에서 조용히 버려지고 **감사 스냅샷에만 남는다.** 대조하려면 카탈로그 값이 필요하다.
     */
    fun findCatalogStatuses(keys: Collection<String>): Map<String, CatalogStatus> {
        if (keys.isEmpty()) return emptyMap()
        return dsl.select(STATUSES.KEY, STATUSES.ID, STATUSES.NAME, STATUSES.CATEGORY)
            .from(STATUSES)
            .where(STATUSES.KEY.`in`(keys))
            .and(STATUSES.DELETED_AT.isNull)
            .fetch()
            .associate {
                it[STATUSES.KEY]!! to
                    CatalogStatus(
                        id = it[STATUSES.ID]!!,
                        name = it[STATUSES.NAME] ?: "",
                        category = it[STATUSES.CATEGORY] ?: "",
                    )
            }
    }

    /**
     * 지금 이 워크플로우에 편성된 상태 키 전량.
     *
     * ### 왜 캐시가 아니라 여기서 읽나
     * 이관 필요 판정의 근거다 — 「빠지는 상태」는 **현재 편성 − 초안** 이고, 그 좌변이 실제보다
     * 작으면 이슈가 남은 상태가 차집합에서 빠져 발행이 그냥 통과한다.
     * [com.bts.workflow.cache.WorkflowCache] 는 무효화를 **커밋 전**에 하고 읽기 경로가 락을
     * 잡지 않아, 무효화와 커밋 사이에 들어온 리더가 옛 정의를 다시 올려 놓을 수 있다.
     * 판정의 근거는 그 창의 영향을 받지 않는 DB 여야 한다.
     */
    fun findComposedStatusKeys(workflowId: UUID): Set<String> =
        dsl.select(STATUSES.KEY)
            .from(WORKFLOW_STATUSES)
            .join(STATUSES).on(STATUSES.ID.eq(WORKFLOW_STATUSES.STATUS_ID))
            .where(WORKFLOW_STATUSES.WORKFLOW_ID.eq(workflowId))
            .and(STATUSES.DELETED_AT.isNull)
            .fetch()
            .mapNotNull { it[STATUSES.KEY] }
            .toSet()

    /**
     * 워크플로우의 이름·설명과 상태·전환 구성을 초안대로 갈아 끼운다.
     *
     * 규칙(validator·post-action)은 전환에 매달린 FK `ON DELETE CASCADE` 라 전환을 지울 때 함께
     * 사라진다. 재삽입은 여기서 하지 않고 호출부가 기존 `ValidatorRepository`·`PostActionRepository`
     * 로 처리한다 — 규칙 INSERT 는 이미 `TransitionRuleRepository` 한 구현이 담당하고 있고,
     * 같은 SQL 을 여기 한 벌 더 두면 config JSONB 직렬화 규칙이 두 곳으로 갈린다.
     *
     * @param statusIds 초안의 상태 키 → 전역 카탈로그 id. 호출부가 [findCatalogStatuses] 로 미리 채운다.
     * @return 초안의 전환 순번 → 새로 생긴 `workflow_transitions.id`. 규칙 삽입이 이 맵을 쓴다.
     */
    @Transactional
    fun replaceDefinition(
        workflowId: UUID,
        definition: WorkflowDraftDefinition,
        statusIds: Map<String, UUID>,
    ): Map<Int, UUID> {
        dsl.update(WORKFLOWS)
            .set(WORKFLOWS.NAME, definition.name)
            .set(WORKFLOWS.DESCRIPTION, definition.description)
            .where(WORKFLOWS.ID.eq(workflowId))
            .execute()

        // 전환을 먼저 지운다 — workflow_statuses 를 지우려면 그것을 가리키는 전환이 먼저 없어야 한다.
        dsl.deleteFrom(WORKFLOW_TRANSITIONS).where(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(workflowId)).execute()
        dsl.deleteFrom(WORKFLOW_STATUSES).where(WORKFLOW_STATUSES.WORKFLOW_ID.eq(workflowId)).execute()

        val compositionIds = insertStatuses(workflowId, definition, statusIds)
        return insertTransitions(workflowId, definition, compositionIds)
    }

    /**
     * 상태 편성을 심고 상태 키 → `workflow_statuses.id` 를 돌려준다. 전환이 이 id 를 가리킨다.
     *
     * ### 다이어그램 좌표를 함께 싣는다
     * 발행은 전량 교체라 편성 행이 통째로 새로 생긴다. 좌표를 안 실으면 그 순간 NULL 이 되고,
     * 사용자가 편집기에서 배치해 둔 다이어그램이 **발행할 때마다** 초기화된다.
     */
    private fun insertStatuses(
        workflowId: UUID,
        definition: WorkflowDraftDefinition,
        statusIds: Map<String, UUID>,
    ): Map<String, UUID> =
        definition.states.associate { state ->
            val statusId =
                statusIds[state.key]
                    ?: error("상태 '${state.key}' 가 전역 카탈로그에 없다. 호출부가 먼저 검증해야 한다")
            val compositionId =
                dsl.insertInto(WORKFLOW_STATUSES)
                    .set(WORKFLOW_STATUSES.WORKFLOW_ID, workflowId)
                    .set(WORKFLOW_STATUSES.STATUS_ID, statusId)
                    .set(WORKFLOW_STATUSES.DISPLAY_ORDER, state.displayOrder)
                    // 컬럼이 REAL(float4)이라 Double 을 그대로 넣을 수 없다. 좁힘을 조용히
                    // 맡기지 않고 여기서 명시한다 — null 은 「자동 배치」로 그대로 보존된다.
                    .set(WORKFLOW_STATUSES.LAYOUT_X, state.layoutX?.toFloat())
                    .set(WORKFLOW_STATUSES.LAYOUT_Y, state.layoutY?.toFloat())
                    .returning(WORKFLOW_STATUSES.ID)
                    .fetchOne(WORKFLOW_STATUSES.ID)
                    ?: error("workflow_statuses INSERT 가 id 를 돌려주지 않았다")
            state.key to compositionId
        }

    /**
     * 전환을 심는다.
     *
     * ★ 구 컬럼(`from_state_id`·`to_state_id`)을 채우지 않는다. 읽기가 신 컬럼 우선 · 구 컬럼
     * 폴백이라(`WorkflowRepository`) 구 컬럼에 값이 남으면 GLOBAL·INITIAL 전환에서 출발지가
     * 되살아나고, 그 행은 `Workflow.of()` invariant 5 를 깨 워크플로우 **전체** 조회가 죽는다.
     * 전량 교체라 애초에 지우고 새로 심으므로 여기서는 값을 넣지 않는 것으로 충분하다.
     */
    private fun insertTransitions(
        workflowId: UUID,
        definition: WorkflowDraftDefinition,
        compositionIds: Map<String, UUID>,
    ): Map<Int, UUID> =
        definition.transitions.withIndex().associate { (index, transition) ->
            val toId =
                compositionIds[transition.to]
                    ?: error("전환 '${transition.name}' 의 도착 상태 '${transition.to}' 가 편성에 없다")
            val fromId = transition.from?.let { compositionIds[it] }
            val id =
                dsl.insertInto(WORKFLOW_TRANSITIONS)
                    .set(WORKFLOW_TRANSITIONS.WORKFLOW_ID, workflowId)
                    .set(WORKFLOW_TRANSITIONS.KIND, transition.kind)
                    .set(WORKFLOW_TRANSITIONS.NAME, transition.name)
                    .set(WORKFLOW_TRANSITIONS.FROM_STATUS_ID, fromId)
                    .set(WORKFLOW_TRANSITIONS.TO_STATUS_ID, toId)
                    .set(WORKFLOW_TRANSITIONS.DISPLAY_ORDER, index)
                    .returning(WORKFLOW_TRANSITIONS.ID)
                    .fetchOne(WORKFLOW_TRANSITIONS.ID)
                    ?: error("workflow_transitions INSERT 가 id 를 돌려주지 않았다")
            index to id
        }
}

/**
 * 워크플로우의 식별자와 낙관적 락 버전, 그리고 출처.
 *
 * @property id `workflows.id`.
 * @property version `workflows.version`. 발행 요청의 `baseVersion` 과 대조하는 값이다.
 * @property origin `SEED` 면 YAML 기본값이 있어 「기본값으로 복원」이 가능하다. 사용자 생성분은 `CUSTOM`
 *   이고 되돌릴 기준이 없다 (ADR 2026-08-18-workflow-db-as-source-of-truth §D3).
 */
data class WorkflowVersionRow(
    val id: UUID,
    val version: Long,
    val origin: String,
)

/**
 * 전역 카탈로그(`statuses`)의 한 행 중 발행이 필요로 하는 것.
 *
 * @property id 편성 INSERT 가 가리킬 `statuses.id`.
 * @property name 카탈로그가 정한 이름. 초안이 다른 값을 담으면 발행이 거절한다.
 * @property category 카탈로그가 정한 카테고리. 위와 같다.
 */
data class CatalogStatus(
    val id: UUID,
    val name: String,
    val category: String,
)

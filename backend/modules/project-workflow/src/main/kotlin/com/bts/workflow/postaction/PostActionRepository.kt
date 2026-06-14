// 워크플로우 전이별 post-action CRUD jOOQ 리포지토리

package com.bts.workflow.postaction

import com.bts.workflow.jooq.tables.WorkflowPostActions.Companion.WORKFLOW_POST_ACTIONS
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * workflow_post_actions 테이블 CRUD 리포지토리.
 *
 * 전이(transition_id) 기준 post-action 행을 조회·삽입·수정·삭제한다.
 * config 는 JSONB 컬럼으로, [ObjectMapper] 로 직렬화/역직렬화한다.
 *
 * @param dsl jOOQ DSLContext. SQL 안전 바인딩(?-파라미터)에 사용.
 * @param objectMapper config Map ↔ JSON 변환용 Jackson ObjectMapper.
 */
@Repository
class PostActionRepository(
    private val dsl: DSLContext,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapTypeRef = object : TypeReference<Map<String, Any?>>() {}

    /**
     * 전이 ID 에 속한 post-action 목록을 display_order ASC 순으로 반환한다.
     *
     * @param transitionId 조회할 전이의 UUID.
     * @return [PostActionRow] 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findByTransitionId(transitionId: UUID): List<PostActionRow> {
        log.debug("PostActionRepository.findByTransitionId transitionId={}", transitionId)
        return dsl
            .select(
                WORKFLOW_POST_ACTIONS.ID,
                WORKFLOW_POST_ACTIONS.TRANSITION_ID,
                WORKFLOW_POST_ACTIONS.TYPE,
                WORKFLOW_POST_ACTIONS.CONFIG,
                WORKFLOW_POST_ACTIONS.DISPLAY_ORDER,
            )
            .from(WORKFLOW_POST_ACTIONS)
            .where(WORKFLOW_POST_ACTIONS.TRANSITION_ID.eq(transitionId))
            .orderBy(WORKFLOW_POST_ACTIONS.DISPLAY_ORDER.asc())
            .fetch()
            .map { record ->
                val id =
                    record.get(WORKFLOW_POST_ACTIONS.ID)
                        ?: error("workflow_post_actions.id null — transitionId=$transitionId")
                val transitionId =
                    record.get(WORKFLOW_POST_ACTIONS.TRANSITION_ID)
                        ?: error("workflow_post_actions.transition_id null")
                val type =
                    record.get(WORKFLOW_POST_ACTIONS.TYPE)
                        ?: error("workflow_post_actions.type null")
                PostActionRow(
                    id = id,
                    transitionId = transitionId,
                    type = type,
                    config = parseJsonb(record.get(WORKFLOW_POST_ACTIONS.CONFIG)),
                    displayOrder = record.get(WORKFLOW_POST_ACTIONS.DISPLAY_ORDER) ?: 0,
                )
            }
    }

    /**
     * post-action 행을 삽입하고 삽입된 행을 반환한다.
     *
     * @param transitionId 소속 전이 UUID.
     * @param type post-action 타입 식별자 (예: "CALL_WEBHOOK").
     * @param config 타입별 설정 Map.
     * @param displayOrder UI 표시 순서.
     * @return 삽입된 [PostActionRow].
     */
    @Transactional
    fun insert(
        transitionId: UUID,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): PostActionRow {
        val configJson = objectMapper.writeValueAsString(config)
        val id =
            dsl.insertInto(WORKFLOW_POST_ACTIONS)
                .set(WORKFLOW_POST_ACTIONS.TRANSITION_ID, transitionId)
                .set(WORKFLOW_POST_ACTIONS.TYPE, type)
                .set(WORKFLOW_POST_ACTIONS.CONFIG, JSONB.valueOf(configJson))
                .set(WORKFLOW_POST_ACTIONS.DISPLAY_ORDER, displayOrder)
                .returningResult(WORKFLOW_POST_ACTIONS.ID)
                .fetchOne()
                ?.value1()
                ?: error("workflow_post_actions INSERT 실패 — transitionId=$transitionId type=$type")

        log.debug("PostActionRepository.insert id={} transitionId={} type={}", id, transitionId, type)
        return PostActionRow(
            id = id,
            transitionId = transitionId,
            type = type,
            config = config,
            displayOrder = displayOrder,
        )
    }

    /**
     * post-action 행을 수정하고 수정된 행을 반환한다.
     *
     * @param id 수정할 post-action UUID.
     * @param type 변경할 타입.
     * @param config 변경할 config Map.
     * @param displayOrder 변경할 displayOrder.
     * @return 수정된 [PostActionRow].
     * @throws IllegalStateException 해당 id 가 존재하지 않을 때.
     */
    @Transactional
    fun update(
        id: UUID,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): PostActionRow {
        val configJson = objectMapper.writeValueAsString(config)
        val updated =
            dsl.update(WORKFLOW_POST_ACTIONS)
                .set(WORKFLOW_POST_ACTIONS.TYPE, type)
                .set(WORKFLOW_POST_ACTIONS.CONFIG, JSONB.valueOf(configJson))
                .set(WORKFLOW_POST_ACTIONS.DISPLAY_ORDER, displayOrder)
                .where(WORKFLOW_POST_ACTIONS.ID.eq(id))
                .returningResult(WORKFLOW_POST_ACTIONS.TRANSITION_ID)
                .fetchOne()
                ?: error("workflow_post_actions UPDATE 실패 — id=$id 가 존재하지 않음")

        val transitionId =
            updated.value1()
                ?: error("workflow_post_actions.transition_id null after UPDATE")

        log.debug("PostActionRepository.update id={} type={}", id, type)
        return PostActionRow(
            id = id,
            transitionId = transitionId,
            type = type,
            config = config,
            displayOrder = displayOrder,
        )
    }

    /**
     * post-action 행을 삭제한다. 존재하지 않는 id 는 no-op.
     *
     * @param id 삭제할 post-action UUID.
     */
    @Transactional
    fun deleteById(id: UUID) {
        log.debug("PostActionRepository.deleteById id={}", id)
        dsl.deleteFrom(WORKFLOW_POST_ACTIONS)
            .where(WORKFLOW_POST_ACTIONS.ID.eq(id))
            .execute()
    }

    // ── private ──────────────────────────────────────────────────────────────

    /** JSONB 컬럼 값을 Map 으로 역직렬화한다. null 또는 빈 값이면 빈 Map 반환. */
    private fun parseJsonb(jsonb: JSONB?): Map<String, Any?> {
        val raw = jsonb?.data()?.takeIf { it.isNotBlank() } ?: return emptyMap()
        return try {
            objectMapper.readValue(raw, mapTypeRef)
        } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
            log.warn("PostActionRepository: JSONB 파싱 실패 raw='{}' error={}", raw, ex.message)
            emptyMap()
        }
    }
}

/**
 * workflow_post_actions 행 데이터 클래스.
 *
 * @property id 행 UUID PK.
 * @property transitionId 소속 전이 UUID FK.
 * @property type post-action 타입 식별자.
 * @property config 타입별 설정 Map.
 * @property displayOrder UI 표시 순서.
 */
data class PostActionRow(
    val id: UUID,
    val transitionId: UUID,
    val type: String,
    val config: Map<String, Any?>,
    val displayOrder: Int,
)

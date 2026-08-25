// 워크플로우 전환별 post-action CRUD jOOQ 리포지토리

package com.bts.workflow.postaction

import com.bts.workflow.jooq.tables.WorkflowPostActions.Companion.WORKFLOW_POST_ACTIONS
import com.bts.workflow.transition.TransitionRuleRepository
import com.bts.workflow.transition.TransitionRuleRow
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * workflow_post_actions 행 타입.
 *
 * 전환 규칙 2종(validator · post-action)의 컬럼이 같아 행 타입은 [TransitionRuleRow] 하나를 공유하고,
 * post-action 쪽 이름만 별칭으로 붙인다. 필드 이름·순서·타입이 그대로라 서비스·DTO·테스트 호출부가
 * 한 줄도 바뀌지 않는다.
 */
typealias PostActionRow = TransitionRuleRow

/**
 * workflow_post_actions 테이블 CRUD 리포지토리.
 *
 * 전환(transition_id) 기준 post-action 행을 조회·삽입·수정·삭제한다.
 * CRUD 구현과 config JSONB 직렬화는 [TransitionRuleRepository] 가 갖고, 여기서는 대상 테이블·컬럼만 지정한다.
 *
 * **트랜잭션 경계는 이 클래스가 갖는다.** 기반 클래스는 Spring Bean 이 아니라서 어드바이스가 붙지 않고,
 * ArchUnit 룰 1 도 `@Transactional` 메서드를 가진 구체 클래스에 stereotype 을 요구한다.
 * 그래서 CRUD 4종을 `override` 로 얹고 애노테이션만 여기서 선언한 뒤 구현은 `super` 에 위임한다.
 *
 * @param dsl jOOQ DSLContext. SQL 안전 바인딩(?-파라미터)에 사용.
 * @param objectMapper config Map ↔ JSON 변환용 Jackson ObjectMapper.
 */
@Repository
class PostActionRepository(
    dsl: DSLContext,
    objectMapper: ObjectMapper,
) : TransitionRuleRepository(
        dsl,
        objectMapper,
        WORKFLOW_POST_ACTIONS,
        WORKFLOW_POST_ACTIONS.ID,
        WORKFLOW_POST_ACTIONS.TRANSITION_ID,
        WORKFLOW_POST_ACTIONS.TYPE,
        WORKFLOW_POST_ACTIONS.CONFIG,
        WORKFLOW_POST_ACTIONS.DISPLAY_ORDER,
    ) {
    /**
     * 전환 ID 에 속한 post-action 목록을 display_order ASC 순으로 반환한다.
     *
     * @param transitionId 조회할 전환의 UUID.
     * @return [PostActionRow] 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    override fun findByTransitionId(transitionId: UUID): List<PostActionRow> = super.findByTransitionId(transitionId)

    /**
     * post-action 행을 삽입하고 삽입된 행을 반환한다.
     *
     * @param transitionId 소속 전환 UUID.
     * @param type post-action 타입 식별자 (예: "CALL_WEBHOOK").
     * @param config 타입별 설정 Map.
     * @param displayOrder UI 표시 순서.
     * @return 삽입된 [PostActionRow].
     */
    @Transactional
    override fun insert(
        transitionId: UUID,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): PostActionRow = super.insert(transitionId, type, config, displayOrder)

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
    override fun update(
        id: UUID,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): PostActionRow = super.update(id, type, config, displayOrder)

    /**
     * post-action 행을 삭제한다. 존재하지 않는 id 는 no-op.
     *
     * @param id 삭제할 post-action UUID.
     */
    @Transactional
    override fun deleteById(id: UUID) {
        super.deleteById(id)
    }
}

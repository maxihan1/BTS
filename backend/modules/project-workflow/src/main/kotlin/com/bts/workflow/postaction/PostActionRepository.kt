// 워크플로우 전환별 post-action CRUD jOOQ 리포지토리

package com.bts.workflow.postaction

import com.bts.workflow.jooq.tables.WorkflowPostActions.Companion.WORKFLOW_POST_ACTIONS
import com.bts.workflow.transition.TransitionRuleRepository
import com.bts.workflow.transition.TransitionRuleRow
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

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
    )

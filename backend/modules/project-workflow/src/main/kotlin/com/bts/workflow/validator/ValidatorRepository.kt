// 워크플로우 전환별 validator CRUD jOOQ 리포지토리

package com.bts.workflow.validator

import com.bts.workflow.jooq.tables.WorkflowValidators.Companion.WORKFLOW_VALIDATORS
import com.bts.workflow.transition.TransitionRuleRepository
import com.bts.workflow.transition.TransitionRuleRow
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * workflow_validators 행 타입.
 *
 * 전환 규칙 2종(validator · post-action)의 컬럼이 같아 행 타입은 [TransitionRuleRow] 하나를 공유하고,
 * validator 쪽 이름만 별칭으로 붙인다. 형제인 `PostActionRow` 와 같은 관례다.
 */
typealias ValidatorRow = TransitionRuleRow

/**
 * workflow_validators 테이블 CRUD 리포지토리.
 *
 * 전환(transition_id) 기준 validator 행을 조회·삽입·수정·삭제한다.
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
class ValidatorRepository(
    dsl: DSLContext,
    objectMapper: ObjectMapper,
) : TransitionRuleRepository(
        dsl,
        objectMapper,
        WORKFLOW_VALIDATORS,
        WORKFLOW_VALIDATORS.ID,
        WORKFLOW_VALIDATORS.TRANSITION_ID,
        WORKFLOW_VALIDATORS.TYPE,
        WORKFLOW_VALIDATORS.CONFIG,
        WORKFLOW_VALIDATORS.DISPLAY_ORDER,
    ) {
    /**
     * 전환 ID 에 속한 validator 목록을 display_order ASC 순으로 반환한다.
     *
     * @param transitionId 조회할 전환의 UUID.
     * @return [ValidatorRow] 목록. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    override fun findByTransitionId(transitionId: UUID): List<ValidatorRow> = super.findByTransitionId(transitionId)

    /**
     * validator 행을 삽입하고 삽입된 행을 반환한다.
     *
     * @param transitionId 소속 전환 UUID.
     * @param type validator 타입 식별자 (예: "RequiredField").
     * @param config 타입별 설정 Map.
     * @param displayOrder UI 표시 순서.
     * @return 삽입된 [ValidatorRow].
     */
    @Transactional
    override fun insert(
        transitionId: UUID,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): ValidatorRow = super.insert(transitionId, type, config, displayOrder)

    /**
     * validator 행을 수정하고 수정된 행을 반환한다.
     *
     * @param id 수정할 validator UUID.
     * @param type 변경할 타입.
     * @param config 변경할 config Map.
     * @param displayOrder 변경할 displayOrder.
     * @return 수정된 [ValidatorRow].
     * @throws IllegalStateException 해당 id 가 존재하지 않을 때.
     */
    @Transactional
    override fun update(
        id: UUID,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): ValidatorRow = super.update(id, type, config, displayOrder)

    /**
     * validator 행을 삭제한다. 존재하지 않는 id 는 no-op.
     *
     * @param id 삭제할 validator UUID.
     */
    @Transactional
    override fun deleteById(id: UUID) {
        super.deleteById(id)
    }
}

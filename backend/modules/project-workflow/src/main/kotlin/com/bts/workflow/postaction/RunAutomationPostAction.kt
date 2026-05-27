// 자동화 실행 PostAction — DomainEvent("AutomationRequested") (automation BC 구독)

package com.bts.workflow.postaction

import com.bts.shared.workflow.DomainEvent
import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.WorkflowPostAction

/**
 * 자동화 트리거 PostAction 구현체.
 *
 * 전이(Transition) 완료 시 automation BC 가 구독하는 [DomainEvent]("AutomationRequested") 를
 * 발행 예약한다. 이벤트 실제 발행(pgmq outbox INSERT)은 이 plan 을 수신한 호출자 BC 가 수행한다.
 *
 * ## payload 구성
 * - `issueKey` — 전이 대상 이슈 키 (ctx.request.issueKey)
 * - `automationKey` — 실행할 자동화 규칙 식별자 (생성자 파라미터)
 *
 * ## 제약
 * [automationKey] 는 빈 문자열이 될 수 없다. 생성 시 [IllegalArgumentException] 을 던진다.
 *
 * 참조. FR-WF-01 / docs/sdd/ §project-workflow / §automation
 *
 * @param automationKey 실행할 자동화 규칙의 고유 식별자. 빈 문자열 불가.
 */
class RunAutomationPostAction(
    private val automationKey: String,
) : WorkflowPostAction {
    init {
        require(automationKey.isNotEmpty()) { "automationKey 는 빈 문자열이 될 수 없습니다." }
    }

    override val type: String = "RUN_AUTOMATION"

    /**
     * automation BC 가 구독할 [DomainEvent]("AutomationRequested") 1건을 담은 계획을 반환한다.
     *
     * 필드 변경은 없으므로 [PostActionPlan.fieldChanges] 는 빈 리스트다.
     *
     * @param ctx 전이 실행 시점의 읽기 전용 컨텍스트.
     * @return fieldChanges 빈 리스트 + emitEvents 1건 (type="AutomationRequested", payload=issueKey+automationKey).
     */
    override fun evaluate(ctx: TransitionContext): PostActionPlan {
        val event =
            DomainEvent(
                type = "AutomationRequested",
                payload =
                    mapOf(
                        "issueKey" to ctx.request.issueKey,
                        "automationKey" to automationKey,
                    ),
            )
        return PostActionPlan(
            fieldChanges = emptyList(),
            emitEvents = listOf(event),
        )
    }
}

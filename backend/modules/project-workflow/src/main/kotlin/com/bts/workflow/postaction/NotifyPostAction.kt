// 알림 PostAction — DomainEvent("NotificationRequested") (notification BC 구독)

package com.bts.workflow.postaction

import com.bts.shared.workflow.DomainEvent
import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.WorkflowPostAction

/**
 * 전이(Transition) 완료 시 알림을 예약하는 PostAction 구현체.
 *
 * [evaluate] 는 [DomainEvent]("NotificationRequested") 1건을 [PostActionPlan.emitEvents] 에 담아 반환한다.
 * 실제 알림 발송(이메일·슬랙 등)은 이 이벤트를 구독하는 notification BC 가 수행한다.
 * 이 구현체는 인프라(DB / 메시지 큐)에 직접 의존하지 않는다.
 *
 * ## YAML 워크플로우 정의 예시
 * ```yaml
 * post_actions:
 *   - type: NOTIFY
 *     channel: slack
 *     recipients: "team-dev"
 * ```
 *
 * @param channel 알림 채널 식별자. 예: "slack", "email". blank 이면 생성 시 예외.
 * @param recipients 수신 대상 식별자. 예: "team-dev", "assignee@example.com".
 */
class NotifyPostAction(
    private val channel: String,
    private val recipients: String,
) : WorkflowPostAction {
    init {
        require(channel.isNotBlank()) { "channel 은 blank 일 수 없습니다." }
    }

    override val type: String = "NOTIFY"

    /**
     * 전이 컨텍스트를 받아 NotificationRequested 이벤트 1건을 계산한다.
     *
     * payload 구성.
     * - `issueKey`: 전이 대상 이슈 키 (예: "BTS-42")
     * - `channel`: 알림 채널 (예: "slack")
     * - `recipients`: 수신 대상 (예: "team-dev")
     *
     * @param ctx 전이 시점의 컨텍스트.
     * @return fieldChanges 빈 리스트 + emitEvents 1건([DomainEvent]("NotificationRequested")).
     */
    override fun evaluate(ctx: TransitionContext): PostActionPlan {
        val event =
            DomainEvent(
                type = "NotificationRequested",
                payload =
                    mapOf(
                        "issueKey" to ctx.request.issueKey,
                        "channel" to channel,
                        "recipients" to recipients,
                    ),
            )
        return PostActionPlan(
            fieldChanges = emptyList(),
            emitEvents = listOf(event),
        )
    }
}

// 웹훅 호출 PostAction — DomainEvent("WebhookRequested") (실제 HTTP 호출은 후속 BC)

package com.bts.workflow.postaction

import com.bts.workflow.domain.dto.DomainEvent
import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.WorkflowPostAction

/**
 * 웹훅 호출을 예약하는 PostAction 구현체.
 *
 * ## config 평가만 — 실행은 후속 BC
 * 이 구현체는 HTTP 요청을 직접 전송하지 않는다.
 * 웹훅 config(`url`, `method`)와 이슈 컨텍스트를 묶어
 * [DomainEvent]("WebhookRequested") 1건을 [PostActionPlan.emitEvents]에 담아 반환한다.
 * 실제 HTTP 디스패치는 이 plan 을 수신한 후속 BC(예. notification, automation)가 수행한다.
 *
 * ## 제약
 * [url] 은 빈 문자열이 될 수 없다. 생성 시 [IllegalArgumentException] 을 던진다.
 *
 * @param url 웹훅 엔드포인트 URL. 빈 문자열 불가.
 * @param method HTTP 메서드. 예: "POST", "PUT".
 */
class CallWebhookPostAction(
    private val url: String,
    private val method: String,
) : WorkflowPostAction {

    init {
        require(url.isNotEmpty()) { "url 은 빈 문자열이 될 수 없습니다." }
    }

    override val type: String = "CALL_WEBHOOK"

    /**
     * 전이 컨텍스트를 바탕으로 WebhookRequested 이벤트 1건을 반환한다.
     *
     * payload 구성.
     * - `issueKey` — ctx.request.issueKey
     * - `url` — 구성된 웹훅 URL
     * - `method` — 구성된 HTTP 메서드
     *
     * @param ctx 전이 실행 시점의 읽기 전용 컨텍스트.
     * @return fieldChanges 빈 리스트 + emitEvents 1건 (WebhookRequested).
     */
    override fun evaluate(ctx: TransitionContext): PostActionPlan {
        val event = DomainEvent(
            type = "WebhookRequested",
            payload = mapOf(
                "issueKey" to ctx.request.issueKey,
                "url" to url,
                "method" to method,
            ),
        )
        return PostActionPlan(
            fieldChanges = emptyList(),
            emitEvents = listOf(event),
        )
    }
}

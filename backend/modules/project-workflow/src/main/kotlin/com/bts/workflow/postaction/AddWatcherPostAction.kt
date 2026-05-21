// 워처 추가 PostAction — DomainEvent("WatcherAdded") emitEvents 1건 (호출자 outbox)

package com.bts.workflow.postaction

import com.bts.workflow.domain.dto.DomainEvent
import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.WorkflowPostAction

/**
 * 워크플로우 전이(Transition) 후 이슈에 워처(Watcher — 이슈 변경 알림을 받는 구독자)를
 * 추가하도록 요청하는 PostAction 구현체.
 *
 * ## 동작 방식
 * - `watcher` 값이 `${actor}` 플레이스홀더이면 전이를 실행한 액터의 userId 로 치환한다.
 * - 그 외의 값은 고정 사용자 ID 로 사용한다.
 * - [DomainEvent] `"WatcherAdded"` 1건을 [PostActionPlan.emitEvents] 에 담아 반환한다.
 * - 실제 outbox INSERT(PostgreSQL 기반 메시지 큐인 pgmq로의 발행)는 이 값을 수신한 호출자 BC 가 수행한다.
 *
 * ## 설정 예 (YAML 워크플로우 정의)
 * ```yaml
 * post_actions:
 *   - type: ADD_WATCHER
 *     config:
 *       watcher: "${actor}"   # 전이 실행자를 워처로 추가
 * ```
 *
 * @param watcher 워처로 추가할 대상. 고정 userId 또는 `${actor}` 플레이스홀더.
 *   빈 문자열 또는 공백 전용 값은 허용하지 않는다.
 */
class AddWatcherPostAction(private val watcher: String) : WorkflowPostAction {
    init {
        require(watcher.isNotBlank()) { "watcher 는 빈 문자열이나 공백일 수 없습니다." }
    }

    override val type: String = "ADD_WATCHER"

    /**
     * 전이 컨텍스트를 바탕으로 워처 추가 이벤트 계획을 계산한다.
     *
     * @param ctx 전이 시점의 컨텍스트 (이슈 상태, 액터 등)
     * @return [PostActionPlan] — fieldChanges 는 항상 비어 있고, emitEvents 에 [DomainEvent] 1건 포함.
     */
    override fun evaluate(ctx: TransitionContext): PostActionPlan {
        val resolvedWatcher = if (watcher == "\${actor}") ctx.actorView.userId else watcher
        val event =
            DomainEvent(
                type = "WatcherAdded",
                payload =
                    mapOf(
                        "issueKey" to ctx.request.issueKey,
                        "watcher" to resolvedWatcher,
                    ),
            )
        return PostActionPlan(
            fieldChanges = emptyList(),
            emitEvents = listOf(event),
        )
    }
}

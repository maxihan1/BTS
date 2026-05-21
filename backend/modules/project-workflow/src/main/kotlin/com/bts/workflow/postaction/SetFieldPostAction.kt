// 필드 자동 채움 PostAction — fieldChanges 1건 반환 (실행은 호출자 BC)

package com.bts.workflow.postaction

import com.bts.workflow.domain.dto.FieldChange
import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.WorkflowPostAction
import java.time.Instant

/**
 * 지정된 이슈 필드를 고정 값(또는 placeholder)으로 채우는 PostAction 구현체.
 *
 * ## 동작
 * 1. [ctx]의 `request.issueFields`에서 [field]의 현재 값(oldValue)을 읽는다.
 *    필드가 없으면 oldValue = null.
 * 2. [value]에 포함된 placeholder 를 치환한다.
 *    - `${now}` → [Instant.now()].toString()
 *    - `${actor}` → ctx.request.actorId
 *    - 그 외 placeholder 는 치환 없이 그대로 사용한다.
 * 3. [PostActionPlan]을 반환한다 — fieldChanges 1건, emitEvents 0건.
 *    실제 필드 저장은 이 plan 을 수신한 호출자 BC 가 수행한다.
 *
 * ## 제약
 * [field] 는 빈 문자열이 될 수 없다. 생성 시 [IllegalArgumentException] 을 던진다.
 *
 * @param field 변경할 이슈 필드 이름. 빈 문자열 불가.
 * @param value 설정할 새 값. null 허용. 문자열일 경우 placeholder 치환이 적용된다.
 */
class SetFieldPostAction(
    private val field: String,
    private val value: Any?,
) : WorkflowPostAction {
    init {
        require(field.isNotEmpty()) { "field 는 빈 문자열이 될 수 없습니다." }
    }

    override val type: String = "SET_FIELD"

    /**
     * 전이 컨텍스트를 바탕으로 필드 변경 계획 1건을 반환한다.
     *
     * @param ctx 전이 실행 시점의 읽기 전용 컨텍스트.
     * @return fieldChanges 1건 (field, oldValue, newValue) + emitEvents 빈 리스트.
     */
    override fun evaluate(ctx: TransitionContext): PostActionPlan {
        val oldValue = ctx.request.issueFields[field]
        val newValue = resolvePlaceholders(value, ctx)
        return PostActionPlan(
            fieldChanges = listOf(FieldChange(field = field, oldValue = oldValue, newValue = newValue)),
            emitEvents = emptyList(),
        )
    }

    /**
     * [raw] 값이 문자열이면 placeholder 를 치환한다. 그 외 타입은 그대로 반환한다.
     *
     * 지원 placeholder.
     * - `${now}` → [Instant.now()].toString() (ISO-8601 UTC)
     * - `${actor}` → ctx.request.actorId
     */
    private fun resolvePlaceholders(
        raw: Any?,
        ctx: TransitionContext,
    ): Any? {
        if (raw !is String) return raw
        return raw
            .replace("\${now}", Instant.now().toString())
            .replace("\${actor}", ctx.request.actorId)
    }
}

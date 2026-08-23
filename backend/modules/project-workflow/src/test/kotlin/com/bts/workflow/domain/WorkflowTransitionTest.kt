// WorkflowTransition 의 key 계산 프로퍼티 — 하위호환 + URL 경로에 안전한 토큰

package com.bts.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * FR-WF-05 — 전환 ID 도입 후에도 `key` 계산 프로퍼티가 하위호환을 지키는지 검증한다.
 *
 * `key` 는 URL 경로 세그먼트로 소비된다 (`.../transitions/{transitionKey}/post-actions`).
 * 따라서 GLOBAL·INITIAL 처럼 출발 상태가 없는 전환도 **경로 문자로 안전한 토큰**을 써야 한다
 * (plan 리뷰 T4 — `*` 는 인코딩·매칭이 갈린다).
 */
class WorkflowTransitionTest {
    /** URL 경로 세그먼트로 그대로 실릴 수 있는 문자만 허용한다. */
    private val urlSafeSegment = Regex("[A-Za-z0-9_-]+")

    private fun transition(
        from: String?,
        to: String,
        name: String,
        kind: TransitionKind = TransitionKind.NORMAL,
    ) = WorkflowTransition(
        id = UUID.randomUUID(),
        fromStateKey = from,
        toStateKey = to,
        name = name,
        kind = kind,
    )

    @Test
    fun `key 계산 프로퍼티는 그대로 from__to 를 낸다`() {
        val normal = transition(from = "open", to = "done", name = "완료")

        assertThat(normal.key).isEqualTo("open__done")
        assertThat(normal.key).matches(urlSafeSegment.pattern)
    }

    @Test
    fun `GLOBAL 전환의 key 는 URL 안전 토큰 GLOBAL__to 다`() {
        val global = transition(from = null, to = "done", name = "긴급 완료", kind = TransitionKind.GLOBAL)

        assertThat(global.key).isEqualTo("GLOBAL__done")
        assertThat(global.key).doesNotContain("*")
        assertThat(global.key).matches(urlSafeSegment.pattern)
    }

    @Test
    fun `INITIAL 전환의 key 는 URL 안전 토큰 INITIAL__to 다`() {
        val initial = transition(from = null, to = "open", name = "생성", kind = TransitionKind.INITIAL)

        assertThat(initial.key).isEqualTo("INITIAL__open")
        assertThat(initial.key).doesNotContain("*")
        assertThat(initial.key).matches(urlSafeSegment.pattern)
    }
}

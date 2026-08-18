// availableTransitions — 어댑터가 WorkflowEngine 에 위임하고 결과를 그대로 반환하는지 단위 검증

package com.bts.workflow.adapter.inbound

import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.workflow.engine.WorkflowEngine
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [WorkflowTransitionAdapter.availableTransitions] 단위 테스트.
 *
 * [WorkflowEngine] 을 MockK 로 대체하여 Spring 컨텍스트 없이 실행한다.
 * 어댑터의 책임은 엔진에 위임하고 결과를 그대로 반환하는 것뿐이므로
 * 실질 로직(enumerate + validator 평가)은 [com.bts.workflow.engine.WorkflowEngineAvailableTransitionsTest] 에서 검증한다.
 *
 * 테스트 시나리오.
 * - S1. 엔진이 Success 반환 → 어댑터가 동일한 Success 를 반환하고 엔진을 1회 호출한다.
 * - S2. 엔진이 WorkflowNotFound 반환 → 어댑터가 동일한 WorkflowNotFound 를 반환한다.
 * - S3. 어댑터는 엔진 호출만 하며 직접 캐시/레포/팩토리를 사용하지 않는다 (위임 계약 검증).
 */
class WorkflowTransitionAdapterAvailableTest {
    private val mockEngine: WorkflowEngine = mockk()
    private lateinit var adapter: WorkflowTransitionAdapter

    private val baseRequest =
        AvailableTransitionsRequest(
            workflowKey = "software-default",
            fromStateKey = "open",
            issueKey = "ATLAS-1",
            actorId = "user-001",
            actorRoles = setOf("MEMBER"),
            issueFields = emptyMap(),
        )

    @BeforeEach
    fun setUp() {
        adapter = WorkflowTransitionAdapter(mockEngine)
    }

    // ── S1. 엔진이 Success 반환 → 어댑터 pass-through ────────────────────────

    @Test
    fun `S1 — 엔진이 Success 를 반환하면 어댑터는 동일한 Success 를 반환한다`() {
        val engineResult =
            AvailableTransitionsResult.Success(
                listOf(
                    AvailableTransitionView("open", "in_progress", "Start Work"),
                    AvailableTransitionView("open", "closed", "Cancel"),
                ),
            )
        every { mockEngine.availableTransitions(baseRequest) } returns engineResult

        val result = adapter.availableTransitions(baseRequest)

        assertThat(result).isEqualTo(engineResult)
        verify(exactly = 1) { mockEngine.availableTransitions(baseRequest) }
    }

    // ── S2. 엔진이 WorkflowNotFound 반환 → 어댑터 pass-through ──────────────

    @Test
    fun `S2 — 엔진이 WorkflowNotFound 를 반환하면 어댑터는 동일한 WorkflowNotFound 를 반환한다`() {
        val unknownReq = baseRequest.copy(workflowKey = "UNKNOWN_KEY")
        val engineResult = AvailableTransitionsResult.WorkflowNotFound("UNKNOWN_KEY")
        every { mockEngine.availableTransitions(unknownReq) } returns engineResult

        val result = adapter.availableTransitions(unknownReq)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.WorkflowNotFound::class.java)
        val notFound = result as AvailableTransitionsResult.WorkflowNotFound
        assertThat(notFound.key).isEqualTo("UNKNOWN_KEY")
        verify(exactly = 1) { mockEngine.availableTransitions(unknownReq) }
    }

    // ── S3. 어댑터는 엔진에만 위임 — 빈 Success 도 그대로 전달 ───────────────

    @Test
    fun `S3 — 엔진이 빈 전환 목록 Success 를 반환하면 어댑터는 그대로 반환한다`() {
        val engineResult = AvailableTransitionsResult.Success(emptyList())
        every { mockEngine.availableTransitions(baseRequest) } returns engineResult

        val result = adapter.availableTransitions(baseRequest)

        assertThat(result).isInstanceOf(AvailableTransitionsResult.Success::class.java)
        val success = result as AvailableTransitionsResult.Success
        assertThat(success.transitions).isEmpty()
    }
}

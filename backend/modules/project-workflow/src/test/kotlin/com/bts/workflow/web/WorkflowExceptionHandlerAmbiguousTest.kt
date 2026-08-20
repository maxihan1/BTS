// 모호 전환 예외(AmbiguousTransitionException)의 409 HTTP 계약을 project-workflow 자기 경로에서 고정하는 테스트

package com.bts.workflow.web

import com.bts.workflow.domain.exception.AmbiguousTransitionException
import com.bts.workflow.domain.exception.TransitionCandidate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 첫 번째 후보 전환 ID. 응답 순서가 던진 순서와 같은지 확인하는 데 쓴다. */
private val CANDIDATE_ONE_ID: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

/** 두 번째 후보 전환 ID. */
private val CANDIDATE_TWO_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")

/** 모호 전환이 발생한 워크플로우 키. */
private const val WORKFLOW_KEY = "software-default"

/**
 * [AmbiguousTransitionException] 을 그대로 던지는 최소 컨트롤러.
 *
 * project-workflow 컨트롤러 경로에서 예외가 표면화될 때 [AmbiguousTransitionExceptionHandler] 가
 * 어떤 HTTP 응답을 만드는지만 보기 위한 스텁이다. 실제 전환 해석(Task 6)에 의존하지 않는다.
 */
@RestController
@RequestMapping("/test/ambiguous-transition")
private class AmbiguousTransitionThrowingController {
    /** 후보 2건을 담은 모호 전환 예외를 던진다. */
    @GetMapping
    fun boom(): String =
        throw AmbiguousTransitionException(
            workflowKey = WORKFLOW_KEY,
            candidates =
                listOf(
                    TransitionCandidate(transitionId = CANDIDATE_ONE_ID, name = "조건부 승인"),
                    TransitionCandidate(transitionId = CANDIDATE_TWO_ID, name = "즉시 완료"),
                ),
        )
}

/**
 * 모호 전환 409 계약 — project-workflow 자기 경로 (FR-WF-05 결정 D-2, plan Task 1 테스트 ①).
 *
 * 같은 상태쌍에 전환이 둘 이상이고 호출자가 `transitionId` 를 주지 않았을 때,
 * 조용히 아무거나 고르지 않고 **409 `AMBIGUOUS_TRANSITION` + 후보 목록**을 돌려주는지 고정한다.
 * 선례는 `WorkflowSchemeExceptionHandler` 의 `SchemeInUseException(usedByProjects) → 409` 다.
 *
 * ## 형제 advice 를 일부러 함께 등록한다
 * 매핑은 [AmbiguousTransitionExceptionHandler] 가 단독으로 들고 있고, 형제인
 * [WorkflowExceptionHandler] 는 이 예외를 더는 잡지 않는다. 둘을 같이 올려 두는 이유는
 * 조립 상태를 흉내내기 위해서다 — 형제가 옆에 있어도 응답이 이 advice 것으로 나오는지 본다.
 */
class WorkflowExceptionHandlerAmbiguousTest {
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders
                .standaloneSetup(AmbiguousTransitionThrowingController())
                .setControllerAdvice(AmbiguousTransitionExceptionHandler(), WorkflowExceptionHandler())
                .build()
    }

    @Test
    fun `모호 전환 예외는 409 AMBIGUOUS_TRANSITION 과 후보 목록을 낸다`() {
        mockMvc.perform(get("/test/ambiguous-transition"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("AMBIGUOUS_TRANSITION"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)
            .andExpect(jsonPath("$.candidates.length()").value(2))
            .andExpect(jsonPath("$.candidates[0].transitionId").value(CANDIDATE_ONE_ID.toString()))
            .andExpect(jsonPath("$.candidates[0].name").value("조건부 승인"))
            .andExpect(jsonPath("$.candidates[1].transitionId").value(CANDIDATE_TWO_ID.toString()))
            .andExpect(jsonPath("$.candidates[1].name").value("즉시 완료"))
    }
}

// transport TransitionIssueRequest DTO resolutionId 필드 + application DTO 매핑 검증 단위 테스트

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.TransitionIssueRequest as AppTransitionIssueRequest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Task B5: 전이 요청 DTO에 resolutionId 필드가 존재하는지 검증한다.
 *
 * transport(REST 요청 바디)와 application 계층 DTO 모두에 resolutionId: UUID? 가 있어야 하며,
 * 컨트롤러가 transport→application 매핑 시 resolutionId 를 전달해야 한다.
 */
class TransitionIssueRequestTest {

    @Test
    fun `transport DTO는 resolutionId nullable 필드를 보유해야 한다`() {
        val resolutionId = UUID.randomUUID()
        val request = TransitionIssueRequest(
            toStatusKey = "done",
            expectedVersion = 1L,
            resolutionId = resolutionId,
        )
        assertEquals(resolutionId, request.resolutionId)
    }

    @Test
    fun `transport DTO resolutionId 기본값은 null 이어야 한다`() {
        val request = TransitionIssueRequest(
            toStatusKey = "in_progress",
            expectedVersion = 1L,
        )
        assertNull(request.resolutionId)
    }

    @Test
    fun `application DTO는 resolutionId nullable 필드를 보유해야 한다`() {
        val resolutionId = UUID.randomUUID()
        val appRequest = AppTransitionIssueRequest(
            toStateKey = "done",
            expectedVersion = 1L,
            resolutionId = resolutionId,
        )
        assertEquals(resolutionId, appRequest.resolutionId)
    }

    @Test
    fun `application DTO resolutionId 기본값은 null 이어야 한다`() {
        val appRequest = AppTransitionIssueRequest(
            toStateKey = "in_progress",
            expectedVersion = 1L,
        )
        assertNull(appRequest.resolutionId)
    }

    @Test
    fun `transport에서 application DTO로 매핑 시 resolutionId가 전달되어야 한다`() {
        val resolutionId = UUID.randomUUID()
        val transportRequest = TransitionIssueRequest(
            toStatusKey = "done",
            expectedVersion = 2L,
            resolutionId = resolutionId,
        )
        // 컨트롤러가 수행하는 매핑을 직접 재현한다
        val appRequest = AppTransitionIssueRequest(
            toStateKey = transportRequest.toStatusKey,
            expectedVersion = transportRequest.expectedVersion,
            resolutionId = transportRequest.resolutionId,
        )
        assertEquals(resolutionId, appRequest.resolutionId)
        assertEquals("done", appRequest.toStateKey)
        assertEquals(2L, appRequest.expectedVersion)
    }

    @Test
    fun `transport resolutionId null 이면 application DTO도 null 전달`() {
        val transportRequest = TransitionIssueRequest(
            toStatusKey = "in_progress",
            expectedVersion = 1L,
            resolutionId = null,
        )
        val appRequest = AppTransitionIssueRequest(
            toStateKey = transportRequest.toStatusKey,
            expectedVersion = transportRequest.expectedVersion,
            resolutionId = transportRequest.resolutionId,
        )
        assertNull(appRequest.resolutionId)
    }
}

// TransitionRequest 8파라미터 시그니처 회귀 가드 — transitionName 제거 후 컴파일+validate 통과 기대

package com.bts.shared.workflow

import io.konform.validation.Valid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [TransitionRequest] 8파라미터 시그니처 회귀 가드.
 *
 * transitionName 필드를 제거한 후 8개 파라미터로 생성 및 [TransitionRequest.validate] 통과를 검증한다.
 * ADR 2026-05-28-workflow-transition-identity-policy 참조.
 *
 * 테스트 목적.
 * - `transitionName` 없이 8파라미터로 생성 가능한지 컴파일 수준에서 보장한다.
 * - `validate()` 가 Valid 를 반환하는지 확인한다.
 * - `transitionName` 관련 프로퍼티가 없음을 런타임에서 검증한다.
 */
class TransitionRequestTest {
    @Test
    fun `8파라미터 시그니처로 생성 후 validate 가 Valid 를 반환한다`() {
        val req =
            TransitionRequest(
                workflowKey = "DEFAULT",
                issueKey = "BTS-1",
                fromStateKey = "TODO",
                toStateKey = "IN_PROGRESS",
                actorId = "user-001",
                issueFields = mapOf("priority" to "HIGH"),
                actorRoles = setOf("MEMBER"),
                version = 1L,
            )

        val result = req.validate()

        assertThat(result).isInstanceOf(Valid::class.java)
    }

    @Test
    fun `transitionName 프로퍼티가 존재하지 않는다`() {
        val req =
            TransitionRequest(
                workflowKey = "DEFAULT",
                issueKey = "BTS-1",
                fromStateKey = "TODO",
                toStateKey = "IN_PROGRESS",
                actorId = "user-001",
                issueFields = emptyMap(),
                actorRoles = emptySet(),
                version = 2L,
            )

        val propertyNames = req::class.members.map { it.name }
        assertThat(propertyNames).doesNotContain("transitionName")
    }

    @Test
    fun `validate 실패 케이스 — workflowKey 빈 문자열이면 Invalid 반환`() {
        val req =
            TransitionRequest(
                workflowKey = "",
                issueKey = "BTS-1",
                fromStateKey = "TODO",
                toStateKey = "IN_PROGRESS",
                actorId = "user-001",
                issueFields = emptyMap(),
                actorRoles = emptySet(),
                version = 1L,
            )

        val result = req.validate()

        assertThat(result).isNotInstanceOf(Valid::class.java)
    }
}

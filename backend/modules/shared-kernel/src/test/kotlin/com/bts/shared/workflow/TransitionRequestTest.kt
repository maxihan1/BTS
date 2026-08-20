// TransitionRequest 시그니처 회귀 가드 — 기존 8파라미터 호출 컴파일 + transitionId nullable 추가 계약

package com.bts.shared.workflow

import io.konform.validation.Valid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

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
 * - **N2 회귀 가드** — `transitionId` 는 기본값 있는 nullable 추가 필드다. 기본값을 지우면
 *   issue-tracking 등 다른 BC 의 8파라미터 호출부가 전부 컴파일 실패한다.
 *   그 순간 이 파일의 8파라미터 호출문이 먼저 red 로 알린다.
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

    @Test
    fun `transitionId 를 안 넣은 기존 생성자 호출이 그대로 컴파일된다`() {
        // ★ 이 호출문 자체가 판별식이다. TransitionRequest.transitionId 의 기본값을 지우면
        //   이 줄이 컴파일되지 않아 red 가 난다 (spec FR-WF-05 N2 — cross-BC 프로덕션 0줄).
        val req =
            TransitionRequest(
                workflowKey = "DEFAULT",
                issueKey = "BTS-1",
                fromStateKey = "TODO",
                toStateKey = "IN_PROGRESS",
                actorId = "user-001",
                issueFields = emptyMap(),
                actorRoles = emptySet(),
                version = 1L,
            )

        assertThat(req.transitionId).isNull()
        assertThat(req.validate()).isInstanceOf(Valid::class.java)
    }

    @Test
    fun `transitionId 를 실으면 그 값을 그대로 보관한다`() {
        val transitionId = UUID.fromString("00000000-0000-4000-8000-0000000000ab")

        val req =
            TransitionRequest(
                workflowKey = "DEFAULT",
                issueKey = "BTS-1",
                fromStateKey = "TODO",
                toStateKey = "IN_PROGRESS",
                actorId = "user-001",
                issueFields = emptyMap(),
                actorRoles = emptySet(),
                version = 1L,
                transitionId = transitionId,
            )

        assertThat(req.transitionId).isEqualTo(transitionId)
        assertThat(req.validate()).isInstanceOf(Valid::class.java)
    }
}

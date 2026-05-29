// AvailableTransitionsRequest validate() 검증 — 빈 키 거부, 정상 입력 통과

package com.bts.shared.workflow

import io.konform.validation.Valid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [AvailableTransitionsRequest] validate() 검증 테스트.
 *
 * [TransitionRequest] 의 validate 패턴과 동일한 Konform 기반 검증이 적용되는지 확인한다.
 *
 * 테스트 목적.
 * - 빈 `workflowKey` 는 Invalid 를 반환한다.
 * - 빈 `fromStateKey` 는 Invalid 를 반환한다.
 * - 정상 입력은 Valid 를 반환한다.
 */
class AvailableTransitionsRequestTest {

    @Test
    fun `정상 입력은 validate 가 Valid 를 반환한다`() {
        val req =
            AvailableTransitionsRequest(
                workflowKey = "DEFAULT",
                fromStateKey = "TODO",
                issueKey = "PROJ-1",
                actorId = "user-001",
                actorRoles = setOf("MEMBER"),
                issueFields = mapOf("priority" to "HIGH"),
            )

        val result = req.validate()

        assertThat(result).isInstanceOf(Valid::class.java)
    }

    @Test
    fun `workflowKey 가 빈 문자열이면 Invalid 를 반환한다`() {
        val req =
            AvailableTransitionsRequest(
                workflowKey = "",
                fromStateKey = "TODO",
                issueKey = "PROJ-1",
                actorId = "user-001",
                actorRoles = emptySet(),
                issueFields = emptyMap(),
            )

        val result = req.validate()

        assertThat(result).isNotInstanceOf(Valid::class.java)
    }

    @Test
    fun `fromStateKey 가 빈 문자열이면 Invalid 를 반환한다`() {
        val req =
            AvailableTransitionsRequest(
                workflowKey = "DEFAULT",
                fromStateKey = "",
                issueKey = "PROJ-1",
                actorId = "user-001",
                actorRoles = emptySet(),
                issueFields = emptyMap(),
            )

        val result = req.validate()

        assertThat(result).isNotInstanceOf(Valid::class.java)
    }

    @Test
    fun `issueKey 가 빈 문자열이면 Invalid 를 반환한다`() {
        val req =
            AvailableTransitionsRequest(
                workflowKey = "DEFAULT",
                fromStateKey = "TODO",
                issueKey = "",
                actorId = "user-001",
                actorRoles = emptySet(),
                issueFields = emptyMap(),
            )

        val result = req.validate()

        assertThat(result).isNotInstanceOf(Valid::class.java)
    }
}

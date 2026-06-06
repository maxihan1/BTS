// SecurityContext 인증 주체를 issue-tracking ActorId 로 변환하는 CurrentActor 헬퍼의 동작을 검증하는 테스트

package com.bts.issue.adapter.inbound.rest

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * issue-tracking [CurrentActor] 단위 테스트.
 *
 * project-workflow CurrentActor 와 달리 issue-tracking [com.bts.issue.domain.ActorId] 는 UUID 래퍼이며
 * nil-UUID(모두 0)를 `require` 가드로 거부한다. 따라서 (1) UUID 형식 오류와 (2) nil-UUID 거부 두 경로 모두
 * 401 로 변환되는지 검증한다.
 */
class CurrentActorTest {
    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `유효한 UUID name 인증 주체면 해당 UUID 의 ActorId 를 반환한다`() {
        val uuid = UUID.fromString("11111111-1111-4111-8111-111111111111")
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                uuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        val actorId = CurrentActor.current()

        assertThat(actorId.value).isEqualTo(uuid)
    }

    @Test
    fun `SecurityContext 가 비어 있으면 401 을 던진다`() {
        SecurityContextHolder.clearContext()

        assertThatThrownBy { CurrentActor.current() }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `익명 인증 주체면 401 을 던진다`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")),
            )

        assertThatThrownBy { CurrentActor.current() }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `name 이 UUID 형식이 아니면 401 을 던진다`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "alice",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        assertThatThrownBy { CurrentActor.current() }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `name 이 nil-UUID 면 401 을 던진다`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                "00000000-0000-0000-0000-000000000000",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )

        assertThatThrownBy { CurrentActor.current() }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}

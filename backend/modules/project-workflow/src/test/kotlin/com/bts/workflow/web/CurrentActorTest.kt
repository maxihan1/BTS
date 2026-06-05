// SecurityContext 인증 주체를 ActorId 로 변환하는 CurrentActor 헬퍼의 동작을 검증하는 테스트

package com.bts.workflow.web

import com.bts.workflow.port.outbound.toUuid
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

class CurrentActorTest {
    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `유효한 UUID name 인증 주체면 해당 UUID 의 ActorId 를 반환한다`() {
        val uuid = UUID.randomUUID()
        val authentication =
            UsernamePasswordAuthenticationToken(
                uuid.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = authentication

        val actorId = CurrentActor.current()

        assertThat(actorId.toUuid()).isEqualTo(uuid)
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
        val anonymous =
            AnonymousAuthenticationToken(
                "key",
                "anonymousUser",
                listOf(SimpleGrantedAuthority("ROLE_ANONYMOUS")),
            )
        SecurityContextHolder.getContext().authentication = anonymous

        assertThatThrownBy { CurrentActor.current() }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `name 이 UUID 형식이 아니면 401 을 던진다`() {
        val authentication =
            UsernamePasswordAuthenticationToken(
                "alice",
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        SecurityContextHolder.getContext().authentication = authentication

        assertThatThrownBy { CurrentActor.current() }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting { (it as ResponseStatusException).statusCode }
            .isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}

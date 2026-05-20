// SpringSecurityProviderAdapter 단위 테스트 — MockK로 ProviderRegistry mocking + 시나리오 검증

package com.atlas.bts.identity.adapter.spring

import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.MfaChallenge
import com.atlas.bts.identity.spi.Principal
import com.atlas.bts.identity.spi.ProviderRegistry
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.spi.fake.FakeLocalProvider
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import java.util.UUID

class SpringSecurityProviderAdapterTest {
    private val registry = mockk<ProviderRegistry>()
    private lateinit var adapter: SpringSecurityProviderAdapter

    @BeforeEach
    fun setUp() {
        adapter = SpringSecurityProviderAdapter(registry)
    }

    @Test
    fun `BTS Success maps to Spring authenticated Authentication`() {
        val principal = Principal(
            userId = UUID.randomUUID(),
            providerType = ProviderType.LOCAL,
            displayName = "Alice",
            externalSubject = null,
        )
        val fakeProvider = FakeLocalProvider()
        every { registry.findFor(any<Credential>()) } returns fakeProvider
        every { fakeProvider.authenticate(any()) } returns AuthnResult.Success(principal)

        // FakeLocalProvider.authenticate는 override 안됨 — mockk provider 사용
        val mockProvider = mockk<com.atlas.bts.identity.spi.AuthenticationProvider>()
        every { mockProvider.authenticate(any()) } returns AuthnResult.Success(principal)
        every { registry.findFor(any<Credential>()) } returns mockProvider

        val token = UsernamePasswordAuthenticationToken("alice", "correct")
        val result = adapter.authenticate(token)

        assertThat(result.isAuthenticated).isTrue()
        assertThat(result.principal).isEqualTo(principal)
        assertThat(result.credentials).isNull()
    }

    @Test
    fun `BTS Failure throws Spring BadCredentialsException`() {
        val mockProvider = mockk<com.atlas.bts.identity.spi.AuthenticationProvider>()
        every { mockProvider.authenticate(any()) } returns AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        every { registry.findFor(any<Credential>()) } returns mockProvider

        val token = UsernamePasswordAuthenticationToken("alice", "wrong")

        assertThatThrownBy { adapter.authenticate(token) }
            .isInstanceOf(BadCredentialsException::class.java)
    }

    @Test
    fun `BTS RequiresMfa throws InsufficientAuthenticationException with mfa challenge`() {
        val mockProvider = mockk<com.atlas.bts.identity.spi.AuthenticationProvider>()
        every { mockProvider.authenticate(any()) } returns AuthnResult.RequiresMfa(MfaChallenge.NOT_IMPLEMENTED_YET)
        every { registry.findFor(any<Credential>()) } returns mockProvider

        val token = UsernamePasswordAuthenticationToken("alice", "mfa")

        assertThatThrownBy { adapter.authenticate(token) }
            .isInstanceOf(InsufficientAuthenticationException::class.java)
            .hasMessageContaining("mfa")
    }

    @Test
    fun `registry returns null provider — throws BadCredentialsException`() {
        every { registry.findFor(any<Credential>()) } returns null

        val token = UsernamePasswordAuthenticationToken("alice", "correct")

        assertThatThrownBy { adapter.authenticate(token) }
            .isInstanceOf(BadCredentialsException::class.java)
            .hasMessageContaining("no provider")
    }

    @Test
    fun `supports returns true for UsernamePasswordAuthenticationToken`() {
        assertThat(adapter.supports(UsernamePasswordAuthenticationToken::class.java)).isTrue()
    }

    @Test
    fun `adapter is NOT auto-registered as Spring Bean via component scan`() {
        // @Component 미부착 검증 — 표준 컴포넌트 스캔으로 SpringSecurityProviderAdapter가 등록되지 않음.
        // FR-AU-09 PR에서 SecurityConfig에 명시 @Bean 등록 예정 (ADR, KDoc 참고).
        // 주의: 전체 SpringBootTest 컨텍스트 로드는 Keycloak issuer-uri 문제로 불가.
        // @Component 어노테이션 부재를 소스 레벨에서 직접 검증.
        val annotations = SpringSecurityProviderAdapter::class.java.annotations
        val hasComponent = annotations.any {
            it.annotationClass.qualifiedName == "org.springframework.stereotype.Component"
        }
        assertThat(hasComponent)
            .describedAs("SpringSecurityProviderAdapter는 @Component가 없어야 한다 — 수동 @Bean 등록 강제")
            .isFalse()
    }
}

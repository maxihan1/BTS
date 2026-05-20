// Spring Security AuthenticationProvider 어댑터 — BTS ProviderRegistry를 Spring 필터 체인에 연결

package com.atlas.bts.identity.adapter.spring

import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.ProviderRegistry
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.InsufficientAuthenticationException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication

/**
 * BTS [ProviderRegistry]를 Spring Security [org.springframework.security.authentication.AuthenticationProvider]로
 * 연결하는 어댑터.
 *
 * 주의: 이 클래스는 @Component를 사용하지 않는다.
 * Spring Security 자동 설정이 AuthenticationProvider Bean을 감지하면 기본 인증 흐름을 덮어쓰는
 * 위험이 있으므로, SecurityConfig에서 명시적 @Bean 등록만 허용한다.
 * FR-AU-09 PR에서 SecurityConfig에 명시 등록 예정.
 */
class SpringSecurityProviderAdapter(
    private val registry: ProviderRegistry,
) : org.springframework.security.authentication.AuthenticationProvider {
    override fun authenticate(authentication: Authentication): Authentication {
        val credential = authentication.toBtsCredential()
        val provider =
            registry.findFor(credential)
                ?: throw BadCredentialsException("no provider for credential: ${credential::class.simpleName}")

        return when (val result = provider.authenticate(credential)) {
            is AuthnResult.Success -> result.toSpringAuthentication()
            is AuthnResult.Failure -> throw BadCredentialsException("authentication failed: ${result.reason}")
            is AuthnResult.RequiresMfa -> throw InsufficientAuthenticationException("mfa required: ${result.challenge}")
        }
    }

    override fun supports(authentication: Class<*>): Boolean =
        UsernamePasswordAuthenticationToken::class.java.isAssignableFrom(authentication)

    private fun Authentication.toBtsCredential(): Credential =
        when (this) {
            is UsernamePasswordAuthenticationToken ->
                Credential.UsernamePassword(
                    username = name,
                    password = (credentials as? String ?: "").toCharArray(),
                )
            else -> throw BadCredentialsException("unsupported authentication type: ${this::class.simpleName}")
        }

    private fun AuthnResult.Success.toSpringAuthentication(): Authentication =
        UsernamePasswordAuthenticationToken(
            principal,
            null,
            emptyList(),
        )
}

// provider 명시 선택 로그인 디스패처 — providerId 로 단일 Provider 만 호출 (FR-AU-06)

package com.atlas.bts.identity.auth

import com.atlas.bts.identity.provider.AuthnProviderConfigRepository
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.ProviderRegistry
import com.atlas.bts.identity.spi.ProviderType
import org.springframework.stereotype.Service

/**
 * provider 를 **명시적으로 선택**해 로그인 자격증명을 인증하는 디스패처 (FR-AU-06).
 *
 * ## 명시 선택만 — 자동 fallback / 우선순위 순회 없음 (보안 결정)
 * 로그인 요청의 `providerId`("local"/"ldap" 등)로 지정된 **단일** Provider 만 호출하고
 * 그 결과를 그대로 반환한다. 인증 실패 시 다른 Provider 로 자동 재시도하지 않는다.
 *
 * 자동 fallback 을 배제한 이유:
 * - **동명이인 타계정 로그인**: LDAP 의 "alice" 와 LOCAL 의 "alice" 가 다른 사람일 때,
 *   fallback 순회는 의도치 않은 타계정 로그인을 유발할 수 있다.
 * - **비밀번호 오전달**: 한 Provider 의 비밀번호가 다른 Provider 의 bind 시도로 전송되어
 *   외부 디렉터리에 평문 비밀번호가 노출될 위험.
 * - **lockout 2배**: 순회마다 실패 카운트가 누적되어 사용자가 빠르게 잠긴다.
 *
 * ## 처리 경로 (username/password 계열 전용)
 * 이 디스패처는 일반 로그인 폼(POST /login)의 LOCAL / LDAP 만 처리한다.
 * SSO(SAML/OIDC)·PAT 는 Spring Security 필터가 별도 흐름으로 인증하므로
 * 이 경로로 들어오면 [FailureReason.INVALID_INPUT] 으로 거부한다.
 */
@Service
class CompositeAuthenticationManager(
    private val providerRegistry: ProviderRegistry,
    private val authnProviderConfigRepository: AuthnProviderConfigRepository,
) {
    /**
     * 지정된 [providerId] 의 Provider 로 username/password 인증을 수행한다.
     *
     * 흐름:
     * 1. [providerId] 를 [ProviderType] 으로 파싱 — 알 수 없는 값은 인증 실패로 처리.
     * 2. username/password 계열(LOCAL/LDAP)이 아니면 [FailureReason.INVALID_INPUT].
     * 3. 운영자가 비활성화한 Provider 이면 [FailureReason.PROVIDER_UNAVAILABLE].
     * 4. 해당 type 의 Provider Bean 이 없으면 [FailureReason.PROVIDER_UNAVAILABLE].
     * 5. type 에 맞는 [Credential] 을 만들어 Provider 에 위임하고 결과를 그대로 반환.
     *
     * [com.atlas.bts.identity.provider.ldap.ProviderUnavailableException] 은 잡지 않고
     * 호출자(AuthController)로 전파한다 — 503 변환은 상위 레이어 책임이다.
     *
     * @param providerId 로그인 폼이 보낸 provider 식별자 (예: "local", "ldap"). 대소문자 무시.
     * @param username 사용자명
     * @param password 비밀번호 CharArray — wipe 책임은 Provider 구현체에 있다.
     */
    fun authenticate(
        providerId: String,
        username: String,
        password: CharArray,
    ): AuthnResult {
        val type = parseUsernamePasswordType(providerId)
            ?: return AuthnResult.Failure(FailureReason.INVALID_INPUT)

        if (!authnProviderConfigRepository.isEnabled(type)) {
            return AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
        }

        val provider = providerRegistry.findByType(type)
            ?: return AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)

        val credential = buildCredential(type, username, password)
        return provider.authenticate(credential)
    }

    /**
     * [providerId] 를 username/password 계열 [ProviderType] 으로 파싱한다.
     *
     * 알 수 없는 provider 문자열은 [ProviderType.valueOf] 가 예외를 던지므로
     * [runCatching] 으로 감싸 의도적으로 null(=인증 실패) 로 변환한다 (CONCERN C3).
     * LOCAL/LDAP 이 아닌 type(SAML/OIDC/PAT/OAUTH)은 이 경로 대상이 아니므로 null 을 반환한다.
     */
    private fun parseUsernamePasswordType(providerId: String): ProviderType? =
        runCatching { ProviderType.valueOf(providerId.uppercase()) }
            .getOrNull()
            ?.takeIf { it == ProviderType.LOCAL || it == ProviderType.LDAP }

    /** username/password 계열 [type] 에 맞는 [Credential] 을 생성한다. */
    private fun buildCredential(
        type: ProviderType,
        username: String,
        password: CharArray,
    ): Credential =
        when (type) {
            ProviderType.LOCAL -> Credential.UsernamePassword(username, password)
            ProviderType.LDAP -> Credential.LdapBind(username, password)
            else -> error("buildCredential 은 LOCAL/LDAP 만 처리한다 — parseUsernamePasswordType 로 보장됨")
        }
}

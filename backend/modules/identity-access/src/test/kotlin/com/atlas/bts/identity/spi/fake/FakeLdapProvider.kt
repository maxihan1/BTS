// 단위 테스트 전용 LDAP 인증 공급자 가짜 구현체 — test-spi 프로필에서만 활성화

package com.atlas.bts.identity.spi.fake

import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.Principal
import com.atlas.bts.identity.spi.ProviderType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * test-spi 프로필 전용 가짜 LdapProvider.
 *
 * 인증 규칙:
 * - 항상 [Credential.LdapBind]를 지원.
 * - [available] = true 이면 Success, false 이면 Failure(PROVIDER_UNAVAILABLE).
 *
 * [available] 파라미터로 LDAP 서버 가용 상태를 시뮬레이션한다 (EC-22 폴백 검증용).
 */
@Component
@Profile("test-spi")
class FakeLdapProvider(
    val available: Boolean = true,
) : AuthenticationProvider {
    override val type: ProviderType = ProviderType.LDAP

    override fun supports(credential: Credential): Boolean = credential is Credential.LdapBind

    override fun authenticate(credential: Credential): AuthnResult {
        require(credential is Credential.LdapBind)
        return if (available) {
            AuthnResult.Success(
                Principal(
                    userId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                    providerType = ProviderType.LDAP,
                    displayName = credential.username,
                    externalSubject = "ldap:${credential.username}",
                ),
            )
        } else {
            AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
        }
    }
}

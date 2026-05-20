// 단위 테스트 전용 로컬 인증 공급자 가짜 구현체 — test-spi 프로필에서만 활성화

package com.atlas.bts.identity.spi.fake

import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.MfaChallenge
import com.atlas.bts.identity.spi.Principal
import com.atlas.bts.identity.spi.ProviderType
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * test-spi 프로필 전용 가짜 LocalProvider.
 * PoC #2 Keycloak 통합 테스트(test 프로필)와 충돌 방지를 위해 별도 프로필 사용.
 *
 * 인증 규칙:
 * - password가 빈 문자열 → Failure(INVALID_INPUT)
 * - password가 "wrong" → Failure(INVALID_CREDENTIALS)
 * - password가 "mfa" → RequiresMfa(NOT_IMPLEMENTED_YET)
 * - password가 "correct" → Success
 * - 그 외 → Failure(INVALID_CREDENTIALS)
 */
@Component
@Profile("test-spi")
class FakeLocalProvider : AuthenticationProvider {
    override val type: ProviderType = ProviderType.LOCAL

    override fun supports(credential: Credential): Boolean = credential is Credential.UsernamePassword

    override fun authenticate(credential: Credential): AuthnResult {
        require(credential is Credential.UsernamePassword)
        return when {
            credential.password.isEmpty() -> AuthnResult.Failure(FailureReason.INVALID_INPUT)
            credential.password.contentEquals("wrong".toCharArray()) -> AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
            credential.password.contentEquals("mfa".toCharArray()) -> AuthnResult.RequiresMfa(MfaChallenge.NOT_IMPLEMENTED_YET)
            credential.password.contentEquals("correct".toCharArray()) -> AuthnResult.Success(
                Principal(
                    userId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                    providerType = ProviderType.LOCAL,
                    displayName = credential.username,
                    externalSubject = null,
                ),
            )
            else -> AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }
    }
}

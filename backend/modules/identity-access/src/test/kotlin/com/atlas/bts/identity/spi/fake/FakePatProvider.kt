// 단위 테스트 전용 PAT 인증 공급자 가짜 구현체 — test-spi 프로필에서만 활성화

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
 * test-spi 프로필 전용 가짜 PatProvider.
 * token이 "valid"이면 Success, 아니면 Failure(INVALID_CREDENTIALS).
 */
@Component
@Profile("test-spi")
class FakePatProvider : AuthenticationProvider {
    override val type: ProviderType = ProviderType.PAT

    override fun supports(credential: Credential): Boolean = credential is Credential.Pat

    override fun authenticate(credential: Credential): AuthnResult {
        require(credential is Credential.Pat)
        return if (credential.token == "valid") {
            AuthnResult.Success(
                Principal(
                    userId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                    providerType = ProviderType.PAT,
                    displayName = "pat-user",
                    externalSubject = null,
                ),
            )
        } else {
            AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }
    }
}

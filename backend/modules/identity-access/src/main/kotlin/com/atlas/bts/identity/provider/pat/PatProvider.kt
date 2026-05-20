// PAT(Personal Access Token) 인증 공급자 스텁 — RED 단계, 컴파일 통과용

package com.atlas.bts.identity.provider.pat

import com.atlas.bts.identity.pat.PersonalAccessTokenRepository
import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/** PAT 인증 공급자 — RED 스텁 (모든 인증 실패 반환) */
@Service
class PatProvider(
    private val patRepository: PersonalAccessTokenRepository,
    private val userRepository: UserRepository,
) : AuthenticationProvider {

    override val type: ProviderType = ProviderType.PAT

    override fun supports(credential: Credential): Boolean = credential is Credential.Pat

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun authenticate(credential: Credential): AuthnResult {
        require(credential is Credential.Pat)
        return AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
    }
}

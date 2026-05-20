// PAT(Personal Access Token) 인증 공급자 — token_hash 검증 + 활성 상태 확인, priority=60

package com.atlas.bts.identity.provider.pat

import com.atlas.bts.identity.pat.PersonalAccessTokenRepository
import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.Principal
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant

@Service
class PatProvider(
    private val patRepository: PersonalAccessTokenRepository,
    private val userRepository: UserRepository,
) : AuthenticationProvider {

    private val log = LoggerFactory.getLogger(PatProvider::class.java)

    override val type: ProviderType = ProviderType.PAT

    override fun supports(credential: Credential): Boolean = credential is Credential.Pat

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun authenticate(credential: Credential): AuthnResult {
        require(credential is Credential.Pat) {
            "PatProvider 는 Pat 자격증명만 처리합니다."
        }

        val hash = sha256Hex(credential.token)

        // EC-08 timing attack 방어: token 존재 여부와 무관하게 SHA-256 계산 후 DB 조회 항상 수행
        val pat = patRepository.findByTokenHash(hash)

        if (pat == null) {
            log.debug("PatProvider authenticate — token_hash 미존재 (enumeration 방지)")
            return AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }

        // EC-09: revoked 또는 만료 확인
        if (!pat.isActive(Instant.now())) {
            log.debug("PatProvider authenticate — PAT inactive (revoked or expired) id={}", pat.id)
            return AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }

        val user = userRepository.findById(pat.userId)
        if (user == null) {
            log.debug("PatProvider authenticate — user 미존재 userId={}", pat.userId)
            return AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }

        return AuthnResult.Success(
            Principal(
                userId = user.id,
                providerType = ProviderType.PAT,
                displayName = user.displayName,
                externalSubject = null,
            ),
        )
    }

    internal companion object {
        /**
         * EC-26 token_hash 정책: SHA-256(raw_token) 소문자 hex 64자.
         * raw_token = `pat_` prefix 포함 전체 문자열 — prefix 를 별도 제거하지 않는다.
         */
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

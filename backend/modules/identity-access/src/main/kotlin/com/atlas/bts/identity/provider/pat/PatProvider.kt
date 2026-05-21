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

/**
 * Personal Access Token (PAT) 인증 공급자 (FR-AU-09 Task 14 / SDD §19.5).
 *
 * CI/CD, 스크립트 등 프로그래밍 방식 API 접근에 사용되는 장기 토큰을 검증한다.
 * PAT 발급은 후속 PR 에서 구현한다. 이 클래스는 **검증만** 담당한다.
 *
 * ## 인증 흐름
 * 1. [Credential.Pat.token] (`pat_` + 48자 base62 body) 을 받아 [sha256Hex] 로 해시한다.
 * 2. [PersonalAccessTokenRepository.findByTokenHash] 로 DB 조회한다.
 *    — token 미존재라도 이 조회는 반드시 수행된다 (EC-08 timing attack 방어).
 * 3. PAT 가 없으면 [AuthnResult.Failure]([FailureReason.INVALID_CREDENTIALS]) 반환.
 * 4. [com.atlas.bts.identity.pat.PersonalAccessToken.isActive] 로 revoke/만료 확인 (EC-09).
 *    — `expiresAt == null` 이면 무기한 유효 (EC-27).
 * 5. [UserRepository.findById] 로 연결된 사용자 조회. user 미존재 시 Failure.
 * 6. 모든 검증 통과 → [AuthnResult.Success]([Principal]).
 *
 * ## Priority 표 (SDD §19.2, FR-AU-09-28)
 * | ProviderType | priority |
 * |---|---|
 * | LDAP  | 80 |
 * | LOCAL | 70 |
 * | **PAT**   | **60** |
 * | OIDC  | 50 |
 * | SAML  | 40 |
 * | OAUTH | 30 |
 *
 * ## 보안 정책 (DEVELOPMENT.md §1.1, DATA.md §8)
 * - **EC-26 token_hash**: `SHA-256("pat_" + body)` — prefix 포함 전체 raw token 을 해시.
 *   `pat_` prefix 를 제거한 body 만 해시하면 EC-26 위반이다.
 * - **EC-08 timing attack 방어**: token 미존재 분기에서도 [PersonalAccessTokenRepository.findByTokenHash]
 *   를 반드시 호출한다. 호출 생략 시 응답 시간 차이로 token 존재 여부가 노출된다.
 * - **EC-09 상태 확인**: [com.atlas.bts.identity.pat.PersonalAccessToken.isActive] 위임.
 *   revoke(`revokedAt != null`) 와 만료(`expiresAt <= now`) 를 모두 차단한다.
 * - **EC-27 무기한 정책**: `expiresAt == null` 이면 만료되지 않은 토큰으로 간주한다.
 * - scope 강제는 Bearer 필터 레이어 책임 (후속 PR). 이 클래스는 scope 를 검증하지 않는다.
 * - 로그에 token 원문 / hash 미출력. PAT id 만 debug 레벨로 기록.
 *
 * ## 트랜잭션 경계 (DATA.md §6, PR #6 CONCERN-1)
 * [authenticate] 에 `REQUIRES_NEW` 적용 — 호출 측 트랜잭션과 독립. 읽기만 수행하므로
 * 실제 커밋은 없지만 트랜잭션 경계 일관성을 위해 LocalProvider 패턴을 따른다.
 */
@Service
class PatProvider(
    private val patRepository: PersonalAccessTokenRepository,
    private val userRepository: UserRepository,
) : AuthenticationProvider {

    private val log = LoggerFactory.getLogger(PatProvider::class.java)

    override val type: ProviderType = ProviderType.PAT

    override fun supports(credential: Credential): Boolean = credential is Credential.Pat

    /**
     * PAT 인증 수행.
     *
     * Contract: 예외를 throw 하지 않고 [AuthnResult.Failure] 로 반환 (Provider contract).
     * scope 검증은 이 메서드 범위 밖 (후속 PR requiredScope 파라미터 도입 예정).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun authenticate(credential: Credential): AuthnResult {
        require(credential is Credential.Pat) {
            "PatProvider 는 Pat 자격증명만 처리합니다."
        }

        val hash = sha256Hex(credential.token)

        // EC-08 timing attack 방어: token 존재 여부와 무관하게 SHA-256 계산 후 DB 조회 항상 수행.
        // 이 조회를 생략하면 응답 시간 차이로 token 존재 여부가 노출된다.
        val pat = patRepository.findByTokenHash(hash)

        if (pat == null) {
            log.debug("PatProvider authenticate — token_hash 미존재 (enumeration 방지)")
            return AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
        }

        // EC-09: revoked 또는 만료(EC-27: expiresAt null = 무기한) 확인
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
         *
         * raw_token = `pat_` prefix 포함 전체 문자열.
         * `pat_` prefix 를 제거한 body 만 해시하면 EC-26 정책 위반이다.
         * 테스트에서 동일 알고리즘으로 hash 를 미리 계산할 때도 이 함수를 사용한다.
         *
         * @param input `pat_` prefix 포함 raw token 전체
         * @return SHA-256 소문자 hex 64자 문자열
         */
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

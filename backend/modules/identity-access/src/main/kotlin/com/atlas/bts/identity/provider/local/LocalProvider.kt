// Local(username/password) 인증 공급자 — BTS SPI AuthenticationProvider 구현, priority=70

package com.atlas.bts.identity.provider.local

import com.atlas.bts.identity.credential.LocalCredentialService
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
import java.util.UUID

/**
 * Local username/password 인증 공급자 (FR-AU-09 Task 13 / SDD §19.2).
 *
 * ## supports 메서드 (SDD §19.2)
 * [Credential.UsernamePassword] 타입만 처리. [Credential.Pat], [Credential.LdapBind] 는 false 반환.
 *
 * ## 인증 흐름
 * 1. [UserRepository.findByUsername] 으로 사용자 조회
 * 2. user 미존재 시: [LocalCredentialService.verifyForUser] 를 [DUMMY_USER_ID] 로 호출하여
 *    응답 시간 일정화 후 Failure 반환
 *    — EC-02 timing attack 방어: user 존재 여부와 무관하게 Argon2 연산 수행
 *    ([LocalCredentialService] 내부에서 [com.atlas.bts.identity.credential.Argon2Params.DUMMY_HASH] 사용)
 * 3. user 존재 시: [LocalCredentialService.verifyForUser] 로 패스워드 검증 (EC-01)
 * 4. 검증 성공 → [AuthnResult.Success], 실패 → [AuthnResult.Failure]
 *
 * ## Priority 표 (SDD §19.2, FR-AU-09-28)
 * | ProviderType | priority |
 * |---|---|
 * | LDAP  | 80 |
 * | LOCAL | **70** |
 * | PAT   | 60 |
 *
 * ## 보안 (DEVELOPMENT.md §1.1)
 * - password [CharArray] 는 finally 블록에서 반드시 wipe (`fill(' ')`)
 * - 로그에 username 이외의 PII / password / hash 미출력
 * - 예외를 throw 하지 않고 [AuthnResult.Failure] 로 반환 (Provider contract)
 *
 * ## 트랜잭션 경계 (PR #6 CONCERN-1 검증)
 * [authenticate] 에 `REQUIRES_NEW` 를 적용하여 호출 측 트랜잭션과 독립적으로 동작.
 * [LocalCredentialService.verifyForUser] 는 내부적으로 `readOnly=true` 트랜잭션을 사용하며,
 * REQUIRES_NEW 하위 컨텍스트에서 올바르게 참여한다.
 */
@Service
class LocalProvider(
    private val localCredentialService: LocalCredentialService,
    private val userRepository: UserRepository,
) : AuthenticationProvider {

    private val log = LoggerFactory.getLogger(LocalProvider::class.java)

    override val type: ProviderType = ProviderType.LOCAL

    override fun supports(credential: Credential): Boolean = credential is Credential.UsernamePassword

    /**
     * Username/password 인증 수행.
     *
     * Contract: [Credential.UsernamePassword.password] CharArray 는 예외 여부와 무관하게
     * finally 블록에서 wipe 됨. 예외를 throw 하지 않고 [AuthnResult.Failure] 로 반환.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun authenticate(credential: Credential): AuthnResult {
        require(credential is Credential.UsernamePassword) {
            "LocalProvider 는 UsernamePassword 자격증명만 처리합니다."
        }

        try {
            val user = userRepository.findByUsername(credential.username)

            if (user == null) {
                // EC-02 timing attack 방어: user 없어도 Argon2 dummy verify 수행 (응답 시간 일정화)
                // LocalCredentialService.verifyForUser 내부에서 DUMMY_HASH 로 검증 후 false 반환
                localCredentialService.verifyForUser(DUMMY_USER_ID, credential.password.copyOf())
                log.debug("LocalProvider authenticate — user not found (enumeration 방지)")
                return AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
            }

            val verified = localCredentialService.verifyForUser(user.id, credential.password.copyOf())
            if (!verified) {
                log.debug("LocalProvider authenticate — password mismatch")
                return AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
            }

            return AuthnResult.Success(
                Principal(
                    userId = user.id,
                    providerType = ProviderType.LOCAL,
                    displayName = user.displayName,
                    externalSubject = null,
                ),
            )
        } finally {
            // password wipe — 예외 여부 무관 (DEVELOPMENT.md §1.1)
            credential.password.fill(' ')
        }
    }

    internal companion object {
        /**
         * EC-02 timing attack 방어용 dummy UUID.
         *
         * user 미존재 시 [LocalCredentialService.verifyForUser] 에 전달하여
         * local_credentials row 없는 경우의 dummy Argon2 verify 경로를 밟게 한다.
         * 이 UUID 가 실제 사용자 ID 와 일치할 가능성은 UUID v4 랜덤 특성상 무시 가능.
         */
        val DUMMY_USER_ID: UUID = UUID.fromString("00000000-dead-beef-0000-000000000000")
    }
}

// LDAP/AD 인증 공급자 구현 — Spring LdapTemplate 어댑터 + BTS SPI AuthenticationProvider

package com.atlas.bts.identity.provider.ldap

import com.atlas.bts.identity.spi.AuthenticationProvider
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason
import com.atlas.bts.identity.spi.Principal
import com.atlas.bts.identity.spi.ProviderType
import org.slf4j.LoggerFactory
import org.springframework.ldap.AuthenticationException
import org.springframework.ldap.CommunicationException
import org.springframework.ldap.core.LdapTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * LDAP/AD 인증 공급자 (FR-AU-02).
 *
 * **인증 흐름**:
 * 1. LdapProviderConfigService 에서 활성 LdapConfig 로드 (lazy init — 0건이면 PROVIDER_UNAVAILABLE)
 * 2. bind password 환경변수 확인 — 미설정 시 PROVIDER_UNAVAILABLE + 1회 WARN
 * 3. user_external_accounts 에서 기존 매핑 조회
 * 4. 잠금 상태 확인 (lockedUntil > now → ACCOUNT_LOCKED)
 * 5. Spring LdapTemplate.authenticate() 호출
 * 6. 성공 → 자동 프로비저닝 (첫 로그인 시) + last_login_at 갱신 + lockout reset
 * 7. 실패 → failedAttempts +1, maxAttempts 초과 시 locked_until 설정
 * 8. 서버 장애 → PROVIDER_UNAVAILABLE
 *
 * **보안 (DEVELOPMENT.md §1.1, §1.2)**:
 * - password CharArray 는 finally 블록에서 반드시 wipe (fill ' ')
 * - 로그에 password / DN / externalSubject 미출력
 * - @Profile 미부착 → production 자동 활성
 */
@Component
class LdapProvider(
    private val configService: LdapProviderConfigService,
    private val externalAccountRepo: ExternalAccountRepository,
    private val ldapTemplate: LdapTemplate,
    private val clock: Clock = Clock.systemUTC(),
) : AuthenticationProvider {

    private val log = LoggerFactory.getLogger(LdapProvider::class.java)

    override val type: ProviderType = ProviderType.LDAP

    override fun supports(credential: Credential): Boolean = credential is Credential.LdapBind

    /**
     * LDAP 바인드 인증 수행.
     *
     * Contract: password CharArray 는 예외 여부와 무관하게 finally 블록에서 wipe 됨.
     * 예외를 throw 하지 않고 AuthnResult.Failure 로 반환 (Provider contract).
     */
    override fun authenticate(credential: Credential): AuthnResult {
        require(credential is Credential.LdapBind) { "LdapProvider는 LdapBind 자격증명만 처리합니다." }

        try {
            // 1. 설정 로드
            val (providerId, config) = configService.findEnabledLdapConfig()
                ?: run {
                    log.warn("LDAP provider 미설정 — authn_providers 에 활성 LDAP 행 없음")
                    return AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
                }

            // 2. bind password 확인
            val bindPassword = config.resolveBindPassword()
                ?: run {
                    log.warn("LDAP bind password 환경변수 미설정 — env: {}", config.bindPasswordEnv)
                    return AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
                }

            // username 길이 검증 (DEVELOPMENT.md §1.6 — 256자 제한)
            if (credential.username.length > MAX_USERNAME_LENGTH) {
                return AuthnResult.Failure(FailureReason.INVALID_INPUT)
            }

            val now = Instant.now(clock)

            // 3. 기존 매핑 조회
            val existing = externalAccountRepo.findByProviderIdAndExternalSubject(
                providerId,
                credential.username,
            )

            // 4. 잠금 상태 확인
            if (existing?.lockedUntil?.isAfter(now) == true) {
                return AuthnResult.Failure(FailureReason.ACCOUNT_LOCKED)
            }

            // 5. LDAP 인증
            return try {
                val searchFilter = config.userSearchFilter.replace("{0}", escapeForLdapFilter(credential.username))
                val authenticated = ldapTemplate.authenticate(config.userSearchBase, searchFilter, String(credential.password))

                if (!authenticated) {
                    return onFailure(existing, config.lockoutPolicy, now, providerId, credential.username)
                }

                // 6. 성공 처리
                onSuccess(credential.username, existing, providerId, config, now, bindPassword)
            } catch (e: AuthenticationException) {
                log.debug("LDAP 인증 실패 — reason=BadCredentials (detail masked)")
                onFailure(existing, config.lockoutPolicy, now, providerId, credential.username)
            } catch (e: CommunicationException) {
                log.warn("LDAP 서버 통신 오류 — {}", e.message)
                AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
            } catch (e: Exception) {
                log.warn("LDAP 인증 중 예상치 못한 오류 — {}", e.javaClass.simpleName)
                AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
            }
        } finally {
            // password wipe — 예외 여부 무관 (DEVELOPMENT.md §1.1)
            credential.password.fill(' ')
        }
    }

    /** 인증 성공 처리 — 자동 프로비저닝 + last_login_at 갱신 */
    private fun onSuccess(
        username: String,
        existing: ExternalAccount?,
        providerId: UUID,
        config: LdapConfig,
        now: Instant,
        @Suppress("UNUSED_PARAMETER") bindPassword: String,
    ): AuthnResult {
        val externalSubject = buildExternalSubject(username, config)
        val account = if (existing == null) {
            // 첫 로그인 — 자동 프로비저닝 (DATA.md §6 단일 트랜잭션)
            externalAccountRepo.provisionUser(
                providerId = providerId,
                externalSubject = externalSubject,
                username = "$username@${config.baseDn.removePrefix("dc=").replace(",dc=", ".")}",
                displayName = username,
                email = null,
                groups = emptyList(),
            )
        } else {
            existing
        }

        externalAccountRepo.updateLastLoginAt(account.id, now)

        return AuthnResult.Success(
            Principal(
                userId = account.userId,
                providerType = ProviderType.LDAP,
                displayName = account.externalSubject.substringBefore(",").removePrefix("uid="),
                externalSubject = account.externalSubject,
            ),
        )
    }

    /** 인증 실패 처리 — failedAttempts +1, maxAttempts 초과 시 잠금 */
    private fun onFailure(
        existing: ExternalAccount?,
        policy: LockoutPolicy,
        now: Instant,
        providerId: UUID,
        username: String,
    ): AuthnResult {
        if (existing != null) {
            externalAccountRepo.incrementFailedAttempts(existing.id)
            val newAttempts = existing.failedAttempts + 1
            if (newAttempts >= policy.maxAttempts) {
                val lockUntil = now.plus(policy.lockoutMinutes.toLong(), ChronoUnit.MINUTES)
                externalAccountRepo.markLockedUntil(existing.id, lockUntil)
            }
        }
        // enumeration 방지 — USER_NOT_FOUND 와 구분 안 함 (spec S-04)
        return AuthnResult.Failure(FailureReason.INVALID_CREDENTIALS)
    }

    /** LDAP DN 형식의 externalSubject 생성 */
    private fun buildExternalSubject(username: String, config: LdapConfig): String =
        "uid=$username,${config.userSearchBase},${config.baseDn}"

    /**
     * LDAP 필터 특수문자 escape (LDAP injection 방어 — DEVELOPMENT.md §1.6).
     * RFC 4515 규정 문자: \ * ( ) \0
     */
    private fun escapeForLdapFilter(value: String): String =
        value
            .replace("\\", "\\5c")
            .replace("*", "\\2a")
            .replace("(", "\\28")
            .replace(")", "\\29")
            .replace(" ", "\\00")

    private companion object {
        const val MAX_USERNAME_LENGTH = 256
    }
}

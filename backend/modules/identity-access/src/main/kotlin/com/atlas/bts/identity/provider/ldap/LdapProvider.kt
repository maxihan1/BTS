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
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * LDAP/AD 인증 공급자 (FR-AU-02, FR-AU-09 Task 15).
 *
 * **인증 흐름**:
 * 1. LdapProviderConfigService 에서 활성 LdapConfig 로드 (lazy init — 0건이면 PROVIDER_UNAVAILABLE)
 * 2. bind password 환경변수 확인 — 미설정 시 PROVIDER_UNAVAILABLE + 1회 WARN
 * 3. user_external_accounts 에서 기존 매핑 조회
 * 4. 잠금 상태 확인 (lockedUntil > now → ACCOUNT_LOCKED)
 * 5. Spring LdapTemplate.authenticate() 호출
 * 6. 성공 → AutoProvisionService.provision (users + user_external_accounts UPSERT 단일 트랜잭션)
 *           + last_login_at 갱신 + lockout reset
 * 7. 실패 → failedAttempts +1, maxAttempts 초과 시 locked_until 설정
 * 8. 서버 장애 → PROVIDER_UNAVAILABLE (AuthnResult.Failure)
 *
 * **@Service (PR #6 learning #1)**:
 * @Transactional 을 위임 받는 AutoProvisionService 가 @Service 이며, 이 클래스도 Spring Bean 이어야
 * AutoProvisionService 를 올바른 프록시로 주입받을 수 있다. @Component → @Service 변경.
 *
 * **priority = 80 (SDD §19.2, FR-AU-09-28)**:
 * [ProviderType.LDAP].priority = 80 — [AuthenticationProvider] 인터페이스 default getter 위임.
 *
 * | Provider | priority |
 * |---|---|
 * | LDAP  | **80** |
 * | LOCAL | 70     |
 * | PAT   | 60     |
 * | OIDC  | 50     |
 * | SAML  | 40     |
 * | OAUTH | 30     |
 *
 * **CONCERN-4 (LDAP unavailable 격리)**:
 * CommunicationException 등 서버 장애 시 AuthnResult.Failure(PROVIDER_UNAVAILABLE) 를 반환하며
 * 예외를 throw 하지 않는다. 이는 Provider contract (예외 대신 Failure 반환) 를 준수하기 위함.
 * ProviderRegistry(Task 35) 는 PROVIDER_UNAVAILABLE 결과를 받으면 다른 Provider 시도를 건너뛰어야 한다.
 * LDAP 장애 시 다른 Provider 폴백 여부는 Task 35 격리 정책에서 결정한다.
 * [ProviderUnavailableException] 은 이 클래스 외부에서 LDAP unavailable 을 시그널할 때 사용한다.
 *
 * **보안 (DEVELOPMENT.md §1.1, §1.2)**:
 * - password CharArray 는 finally 블록에서 반드시 wipe (fill ' ')
 * - 로그에 password / DN / externalSubject 미출력
 * - @Profile 미부착 → production 자동 활성
 */
@Service
class LdapProvider(
    private val configService: LdapProviderConfigService,
    private val externalAccountRepo: ExternalAccountRepository,
    private val autoProvisionService: AutoProvisionService,
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
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "SwallowedException")
    override fun authenticate(credential: Credential): AuthnResult {
        require(credential is Credential.LdapBind) { "LdapProvider는 LdapBind 자격증명만 처리합니다." }

        try {
            // 1. 설정 로드
            val (providerId, config) =
                configService.findEnabledLdapConfig()
                    ?: run {
                        log.warn("LDAP provider 미설정 — authn_providers 에 활성 LDAP 행 없음")
                        return AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
                    }

            // 2. bind password 확인 (값은 resolveBindPassword() 로 null 여부만 확인)
            config.resolveBindPassword()
                ?: run {
                    log.warn("LDAP bind password 환경변수 미설정 — env: {}", config.bindPasswordEnv)
                    return AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
                }

            // username 길이 검증 (DEVELOPMENT.md §1.6 — 256자 제한)
            if (credential.username.length > MAX_USERNAME_LENGTH) {
                return AuthnResult.Failure(FailureReason.INVALID_INPUT)
            }

            val now = Instant.now(clock)
            val externalSubject = buildExternalSubject(credential.username, config)

            // 3. 기존 매핑 조회 — DN 형태로 저장되어 있으므로 동일 형태로 조회
            val existing =
                externalAccountRepo.findByProviderIdAndExternalSubject(
                    providerId,
                    externalSubject,
                )

            // 4. 잠금 상태 확인
            if (existing?.lockedUntil?.isAfter(now) == true) {
                return AuthnResult.Failure(FailureReason.ACCOUNT_LOCKED)
            }

            // 5. LDAP 인증
            return try {
                val searchFilter =
                    config.userSearchFilter
                        .replace("{0}", escapeForLdapFilter(credential.username))
                val authenticated =
                    ldapTemplate.authenticate(
                        config.userSearchBase,
                        searchFilter,
                        String(credential.password),
                    )

                if (!authenticated) {
                    return onFailure(existing, config.lockoutPolicy, now, providerId, externalSubject)
                }

                // 6. 성공 처리
                onSuccess(credential.username, existing, providerId, config, now, externalSubject)
            } catch (e: AuthenticationException) {
                log.debug("LDAP 인증 실패 — reason=BadCredentials (detail masked)")
                onFailure(existing, config.lockoutPolicy, now, providerId, externalSubject)
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

    /**
     * 인증 성공 처리 — AutoProvisionService 위임 + last_login_at 갱신.
     *
     * AutoProvisionService.provision 이 users + user_external_accounts UPSERT 를
     * 단일 @Transactional 경계 안에서 처리한다 (EC-17, DATA.md §6).
     * 첫 로그인(existing=null)이든 재로그인(existing!=null)이든 동일하게 UPSERT 를 통해
     * email/displayName 을 최신화한다 (기존 id 보존).
     */
    @Suppress("LongParameterList")
    private fun onSuccess(
        username: String,
        existing: ExternalAccount?,
        providerId: UUID,
        config: LdapConfig,
        now: Instant,
        externalSubject: String,
    ): AuthnResult {
        val btsUsername = "$username@${config.baseDn.removePrefix("dc=").replace(",dc=", ".")}"

        // AutoProvisionService 가 users UPSERT + user_external_accounts UPSERT 를 단일 트랜잭션으로 처리
        val account = autoProvisionService.provision(
            providerId = providerId,
            attrs = LdapProvisionAttrs(
                username = btsUsername,
                email = null,
                displayName = username,
                externalSubject = externalSubject,
                groups = existing?.groups ?: emptyList(),
            ),
        )

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
        @Suppress("UNUSED_PARAMETER") providerId: UUID,
        @Suppress("UNUSED_PARAMETER") externalSubject: String,
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
    private fun buildExternalSubject(
        username: String,
        config: LdapConfig,
    ): String = "uid=$username,${config.userSearchBase},${config.baseDn}"

    internal companion object {
        const val MAX_USERNAME_LENGTH = 256

        /**
         * LDAP 필터 특수문자 escape (LDAP injection 방어 — DEVELOPMENT.md §1.6).
         * RFC 4515 규정 문자: \ * ( ) \0
         * 공백은 RFC 4515 비규정 — escape 시 합법 사용자명 (예: "John Doe") 이 LDAP 검색에서
         * 매칭 실패하는 false-positive 발생. 보안 영향 없으므로 제외.
         */
        fun escapeForLdapFilter(value: String): String =
            value
                .replace("\\", "\\5c")
                .replace("*", "\\2a")
                .replace("(", "\\28")
                .replace(")", "\\29")
    }
}

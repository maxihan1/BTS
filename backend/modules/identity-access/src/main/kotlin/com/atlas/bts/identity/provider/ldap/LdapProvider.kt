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
     * LDAP 바인드 인증 수행 (일반 로그인 — bind + provision).
     *
     * **두 경로의 책임 경계 (FR-AU-08)**:
     * - [authenticate] (일반 로그인): bind + 속성추출([bindAndExtract]) + **provision**(신규/기존 user 생성·매칭).
     *   잠금(lockout) 정책 — 잠금 상태 확인 + 실패 카운트 증가 — 도 이 경로에만 적용된다.
     * - [bindForLinking] (계정 연결): bind + 속성추출만. provision 안 함(이미 로그인된 user 에 DN 을 붙임).
     *
     * Contract: password CharArray 는 예외 여부와 무관하게 finally 블록에서 wipe 됨.
     * 예외를 throw 하지 않고 AuthnResult.Failure 로 반환 (Provider contract).
     */
    @Suppress("ReturnCount")
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

            // 4. 잠금 상태 확인 (일반 로그인 전용)
            if (existing?.lockedUntil?.isAfter(now) == true) {
                return AuthnResult.Failure(FailureReason.ACCOUNT_LOCKED)
            }

            // 5. LDAP bind + 속성추출 (provision 미포함 — bindForLinking 과 공유)
            return when (bindAndExtract(credential, config)) {
                is BindOutcome.Success ->
                    // 6. 성공 처리 — provision (일반 로그인 전용)
                    onSuccess(credential.username, existing, providerId, config, now)
                BindOutcome.InvalidCredentials ->
                    onFailure(existing, config.lockoutPolicy, now, providerId, externalSubject)
                BindOutcome.Unavailable ->
                    AuthnResult.Failure(FailureReason.PROVIDER_UNAVAILABLE)
            }
        } finally {
            // password wipe — 예외 여부 무관 (DEVELOPMENT.md §1.1)
            credential.password.fill(' ')
        }
    }

    /**
     * 계정 연결용 LDAP bind (FR-AU-08 — bind 만, provision 안 함).
     *
     * 일반 로그인([authenticate])과 달리 신규 user/user_external_accounts 를 생성하지 않는다.
     * 이미 로그인된 user 에 LDAP DN 을 연결하는 호출자가 반환된 [LdapProvisionAttrs.externalSubject]
     * (= LDAP DN) 와 groups 를 그대로 사용한다. 잠금 정책·실패 카운트도 적용하지 않는다.
     *
     * provider 해소는 [authenticate] 와 동일하게 활성 LDAP config 를 로드한 뒤, 그 providerId 가
     * 인자 [providerId] 와 일치할 때만 진행한다(멀티 Provider 정합 — FR-AU-06). 비활성·미존재·
     * 다른 providerId·비-LDAP 이면 null 을 반환한다.
     *
     * Contract: [password] CharArray 는 예외 여부와 무관하게 finally 블록에서 wipe 된다.
     * 예외를 throw 하지 않으며 실패(자격증명 오류·서버 장애·설정 부재)는 모두 null 로 표현한다.
     *
     * @param providerId 연결 대상 LDAP authn_providers.id
     * @param username LDAP 사용자명 (uid)
     * @param password 평문 비밀번호 — 호출 후 wipe 됨
     * @return bind 성공 시 [LdapProvisionAttrs], 실패 시 null
     */
    @Suppress("ReturnCount")
    fun bindForLinking(
        providerId: UUID,
        username: String,
        password: CharArray,
    ): LdapProvisionAttrs? {
        try {
            val (configId, config) = configService.findEnabledLdapConfig() ?: return null

            // 인자 providerId 와 활성 LDAP config 의 id 가 일치할 때만 진행 (다른 providerId → null)
            if (configId != providerId) {
                return null
            }

            config.resolveBindPassword() ?: run {
                log.warn("LDAP bind password 환경변수 미설정 — env: {}", config.bindPasswordEnv)
                return null
            }

            if (username.length > MAX_USERNAME_LENGTH) {
                return null
            }

            val credential = Credential.LdapBind(username, password)
            return when (val outcome = bindAndExtract(credential, config)) {
                is BindOutcome.Success -> outcome.attrs
                BindOutcome.InvalidCredentials, BindOutcome.Unavailable -> null
            }
        } finally {
            password.fill(' ')
        }
    }

    /**
     * LDAP bind + 속성추출 (provision 미포함 — [authenticate] 와 [bindForLinking] 공용).
     *
     * 순수하게 LDAP bind 만 수행하고, 성공 시 [LdapProvisionAttrs] 를 채워 반환한다.
     * 잠금 조회·실패 카운트·provision 은 호출자 책임이다(경로별 정책이 다르기 때문).
     * 예외를 throw 하지 않고 [BindOutcome] 으로 결과(성공/자격증명오류/서버장애)를 표현한다.
     *
     * password CharArray wipe 는 진입점([authenticate]/[bindForLinking])의 finally 가 담당한다.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun bindAndExtract(
        credential: Credential.LdapBind,
        config: LdapConfig,
    ): BindOutcome {
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
                BindOutcome.InvalidCredentials
            } else {
                BindOutcome.Success(extractAttrs(credential.username, config))
            }
        } catch (e: AuthenticationException) {
            log.debug("LDAP 인증 실패 — reason=BadCredentials (detail masked)")
            BindOutcome.InvalidCredentials
        } catch (e: CommunicationException) {
            log.warn("LDAP 서버 통신 오류 — {}", e.message)
            BindOutcome.Unavailable
        } catch (e: Exception) {
            log.warn("LDAP 인증 중 예상치 못한 오류 — {}", e.javaClass.simpleName)
            BindOutcome.Unavailable
        }
    }

    /**
     * bind 성공 후 LDAP 속성을 [LdapProvisionAttrs] 로 매핑.
     *
     * externalSubject 는 LDAP DN 형식(uid=...,baseDn)이며 user_external_accounts.external_subject 와 정합한다.
     * username 은 BTS 내부 users.username (uid@baseDomain) 형식으로 구성한다.
     */
    private fun extractAttrs(
        username: String,
        config: LdapConfig,
    ): LdapProvisionAttrs {
        val btsUsername = "$username@${config.baseDn.removePrefix("dc=").replace(",dc=", ".")}"
        return LdapProvisionAttrs(
            username = btsUsername,
            email = null,
            displayName = username,
            externalSubject = buildExternalSubject(username, config),
            groups = emptyList(),
        )
    }

    /**
     * 인증 성공 처리 (일반 로그인 전용) — AutoProvisionService 위임 + last_login_at 갱신.
     *
     * AutoProvisionService.provision 이 users + user_external_accounts UPSERT 를
     * 단일 @Transactional 경계 안에서 처리한다 (EC-17, DATA.md §6).
     * 첫 로그인(existing=null)이든 재로그인(existing!=null)이든 동일하게 UPSERT 를 통해
     * email/displayName 을 최신화한다 (기존 id 보존).
     *
     * 속성 구성은 [extractAttrs] 를 재사용하되 groups 만 재로그인 시 기존 값을 보존한다
     * ([bindForLinking] 과의 중복 제거 — 책임 경계는 provision 호출 여부뿐).
     */
    @Suppress("LongParameterList")
    private fun onSuccess(
        username: String,
        existing: ExternalAccount?,
        providerId: UUID,
        config: LdapConfig,
        now: Instant,
    ): AuthnResult {
        // AutoProvisionService 가 users UPSERT + user_external_accounts UPSERT 를 단일 트랜잭션으로 처리.
        // extractAttrs 는 groups 를 emptyList 로 채우므로, 재로그인 시 기존 매핑의 groups 를 copy 로 보존한다.
        val attrs = extractAttrs(username, config).copy(groups = existing?.groups ?: emptyList())
        val account = autoProvisionService.provision(providerId = providerId, attrs = attrs)

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

    /**
     * [bindAndExtract] 결과 — provision/lockout 무관 순수 bind 결과.
     * - [Success]: bind 성공 + 속성추출 완료.
     * - [InvalidCredentials]: 자격증명 불일치 (enumeration 방지 위해 user 미존재와 동일 취급).
     * - [Unavailable]: LDAP 서버 통신 장애 등 일시적 사용 불가.
     */
    private sealed interface BindOutcome {
        data class Success(val attrs: LdapProvisionAttrs) : BindOutcome

        data object InvalidCredentials : BindOutcome

        data object Unavailable : BindOutcome
    }

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

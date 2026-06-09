// 사용자 본인의 외부 계정 연결/해제/목록을 관리하는 애플리케이션 서비스 (FR-AU-08)

package com.atlas.bts.identity.account

import com.atlas.bts.identity.credential.StoredPasswordCredentialRepository
import com.atlas.bts.identity.provider.AuthnProviderConfigRepository
import com.atlas.bts.identity.provider.AuthnProviderInfo
import com.atlas.bts.identity.provider.ldap.ExternalAccount
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.spi.ProviderType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 한 사용자에게 연결된 외부 계정(external account) 하나의 표시용 뷰 (FR-AU-08).
 *
 * provider 메타데이터([providerName]/[providerType]/[providerEnabled])는
 * authn_providers 에서 결합한다. provider row 가 없으면 null/false 로 채운다.
 *
 * [linkedAt]·[lastLoginAt] 은 ExternalAccount 의 createdAt·lastLoginAt 를 그대로 흘려보낸다
 * (S1 — 데이터 invent 없이 기존 데이터 surface). 한 번도 로그인하지 않은 링크는 [lastLoginAt] 이 null 이다.
 */
data class AccountLinkView(
    val id: UUID,
    val providerId: UUID,
    val providerName: String?,
    val providerType: ProviderType?,
    val providerEnabled: Boolean,
    val externalSubject: String,
    val linkedAt: Instant,
    val lastLoginAt: Instant?,
)

/**
 * 사용자의 연결 목록 + 로컬 비밀번호 보유 여부 (FR-AU-08).
 *
 * [hasLocalPassword] 는 LOCAL 비밀번호도 하나의 로그인 수단임을 프론트가 표시·판단할 때 쓴다.
 */
data class AccountLinks(
    val links: List<AccountLinkView>,
    val hasLocalPassword: Boolean,
)

/**
 * 외부 계정 연결([AccountLinkService.link])의 성공 결과 — 신규/멱등 구분 신호 (FR-AU-08).
 *
 * 컨트롤러가 HTTP status 를 구분(신규 201 / 멱등 200)할 수 있도록 서비스가 어느 경로로 성공했는지
 * 알린다. 두 케이스 모두 동일한 [view] 본문을 담는다(응답 body 는 같고 status 만 다르다).
 *
 * ## 케이스
 * - [Created]: 기존 매핑이 없어 신규 INSERT 한 경우. 컨트롤러가 **201 CREATED** 로 매핑한다.
 * - [AlreadyLinked]: 이미 본인 소유 DN 이라 멱등 no-op(기존 행 그대로 반환)한 경우.
 *   컨트롤러가 **200 OK** 로 매핑한다. 충돌(타인 소유 409)·bind 실패(401)는 결과가 아닌 예외로 분기한다.
 */
sealed interface LinkOutcome {
    /** 연결 결과 표시용 뷰 — 신규/멱등 두 케이스 공통 본문. */
    val view: AccountLinkView

    data class Created(override val view: AccountLinkView) : LinkOutcome

    data class AlreadyLinked(override val view: AccountLinkView) : LinkOutcome
}

/**
 * 사용자 본인의 외부 계정 연결을 관리하는 애플리케이션 서비스 (FR-AU-08).
 *
 * ## 책임 경계
 * 연결 목록 조회·연결(link)·해제(unlink)만 담당한다. 권한 검증(본인 여부)·HTTP 변환·
 * 감사 로그는 컨트롤러 계층(후속 Task)이 처리한다. 다른 BC 를 직접 호출하지 않는다.
 *
 * ## 트랜잭션 (DEVELOPMENT.md §1.2/§6)
 * `@Service` + 클래스 레벨 `@Transactional`. [unlink] 는 advisory lock → 남은 수단 재조회 →
 * delete 를 **단일 트랜잭션**으로 묶는다. lock 후 같은 tx 안에서 읽고 써야 TOCTOU 가 막힌다
 * (advisory-lock-bigint-toctou 선례).
 *
 * ## 보안 (계정 열거 0)
 * link 충돌·unlink not-found 모두 어느 user/계정인지 노출하지 않는 일반화 메시지를 던진다.
 * 로그에 externalSubject(DN)/비밀번호를 남기지 않는다.
 */
@Service
@Transactional
class AccountLinkService(
    private val externalAccountRepository: ExternalAccountRepository,
    private val ldapProvider: LdapProvider,
    private val storedPasswordCredentialRepository: StoredPasswordCredentialRepository,
    private val authnProviderConfigRepository: AuthnProviderConfigRepository,
) {
    /**
     * 사용자 본인에게 연결된 외부 계정 목록을 provider 정보와 함께 반환한다 (FR-AU-08).
     *
     * findByUserId 로 본인 링크만 조회하고, 링크들의 provider_id 를 findByIds 로 한 번에 해소해
     * 이름·유형·활성 여부를 결합한다. hasLocalPassword 는 LOCAL 비밀번호 보유 여부다.
     */
    @Transactional(readOnly = true)
    fun listLinks(userId: UUID): AccountLinks {
        val accounts = externalAccountRepository.findByUserId(userId)
        val providers = authnProviderConfigRepository.findByIds(accounts.map { it.providerId }.toSet())
        val hasLocalPassword = storedPasswordCredentialRepository.findByUserId(userId) != null
        return AccountLinks(
            links = accounts.map { it.toView(providers[it.providerId]) },
            hasLocalPassword = hasLocalPassword,
        )
    }

    /**
     * 외부 계정을 현재 사용자에게 연결한다 — bind 선행 후 충돌 분기 (FR-AU-08).
     *
     * 1. **bind 먼저**(EC12): 자격증명을 외부 IdP 로 직접 검증한다. 실패 시 [AccountLinkAuthException].
     *    bind 성공 attrs.externalSubject 가 곧 연결 대상 식별자(DN)다.
     * 2. **충돌 조회 후 분기**: provisionUser 는 ON CONFLICT(provider_id, external_subject) UPSERT 라
     *    타계정 선점 시 user_id 가 보존된다. 따라서 INSERT 전에 반드시 조회로 분기해
     *    타계정이면 provisionUser 를 호출하지 않는다.
     *    - 매핑 없음 → 신규 INSERT(현재 userId attach) → [LinkOutcome.Created].
     *    - 본인 소유 → 멱등 no-op(기존 반환) → [LinkOutcome.AlreadyLinked].
     *    - 타인 소유 → [AccountLinkConflictException].
     *
     * 신규/멱등을 [LinkOutcome] 로 구분해 컨트롤러가 201/200 을 분기한다(응답 body 는 동일).
     */
    fun link(
        userId: UUID,
        providerId: UUID,
        username: String,
        password: CharArray,
    ): LinkOutcome {
        val attrs =
            ldapProvider.bindForLinking(providerId, username, password)
                ?: throw AccountLinkAuthException()

        return resolveLinkOutcome(userId, providerId, attrs.externalSubject) {
            externalAccountRepository.provisionUser(providerId, attrs.externalSubject, userId, attrs.groups)
        }
    }

    /**
     * 이미 IdP 인증된 SSO 외부 신원을 현재 사용자에 연결한다 — bind 없음 (FR-AU-08b, FR4).
     *
     * LDAP [link] 와 달리 **동기 bind 가 없다**: SAML/OIDC 는 IdP 로 리다이렉트 왕복하므로 신원
     * 인증이 성공 핸들러에서 이미 끝난 상태로 호출된다([externalSubject] 가 곧 인증된 식별자).
     *
     * 1. **lock 먼저**([acquireSubjectLock], EC18): 다중 탭/재시도 콜백의 check-then-insert 구간을
     *    `(providerId, externalSubject)` 단위로 직렬화한다. lock 후 같은 tx 안에서 재조회·INSERT 해야
     *    TOCTOU 가 막힌다(advisory-lock-bigint-toctou 선례).
     * 2. **충돌 조회 후 분기**: 매핑 없음 → [insertLink] 순수 INSERT(현재 userId attach) → [LinkOutcome.Created] /
     *    본인 소유 → 멱등 no-op([LinkOutcome.AlreadyLinked]) / 타인 소유 → [AccountLinkConflictException].
     *
     * **신규 user 생성 금지(confused-deputy 차단)**: `provisionUser`(users UPSERT)를 호출하지 않는다.
     * [userId] 는 이미 로그인한 본인의 id 이며, attach 만 수행한다.
     */
    fun linkExternalSubject(
        userId: UUID,
        providerId: UUID,
        externalSubject: String,
        groups: List<String>,
    ): LinkOutcome {
        externalAccountRepository.acquireSubjectLock(providerId, externalSubject)
        return resolveLinkOutcome(userId, providerId, externalSubject) {
            externalAccountRepository.insertLink(providerId, externalSubject, userId, groups)
        }
    }

    /**
     * 외부 신원 연결의 공통 충돌 분기 — LDAP([link])·SSO([linkExternalSubject]) 공유 (FR-AU-08/08b).
     *
     * `(providerId, externalSubject)` 매핑을 조회해 세 갈래로 분기한다.
     * - 매핑 없음 → [createLink] 로 신규 행 생성(LDAP=UPSERT / SSO=순수 INSERT) → [LinkOutcome.Created].
     * - 본인 소유 → 멱등 no-op(기존 행 그대로) → [LinkOutcome.AlreadyLinked].
     * - 타인 소유 → [AccountLinkConflictException](계정 열거 0).
     *
     * 매핑 없음 경로에서만 [createLink] 를 호출하므로 타계정이 선점한 신원은 INSERT 가 일어나지 않는다.
     */
    private fun resolveLinkOutcome(
        userId: UUID,
        providerId: UUID,
        externalSubject: String,
        createLink: () -> ExternalAccount,
    ): LinkOutcome {
        val existing = externalAccountRepository.findByProviderIdAndExternalSubject(providerId, externalSubject)
        val provider = authnProviderConfigRepository.findByIds(setOf(providerId))[providerId]

        return when {
            existing == null -> LinkOutcome.Created(createLink().toView(provider))
            existing.userId == userId -> LinkOutcome.AlreadyLinked(existing.toView(provider))
            else -> throw AccountLinkConflictException()
        }
    }

    /**
     * 외부 계정 연결을 해제한다 — advisory lock 후 남은 수단 재조회 → 삭제 (FR-AU-08).
     *
     * 1. **lock 먼저**(TOCTOU 직렬화): 같은 사용자의 동시 unlink 를 직렬화한다.
     * 2. **남은 수단 재조회**: lock 안에서 findByUserId 로 다시 읽어 [remainingMethods] 를 센다.
     *    0이면 [AccountLinkLastMethodException] — 영구 락 방지.
     * 3. **소유 검증 삭제**: deleteByIdAndUserId 가 0행이면 [AccountLinkNotFoundException]
     *    (타인 소유·미존재를 구분하지 않음 — 존재 probe 방지).
     */
    fun unlink(
        userId: UUID,
        linkId: UUID,
    ) {
        externalAccountRepository.acquireUserLock(userId)

        if (remainingMethodsAfterRemoving(userId, linkId) < MIN_REMAINING_METHODS) {
            throw AccountLinkLastMethodException()
        }

        val deleted = externalAccountRepository.deleteByIdAndUserId(linkId, userId)
        if (deleted == 0) {
            throw AccountLinkNotFoundException()
        }
    }

    /**
     * [linkId] 를 제거했다고 가정했을 때 남는 로그인 수단 수를 센다 (FR-AU-08).
     *
     * 남은 수단 = (linkId 를 제외한 **활성 provider** 링크 수) + (로컬 비밀번호 보유 ? 1 : 0).
     * **비활성 provider 링크는 제외한다(C4 — 영구 락 방지)**: 비활성 provider 로는 로그인할 수 없으므로
     * 그것만 남기고 해제를 막으면 사용자가 영구히 잠긴다.
     */
    private fun remainingMethodsAfterRemoving(
        userId: UUID,
        linkId: UUID,
    ): Int {
        val accounts = externalAccountRepository.findByUserId(userId)
        val providers = authnProviderConfigRepository.findByIds(accounts.map { it.providerId }.toSet())
        val remainingEnabledLinks =
            accounts.count { it.id != linkId && providers[it.providerId]?.enabled == true }
        val localMethod = if (storedPasswordCredentialRepository.findByUserId(userId) != null) 1 else 0
        return remainingEnabledLinks + localMethod
    }

    private fun ExternalAccount.toView(provider: AuthnProviderInfo?): AccountLinkView =
        AccountLinkView(
            id = id,
            providerId = providerId,
            providerName = provider?.name,
            providerType = provider?.type,
            providerEnabled = provider?.enabled ?: false,
            externalSubject = externalSubject,
            linkedAt = createdAt,
            lastLoginAt = lastLoginAt,
        )

    private companion object {
        /** 해제 후 최소 1개의 로그인 수단이 남아야 한다 (영구 락 방지). */
        const val MIN_REMAINING_METHODS = 1
    }
}

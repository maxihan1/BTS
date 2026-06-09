// 민감동작(연결/해제) 전 재인증 챌린지를 검증해 세션에 step-up 윈도우를 부여하는 서비스 (FR-AU-08)

package com.atlas.bts.identity.account

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 재인증(re-auth) 챌린지에 사용할 인증 수단 (FR-AU-08).
 *
 * - [LOCAL]: 로컬 비밀번호로 본인 확인.
 * - [LDAP]: LDAP bind 로 본인 확인. 단, **현재 사용자에게 이미 연결된 DN** 으로만 가능하다(EC9).
 *
 * SSO(OIDC/SAML) 재인증은 IdP 리다이렉트가 필요해 동기 챌린지로 표현할 수 없으므로
 * 여기서 다루지 않고 FR-AU-08b(SSO step-up) 로 위임한다.
 */
enum class ReauthMethod {
    LOCAL,
    LDAP,
}

/**
 * 재인증 챌린지가 실패했을 때 던지는 도메인 예외 (FR-AU-08).
 *
 * HTTP 401 의미. 컨트롤러 계층(후속 Task)이 상태 매핑한다.
 * 메시지는 사용자명/DN 등 식별 정보를 노출하지 않는다(계정 열거 0).
 *
 * 이름은 account 패키지 내 고유다 — 동명 예외 교차패키지 상태코드 변질 방지
 * (duplicate-exception-name-cross-package-status 선례).
 */
class ReauthChallengeFailedException :
    RuntimeException("재인증에 실패했습니다.")

/**
 * 민감 동작(계정 연결/해제) 직전의 "재인증 챌린지"를 검증하는 애플리케이션 서비스 (FR-AU-08).
 *
 * 사용자가 제출한 자격증명을 인증 수단([ReauthMethod])별로 검증하고, 성공하면
 * 현재 세션([sid])에 [StepUpService] step-up 윈도우를 부여한다. 이후 윈도우 동안만
 * 민감 동작이 추가 인증 없이 허용된다.
 *
 * ## sid 신뢰 출처 (FR9)
 * [sid] 는 호출자(컨트롤러)가 인증된 JWT 의 `sid` 클레임에서 추출해 전달한다.
 * 이 서비스는 받은 [sid] 의 진위를 검증하지 않고 그대로 [StepUpService.grant] 에 사용한다.
 * sid 가 현재 인증 주체의 것인지 확인하는 책임은 전적으로 컨트롤러에 있다.
 *
 * ## EC9 — LDAP/SSO 재인증 제약
 * LDAP 경로는 bind 성공만으로는 부족하다. bind 로 확인된 DN 이 **현재 사용자에게 이미
 * 연결된** 외부 계정이어야 한다. 타 신원·미연결 DN 으로는 재인증할 수 없다. 이는 LDAP bind
 * 만으로 임의 신원을 빌려 step-up 을 얻는 우회를 막는다. SSO(SAML/OIDC) 재인증
 * ([reauthenticateSso], FR-AU-08b)도 동일 제약을 따른다 — IdP 인증 성공만으로는 부족하고
 * 돌아온 신원이 현재 사용자에게 이미 연결돼 있어야 grant 한다([grantIfSubjectOwnedBy] 공통 검증).
 *
 * ## 보안 (DEVELOPMENT.md §1.1)
 * - 실패는 수단·원인을 구분하지 않는 일반화 예외([ReauthChallengeFailedException])로 던진다.
 * - 평문 비밀번호는 [CharArray] 로 받는다. LOCAL 은 [LocalCredentialService.verifyForUser] 가
 *   자체 wipe 하고, LDAP 은 검증 함수가 wipe 를 보장하더라도 이 서비스가 finally 로 직접 wipe 해
 *   방어 계층을 둔다.
 * - 로그에 DN/비밀번호/사용자명을 남기지 않는다.
 */
@Service
@Transactional(readOnly = true)
class ReauthService(
    private val stepUpService: StepUpService,
    private val localCredentialService: LocalCredentialService,
    private val ldapProvider: LdapProvider,
    private val externalAccountRepository: ExternalAccountRepository,
) {
    /**
     * 로컬 비밀번호로 재인증한다 (FR-AU-08).
     *
     * [LocalCredentialService.verifyForUser] 가 timing-attack 방어 검증과 평문 wipe 를
     * 모두 수행한다. 일치하면 [StepUpService.grant] 로 [sid] 에 step-up 윈도우를 연다.
     * 불일치 시 [ReauthChallengeFailedException] 을 던진다(grant 미호출).
     *
     * **LOCAL lockout 없음**: BTS 는 LOCAL 로그인에 잠금 인프라가 없으므로 재인증도 동일하게
     * 잠금 없이 기존 LOCAL 검증과 같은 보호 수준을 따른다.
     *
     * @param userId 재인증 대상 사용자 식별자.
     * @param sid 컨트롤러가 JWT sid 클레임에서 추출해 전달한 현재 세션 식별자.
     * @param plain 평문 비밀번호 — 호출 후 wipe 됨([verifyForUser] 가 처리).
     */
    fun reauthenticateLocal(
        userId: UUID,
        sid: UUID,
        plain: CharArray,
    ) {
        if (!localCredentialService.verifyForUser(userId, plain)) {
            throw ReauthChallengeFailedException()
        }
        stepUpService.grant(sid)
    }

    /**
     * LDAP bind 로 재인증한다 — EC9 제약 적용 (FR-AU-08).
     *
     * [LdapProvider.bindForLinking] 으로 bind 한다. null 이면 실패다. bind 성공 시
     * 반환된 DN(externalSubject)이 **현재 [userId] 에 이미 연결된** 외부 계정인지 확인한다(EC9).
     * 미연결·타 user 연결이면 실패다.
     * 두 조건을 모두 만족할 때만 [StepUpService.grant] 로 step-up 윈도우를 연다.
     *
     * @param userId 재인증 대상 사용자 식별자.
     * @param sid 컨트롤러가 JWT sid 클레임에서 추출해 전달한 현재 세션 식별자.
     * @param providerId 재인증에 사용할 LDAP authn_providers.id.
     * @param username LDAP 사용자명(uid).
     * @param password 평문 비밀번호 — 이 메서드의 finally 에서 직접 wipe 됨.
     */
    fun reauthenticateLdap(
        userId: UUID,
        sid: UUID,
        providerId: UUID,
        username: String,
        password: CharArray,
    ) {
        try {
            val attrs =
                ldapProvider.bindForLinking(providerId, username, password)
                    ?: throw ReauthChallengeFailedException()

            grantIfSubjectOwnedBy(userId, sid, providerId, attrs.externalSubject)
        } finally {
            // verifyForUser 와 달리 bindForLinking 외 경로(예외 등)에서도 평문이 남지 않도록 직접 wipe.
            password.fill(' ')
        }
    }

    /**
     * SSO(SAML/OIDC) 외부 신원으로 재인증한다 — EC9 제약 적용 (FR-AU-08b Task 5).
     *
     * LDAP 과 달리 동기 bind 가 없다. IdP 리다이렉트 왕복으로 **이미 인증이 끝난** 외부 신원
     * `(providerId, externalSubject)` 을 호출자(콜백 핸들러)가 넘긴다. 이 메서드는 그 신원이
     * **현재 [userId] 에 이미 연결된** 외부 계정인지만 확인한다(EC9 동형). 일치하면
     * [StepUpService.grant] 로 step-up 윈도우를 연다. 미연결(EC9)·타 user 연결(EC8)이면
     * grant 없이 [ReauthChallengeFailedException] 을 던진다 — 타 신원으로 우회 grant 를 막는다.
     *
     * bind 를 호출하지 않으므로 평문 비밀번호 인자가 없다(IdP 가 인증을 선행 완료).
     *
     * @param userId 재인증 대상 사용자 식별자(intent.userId).
     * @param sid 컨트롤러가 JWT sid 클레임에서 추출해 intent 에 복사한 현재 세션 식별자.
     * @param providerId 재인증에 사용한 SSO authn_providers.id.
     * @param externalSubject IdP 가 반환한 외부 신원(OIDC sub / SAML NameID). 로그 출력 금지(PII).
     */
    fun reauthenticateSso(
        userId: UUID,
        sid: UUID,
        providerId: UUID,
        externalSubject: String,
    ) {
        grantIfSubjectOwnedBy(userId, sid, providerId, externalSubject)
    }

    /**
     * `(providerId, externalSubject)` 외부 신원이 [userId] 에 이미 연결돼 있으면 step-up 을 부여한다 (EC9).
     *
     * LDAP/SSO 재인증의 공통 EC9 검증 — bind 성공(LDAP)이나 IdP 인증 성공(SSO)만으로는 부족하고,
     * 확인된 신원이 **현재 사용자에게 이미 연결된** 외부 계정이어야 한다. 미연결·타 user 연결이면
     * [ReauthChallengeFailedException] 을 던진다(grant 미호출). 임의 신원을 빌려 step-up 을 얻는 우회 차단.
     */
    private fun grantIfSubjectOwnedBy(
        userId: UUID,
        sid: UUID,
        providerId: UUID,
        externalSubject: String,
    ) {
        val linkedOwner =
            externalAccountRepository
                .findByProviderIdAndExternalSubject(providerId, externalSubject)
                ?.userId
        if (linkedOwner != userId) {
            throw ReauthChallengeFailedException()
        }
        stepUpService.grant(sid)
    }
}

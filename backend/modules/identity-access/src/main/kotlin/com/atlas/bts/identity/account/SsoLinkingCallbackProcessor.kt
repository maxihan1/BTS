// SSO 콜백을 연결/재인증 모드로 분기 처리하는 컴포넌트 — fail-closed, 고정 복귀경로 (FR-AU-08b)

package com.atlas.bts.identity.account

import com.atlas.bts.identity.spi.ProviderType
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * SAML/OIDC 성공 핸들러가 콜백 시 호출하는 "연결 모드 분기" 처리기 (FR-AU-08b, FR3).
 *
 * ## 호출 계약 (fail-closed)
 * 성공 핸들러는 issueTokens(세션/JWT 발급) **진입 이전에** [process] 를 호출하고, **true 면 즉시
 * return** 해 일반 로그인 발급 경로로 진입하지 않아야 한다. [process] 가 intent 를 소비했다면
 * 항상 true 를 반환하므로(성공/충돌/실패/불일치 무관 — 모두 리다이렉트로 종결), 분기 누락 시에도
 * 발급이 일어나지 않는 fail-closed 가 성립한다. intent 가 없을 때만 false 를 반환해 핸들러가 기존
 * JIT 로그인을 계속하게 한다(EC1 회귀 무변경).
 *
 * ## 모드 분기
 * - LINK: intent.registrationId·providerType 둘 다 콜백과 일치해야 한다(불일치 → `?link=error`, EC4).
 *   일치 시 [AccountLinkService.linkExternalSubject](충돌 규칙·신규 user 생성 없음)를 호출해
 *   Created → `?link=success` / AlreadyLinked → `?link=already_linked` /
 *   [AccountLinkConflictException] → `?link=conflict`(attach 0). **새 세션/JWT/provision 미발생**.
 * - REAUTH: 동형으로 registrationId·providerType 일치 검증(C3, 불일치 → `?reauth=failed`) →
 *   [ReauthService.reauthenticateSso](EC9 — 돌아온 신원이 본인 링크와 일치할 때만 grant)를 호출해
 *   성공 → `?reauth=success` / 예외 → `?reauth=failed`.
 *
 * ## open-redirect 0 (EC14)
 * 복귀 대상은 **서버 고정 설정 경로**([SETTINGS_PATH]) + status 쿼리뿐이다. 사용자 입력(RelayState/
 * returnTo)을 echo 하지 않으므로 open-redirect 가 없다. external_subject 등 PII 는 쿼리에 싣지 않는다.
 */
@Component
class SsoLinkingCallbackProcessor(
    private val intentStore: SsoLinkingIntentStore,
    private val accountLinkService: AccountLinkService,
    private val reauthService: ReauthService,
) {
    /**
     * 콜백을 연결/재인증 모드로 분기 처리한다.
     *
     * @param session 콜백 시점의 HttpSession(intent 보존 슬롯).
     * @param providerType 콜백을 만든 SSO 체인의 유형(SAML/OIDC). intent.providerType 와 대조한다.
     * @param registrationId 인증된 provider 의 registration_id. intent.registrationId 와 대조한다.
     * @param providerId 콜백 시점 enabled 재해소로 얻은 authn_providers.id(성공 핸들러가 전달).
     * @param externalSubject IdP 가 반환한 외부 신원(OIDC sub / SAML NameID). 로그/쿼리 출력 금지(PII).
     * @param groups IdP 그룹 클레임(연결 시 함께 저장).
     * @param response 리다이렉트를 쓸 응답.
     * @return intent 를 소비해 처리(리다이렉트)했으면 true, intent 가 없어 일반 로그인에 위임하면 false.
     */
    @Suppress("LongParameterList")
    fun process(
        session: HttpSession,
        providerType: ProviderType,
        registrationId: String,
        providerId: UUID,
        externalSubject: String,
        groups: List<String>,
        response: HttpServletResponse,
    ): Boolean {
        val intent = intentStore.consume(session) ?: return false

        val status =
            when (intent.mode) {
                SsoLinkingIntent.Mode.LINK ->
                    handleLink(intent, providerType, registrationId, providerId, externalSubject, groups)
                SsoLinkingIntent.Mode.REAUTH ->
                    handleReauth(intent, providerType, registrationId, providerId, externalSubject)
            }
        response.sendRedirect(SETTINGS_PATH + status)
        return true
    }

    /**
     * LINK 모드 처리 — registrationId·providerType 일치 검증 후 연결 시도, status 쿼리를 반환한다.
     *
     * 불일치(EC4)면 [LINK_ERROR]. 일치 시 [AccountLinkService.linkExternalSubject] 결과를
     * [LINK_SUCCESS]/[LINK_ALREADY_LINKED] 로 매핑하고 [AccountLinkConflictException] 은 [LINK_CONFLICT] 로
     * catch 한다(attach 0).
     */
    @Suppress("LongParameterList")
    private fun handleLink(
        intent: SsoLinkingIntent,
        providerType: ProviderType,
        registrationId: String,
        providerId: UUID,
        externalSubject: String,
        groups: List<String>,
    ): String {
        if (!intent.matches(providerType, registrationId)) return LINK_ERROR
        return try {
            when (accountLinkService.linkExternalSubject(intent.userId, providerId, externalSubject, groups)) {
                is LinkOutcome.Created -> LINK_SUCCESS
                is LinkOutcome.AlreadyLinked -> LINK_ALREADY_LINKED
            }
        } catch (_: AccountLinkConflictException) {
            LINK_CONFLICT
        }
    }

    /**
     * REAUTH 모드 처리 — registrationId·providerType 일치 검증 후 재인증 시도, status 쿼리를 반환한다.
     *
     * 불일치(C3)·[ReauthChallengeFailedException] 모두 [REAUTH_FAILED]. 일치하고 본인 링크면
     * [ReauthService.reauthenticateSso] 가 grant 하고 [REAUTH_SUCCESS] 를 반환한다. sid 부재는
     * 의도상 발생하지 않으나(REAUTH intent 는 sid 동반) 방어적으로 실패 처리한다.
     *
     * ReturnCount 억제 — 불일치/sid 부재 early return 이 중첩 if 보다 가독성 우수(DEVELOPMENT.md §2.3).
     */
    @Suppress("ReturnCount")
    private fun handleReauth(
        intent: SsoLinkingIntent,
        providerType: ProviderType,
        registrationId: String,
        providerId: UUID,
        externalSubject: String,
    ): String {
        if (!intent.matches(providerType, registrationId)) return REAUTH_FAILED
        val sid = intent.sid ?: return REAUTH_FAILED
        return try {
            reauthService.reauthenticateSso(intent.userId, sid, providerId, externalSubject)
            REAUTH_SUCCESS
        } catch (_: ReauthChallengeFailedException) {
            REAUTH_FAILED
        }
    }

    /** intent 의 providerType·registrationId 가 콜백과 모두 일치하는지 확인한다(EC4/C3). */
    private fun SsoLinkingIntent.matches(
        providerType: ProviderType,
        registrationId: String,
    ): Boolean = this.providerType == providerType && this.registrationId == registrationId

    private companion object {
        /** 서버 고정 복귀 경로 — 사용자 입력 아님(open-redirect 0, EC14). */
        const val SETTINGS_PATH = "/settings/account-links"

        const val LINK_SUCCESS = "?link=success"
        const val LINK_ALREADY_LINKED = "?link=already_linked"
        const val LINK_CONFLICT = "?link=conflict"
        const val LINK_ERROR = "?link=error"
        const val REAUTH_SUCCESS = "?reauth=success"
        const val REAUTH_FAILED = "?reauth=failed"
    }
}

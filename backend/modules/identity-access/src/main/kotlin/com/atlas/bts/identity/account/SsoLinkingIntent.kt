// SSO(SAML/OIDC) 연결/재인증 의도를 IdP 왕복 동안 보존하는 단명 서버측 상태 (FR-AU-08b)

package com.atlas.bts.identity.account

import com.atlas.bts.identity.spi.ProviderType
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/**
 * SSO 연결 인텐트 (LinkingIntent).
 *
 * 로그인 사용자가 SSO(SAML/OIDC) 연결/재인증을 개시할 때 만드는 **단명 서버측 상태**다.
 * IdP(Identity Provider) 로 리다이렉트 왕복(수 분)을 살아남아야 하므로 HttpSession 속성에
 * 보존되며, 콜백 성공 핸들러에서 **1회용으로 소비**된다([SsoLinkingIntentStore]).
 *
 * ## URL 노출 금지
 * userId/sid 등은 URL(RelayState/state 쿼리)에 싣지 않는다(reflection/탈취 차단).
 * HttpSession 피기백으로만 보존한다(Maxi 결정 D-a).
 *
 * ## 직렬화
 * HttpSession 이 톰캣 세션 직렬화(예 재시작/클러스터) 시 attribute 를 직렬화할 수 있어
 * [Serializable] 을 구현한다. 모든 필드(UUID/String/Instant/enum)가 직렬화 가능하다.
 *
 * ## 단명성
 * [expiresAt] 는 발급 시점 + ≤5분(step-up 윈도우와 정합, EC19)으로 설정한다.
 * 만료된 intent 는 [SsoLinkingIntentStore.consume] 에서 무효(null)로 처리된다.
 *
 * @property mode 연결 모드. LINK(신원 attach) 또는 REAUTH(step-up 획득).
 * @property userId 인텐트를 개시한 로그인 사용자의 식별자.
 * @property sid REAUTH 모드에서 grant 대상이 되는 세션 식별자(JWT `sid` 클레임 출처).
 *               LINK 모드에서는 null.
 * @property registrationId 연결/재인증 대상 SSO provider 의 registration_id.
 * @property providerType SSO 유형(SAML 또는 OIDC). LOCAL/LDAP/PAT/OAUTH 는 SSO 가 아니라 불가.
 * @property expiresAt 인텐트 만료 시각. 이후 콜백은 거부된다.
 */
data class SsoLinkingIntent(
    val mode: Mode,
    val userId: UUID,
    val sid: UUID?,
    val registrationId: String,
    val providerType: ProviderType,
    val expiresAt: Instant,
) : Serializable {
    /** 인텐트 모드 — 연결(LINK) 또는 SSO 재인증(REAUTH). */
    enum class Mode {
        /** SSO 외부 신원을 현재 사용자 계정에 attach 한다(새 세션/JWT 미발급). */
        LINK,

        /** 이미 연결된 SSO 로 본인 확인 후 step-up 윈도우를 grant 한다. */
        REAUTH,
    }

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}

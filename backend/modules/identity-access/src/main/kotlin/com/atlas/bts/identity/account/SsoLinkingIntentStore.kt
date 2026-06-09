// SSO 연결 인텐트를 HttpSession 에 1회용으로 보존/소비하는 스토어 (FR-AU-08b)

package com.atlas.bts.identity.account

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * [SsoLinkingIntent] 를 HttpSession 에 저장하고 1회용으로 소비하는 스토어.
 *
 * ## 보존 메커니즘 (Maxi 결정 D-a)
 * 연결 시작 XHR 가 [put] 으로 intent 를 HttpSession 속성에 저장하면, IdP 왕복(수 분)
 * 동안 같은 세션이 재사용되어 콜백 성공 핸들러가 [consume] 으로 회수한다. userId/sid 를
 * URL 에 싣지 않으므로 reflection/탈취가 차단된다.
 *
 * ## 1회용 소비
 * [consume] 은 get 직후 무조건 속성을 제거한다(성공/만료 무관). 두 번째 호출은 항상 null 이라
 * 중복/재시도 콜백이 같은 intent 를 재소비하지 못한다. 만료된 intent 도 제거되어 잔류하지 않는다.
 *
 * ## 만료 (단명 ≤5분)
 * [consume] 은 [SsoLinkingIntent.expiresAt] 를 주입된 [clock] 으로 검사한다. 만료 경계는
 * 닫힘 비교(`expiresAt > now` 일 때만 유효)라 정확히 만료 시각이면 무효(null)로 처리한다
 * ([StepUpService] 와 동일 경계 규약). 시각 비교를 핸들러에서 직접 하지 않고 이 [clock] 으로만
 * 하므로 테스트는 `Clock.fixed` 로 고정/전진할 수 있다(time-bomb 회귀 방지).
 *
 * @property clock 시각 출처. 기본값은 시스템 UTC 시계.
 */
@Component
class SsoLinkingIntentStore(
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 주어진 세션에 SSO 연결 인텐트를 저장한다(기존 슬롯이 있으면 덮어쓴다, last-wins, EC13).
     *
     * @param session 연결 시작 XHR 가 만든(또는 재사용하는) HttpSession.
     * @param intent 보존할 단명 인텐트.
     */
    fun put(
        session: HttpSession,
        intent: SsoLinkingIntent,
    ) {
        session.setAttribute(ATTRIBUTE_KEY, intent)
    }

    /**
     * 세션에서 인텐트를 1회용으로 회수한다.
     *
     * 저장된 속성을 읽은 뒤 **무조건 제거**하고(성공/만료 무관, 1회용·재소비 차단),
     * 만료된 경우 null 을 반환한다. 저장된 적이 없으면 null.
     *
     * @param session 콜백 성공 핸들러가 보는 HttpSession.
     * @return 유효한(미만료) 인텐트, 없거나 만료면 null.
     */
    fun consume(session: HttpSession): SsoLinkingIntent? {
        val intent = session.getAttribute(ATTRIBUTE_KEY) as? SsoLinkingIntent
        session.removeAttribute(ATTRIBUTE_KEY)
        if (intent == null) return null
        // 만료 경계는 닫힘 — expiresAt 가 현재 시각보다 엄격히 클 때만 유효.
        return if (intent.expiresAt > clock.instant()) intent else null
    }

    companion object {
        /** HttpSession 속성 키 — 다른 속성과 충돌하지 않도록 FQCN 기반. */
        const val ATTRIBUTE_KEY: String = "com.atlas.bts.identity.account.SSO_LINKING_INTENT"
    }
}

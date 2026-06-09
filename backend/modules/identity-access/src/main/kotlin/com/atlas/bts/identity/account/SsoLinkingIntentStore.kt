// SSO 연결 인텐트를 HttpSession 에 1회용으로 보존/소비하는 스토어 (FR-AU-08b)

package com.atlas.bts.identity.account

import jakarta.servlet.http.HttpSession
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * [SsoLinkingIntent] 를 HttpSession 에 저장하고 1회용으로 소비하는 스토어.
 *
 * RED 골격 — 아직 미구현(consume 항상 null).
 */
@Component
class SsoLinkingIntentStore(
    private val clock: Clock = Clock.systemUTC(),
) {
    /** RED 골격. */
    fun put(
        session: HttpSession,
        intent: SsoLinkingIntent,
    ) {
        // 미구현
    }

    /** RED 골격 — 항상 null. */
    fun consume(session: HttpSession): SsoLinkingIntent? = null
}

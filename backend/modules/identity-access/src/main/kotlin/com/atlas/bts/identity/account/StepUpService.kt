// 계정 연결 민감동작용 step-up(재인증) 윈도우를 sid 기준으로 관리하는 서비스 (FR-AU-08)
package com.atlas.bts.identity.account

import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 계정 연결/해제 같은 민감 동작 직전에 재인증(step-up)이 통과됐음을
 * 세션 식별자(sid) 기준으로 일정 시간(윈도우) 동안 기억하는 서비스 (FR-AU-08).
 *
 * ## 동작 모델
 * - 윈도우형이다. [grant] 호출 시점부터 [STEP_UP_TTL] 동안 [isValid] 가 true 를 반환한다.
 *   per-action 소비형(한 번 쓰면 사라짐)이 아니므로, 윈도우 안에서는 여러 민감 동작을
 *   추가 재인증 없이 수행할 수 있다.
 * - sid 는 호출자가 인증된 JWT 의 `sid` 클레임에서 추출해 전달한다. 이 서비스는
 *   sid 의 진위를 검증하지 않으며, "이 세션이 최근 재인증했는가"만 추적한다.
 *
 * ## fail-safe 정책
 * - 저장은 인메모리([ConcurrentHashMap])다. 단일 호스트 운영이라 충분하며,
 *   프로세스 재시작 시 모든 윈도우가 소실되어 재인증을 강제한다 (열린 채로 남지 않음).
 * - 만료 경계는 닫힘(>) 비교다. `expiresAt` 가 현재 시각보다 **엄격히 클 때만** 유효하므로,
 *   정확히 TTL 경계 시점은 만료로 처리한다. 경계 모호성을 항상 "차단" 쪽으로 해소한다.
 *
 * ## 시각 의존성
 * 모든 시각 비교는 주입된 [Clock] 으로 한다. 핸들러에서 `Instant.now()` 를 직접 쓰면
 * 특정 날짜에 깨지는 time-bomb 이 되므로, 테스트는 `Clock.fixed` 로 시간을 고정/전진한다.
 *
 * @property clock 시각 출처. 기본값은 시스템 UTC 시계.
 */
@Service
class StepUpService(
    private val clock: Clock = Clock.systemUTC(),
) {
    /** sid(UUID) → 윈도우 만료 시각(expiresAt). 만료된 항목은 [isValid] 에서 무효 취급된다. */
    private val windows: ConcurrentHashMap<UUID, Instant> = ConcurrentHashMap()

    /**
     * 주어진 sid 에 대해 step-up 윈도우를 연다(또는 갱신한다).
     *
     * 만료 시각을 `현재 시각 + [STEP_UP_TTL]` 로 설정한다. 같은 sid 로 다시 호출하면
     * 윈도우가 현재 시각 기준으로 새로 시작된다.
     *
     * @param sid 재인증을 마친 세션의 식별자.
     */
    fun grant(sid: UUID) {
        windows[sid] = clock.instant().plus(STEP_UP_TTL)
    }

    /**
     * 주어진 sid 가 현재 유효한 step-up 윈도우 안에 있는지 반환한다.
     *
     * 윈도우형이라 이 검사는 윈도우를 소비하지 않는다. 유효한 동안 몇 번 호출해도 true 다.
     * grant 이력이 없거나 윈도우가 만료됐으면 false 를 반환한다(fail-safe).
     * 경계 비교는 닫힘(`expiresAt > now`)이라 정확히 TTL 경계 시점은 만료로 처리한다.
     *
     * @param sid 검사할 세션의 식별자.
     * @return 유효 윈도우 안이면 true, 미발급·만료면 false.
     */
    fun isValid(sid: UUID): Boolean {
        val expiresAt = windows[sid] ?: return false
        return expiresAt > clock.instant()
    }

    companion object {
        /**
         * step-up 윈도우의 수명(TTL).
         *
         * 재인증 후 이 시간 동안만 민감 동작을 추가 인증 없이 허용한다.
         * 짧을수록 탈취 세션의 악용 창이 줄지만 사용자 마찰이 는다. 5분은 그 절충점이다.
         */
        val STEP_UP_TTL: Duration = Duration.ofMinutes(5)
    }
}

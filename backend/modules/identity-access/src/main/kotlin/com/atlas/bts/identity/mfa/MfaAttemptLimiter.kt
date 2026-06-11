// 사용자별 MFA 코드 실패 횟수를 제한하는 brute-force 방어 컴포넌트 (FR-MF-01 Task 7)

package com.atlas.bts.identity.mfa

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * 사용자별 연속 MFA 코드 실패 횟수를 제한하는 brute-force 방어 컴포넌트.
 *
 * ## 정책
 * 한 사용자가 [WINDOW] 안에 [MAX_FAILURES] 회 이상 MFA 코드 검증에 실패하면 [isBlocked] 가 `true`
 * 를 돌려 추가 시도를 거부한다. MFA 코드 검증에 성공하면 호출처가 [reset] 으로 카운터를 비워야
 * 한다. 실패 카운터는 마지막 기록 시점 기준 [WINDOW] 가 지나면(`expireAfterWrite`) 자동 소멸하므로,
 * window 밖의 산발적 실패는 누적되지 않고 차단도 자연 해제된다.
 *
 * ## 메모리 only — 영구 lockout 이연 (FR-MF-04)
 * 카운터는 Caffeine in-memory 캐시에만 존재한다. BTS 는 단일 호스트 배포이므로 프로세스 메모리로
 * 충분하며, 프로세스 재시작 시 카운터가 초기화된다. **영구 계정 잠금**(persisted lockout)·관리자
 * 해제 흐름은 본 컴포넌트 범위가 아니라 **FR-MF-04** 로 이연한다.
 *
 * ## 시각 의존 — Ticker 주입
 * window 만료 판단은 Caffeine 의 [Ticker] 가 돌려주는 나노초 값을 기준으로 한다. `Instant.now()` 를
 * 하드코딩하면 특정 시각에 깨지는 time-bomb 이 되므로, 테스트에서 수동 진행 가능한 [Ticker] 를
 * 주입해 window 만료를 시뮬레이션한다 (메모리 authcontroller-revokesession-timebomb).
 *
 * ## 보안 주의
 * userId 등 식별자는 로깅하지 않는다(§1.1.2). 상태를 보유하는 싱글톤 빈이다(Task 8 `MfaService`
 * 가 주입).
 *
 * ## 참조
 * - FR-MF-01 Task 7 (Caffeine rate-limit)
 * - SDD §19.7 (2FA)
 */
@Component
class MfaAttemptLimiter(
    ticker: Ticker = Ticker.systemTicker(),
) {
    /** 사용자별 연속 실패 횟수. 마지막 기록 시점 기준 [WINDOW] 후 자동 소멸. */
    private val failures: Cache<UUID, Int> =
        Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .ticker(ticker)
            .build()

    /**
     * 사용자의 MFA 코드 검증 실패를 1회 기록한다.
     *
     * @param userId 실패한 사용자 UUID.
     */
    fun recordFailure(userId: UUID) {
        val current = failures.getIfPresent(userId) ?: 0
        failures.put(userId, current + 1)
    }

    /**
     * 사용자의 실패 카운터를 비운다. MFA 코드 검증에 성공한 직후 호출해야 한다.
     *
     * @param userId 초기화할 사용자 UUID.
     */
    fun reset(userId: UUID) {
        failures.invalidate(userId)
    }

    /**
     * 사용자가 현재 [WINDOW] 안에서 [MAX_FAILURES] 회 이상 실패해 차단 상태인지 여부.
     *
     * @param userId 확인할 사용자 UUID.
     * @return 차단 상태면 `true`.
     */
    fun isBlocked(userId: UUID): Boolean {
        val current = failures.getIfPresent(userId) ?: 0
        return current >= MAX_FAILURES
    }

    internal companion object {
        /** 차단 임계값 — 연속 실패가 이 횟수 이상이면 차단. */
        const val MAX_FAILURES = 5

        /** 실패 카운터 누적 window — 마지막 실패 후 이 시간이 지나면 카운터 소멸. */
        val WINDOW: Duration = Duration.ofMinutes(5)
    }
}

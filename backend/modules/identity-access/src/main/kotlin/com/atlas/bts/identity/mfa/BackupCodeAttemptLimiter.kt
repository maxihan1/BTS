// 사용자별 MFA 백업 코드 실패 횟수를 TOTP와 독립적으로 제한하는 brute-force 방어 컴포넌트 (FR-MF-02 Task 5)

package com.atlas.bts.identity.mfa

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * 사용자별 연속 **백업 코드** 검증 실패 횟수를 제한하는 brute-force 방어 컴포넌트 (FR-MF-02 Task 5).
 *
 * ## 왜 [MfaAttemptLimiter] 와 별개 빈인가 — 독립 카운터 (보안 결정)
 * TOTP 코드 실패와 백업 코드 실패를 **하나의** 카운터로 합치면, TOTP 오답이 누적돼 차단되는 순간
 * 정작 복구 수단인 백업 코드까지 함께 잠긴다 — 즉 사용자가 폰을 잃어 TOTP 를 틀린 상황에서 백업
 * 코드로도 못 들어가는 자물쇠가 된다. 이를 막으려면 두 경로의 실패 카운터가 서로 독립이어야 한다.
 * [MfaAttemptLimiter] 와 **같은 정책**(임계값/window)을 쓰되 **별도 클래스 + 별도 Caffeine 캐시**로
 * 분리해, 한쪽 차단이 다른 쪽으로 전이되지 않게 한다. 같은 타입의 두 번째 빈을 등록하면 [MfaService]
 * 의 [MfaAttemptLimiter] 주입이 모호해져 부팅이 깨지므로(profile-scoped-bean-boot-failure 교훈),
 * 타입을 분리해 충돌을 0 으로 만든다.
 *
 * ## 정책 — [MfaAttemptLimiter] 와 동일
 * 한 사용자가 [WINDOW] 안에 [MAX_FAILURES] 회 이상 백업 코드 검증에 실패하면 [isBlocked] 가 `true`
 * 를 돌려 추가 시도를 거부한다. 검증 성공 시 호출처가 [reset] 으로 카운터를 비운다. 카운터는 마지막
 * 기록 시점 기준 [WINDOW] 가 지나면(`expireAfterWrite`) 자동 소멸한다.
 *
 * ## 메모리 only — 영구 lockout 이연 (FR-MF-04)
 * 카운터는 Caffeine in-memory 캐시에만 존재한다. BTS 단일 호스트 배포이므로 프로세스 메모리로
 * 충분하며, 재시작 시 초기화된다. 영구 계정 잠금/관리자 해제 흐름은 FR-MF-04 로 이연한다.
 *
 * ## 시각 의존 — Ticker 주입
 * window 만료 판단은 Caffeine [Ticker] 나노초 기준이다. `Instant.now()` 하드코딩은 특정 시각에
 * 깨지는 time-bomb 이므로, 테스트는 수동 진행 가능한 [Ticker] 를 주입한다
 * (authcontroller-revokesession-timebomb 교훈).
 *
 * ## 보안 주의
 * userId 등 식별자는 로깅하지 않는다(§1.1.2). 상태를 보유하는 싱글톤 빈이다([MfaBackupCodeService] 주입).
 *
 * ## 참조
 * - FR-MF-02 Task 5, SDD §19.7 (MFA — 백업 코드)
 */
@Component
class BackupCodeAttemptLimiter(
    ticker: Ticker = Ticker.systemTicker(),
) {
    /** 사용자별 연속 백업 코드 실패 횟수. 마지막 기록 시점 기준 [WINDOW] 후 자동 소멸. */
    private val failures: Cache<UUID, Int> =
        Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .ticker(ticker)
            .build()

    /**
     * 사용자의 백업 코드 검증 실패를 1회 기록한다.
     *
     * @param userId 실패한 사용자 UUID.
     */
    fun recordFailure(userId: UUID) {
        val current = failures.getIfPresent(userId) ?: 0
        failures.put(userId, current + 1)
    }

    /**
     * 사용자의 실패 카운터를 비운다. 백업 코드 검증에 성공한 직후 호출해야 한다.
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
        /** 차단 임계값 — 연속 실패가 이 횟수 이상이면 차단([MfaAttemptLimiter] 와 동일). */
        const val MAX_FAILURES = 5

        /** 실패 카운터 누적 window — 마지막 실패 후 이 시간이 지나면 카운터 소멸([MfaAttemptLimiter] 와 동일). */
        val WINDOW: Duration = Duration.ofMinutes(5)
    }
}

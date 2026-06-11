// MfaAttemptLimiter 단위 테스트 — 사용자별 MFA 실패 횟수 제한(5회/5분) + window 만료 해제 (FR-MF-01 Task 7)

package com.atlas.bts.identity.mfa

import com.github.benmanes.caffeine.cache.Ticker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * [MfaAttemptLimiter] 단위 테스트.
 *
 * 사용자별 연속 MFA 실패 횟수를 제한(5회/5분)하는 brute-force 방어 로직을 검증한다.
 * 시각 의존(window 만료)은 주입 [Ticker] ([ManualTicker])로 고정해 실제 대기 없이 시간 진행을
 * 시뮬레이션한다 — `Instant.now()` 하드코딩 time-bomb 회귀 방지
 * (메모리 authcontroller-revokesession-timebomb).
 *
 * ## 검증 시나리오
 * - 4회 실패 후 미차단 (임계값 미만)
 * - 5회 실패 후 차단
 * - 성공([reset]) 후 카운터 초기화 → 미차단
 * - window(5분) 만료(ticker 진행) 후 차단 해제
 */
class MfaAttemptLimiterTest {
    private val ticker = ManualTicker()
    private lateinit var limiter: MfaAttemptLimiter

    private val userId = UUID.fromString("77777777-0000-0000-0000-000000000007")

    @BeforeEach
    fun setUp() {
        limiter = MfaAttemptLimiter(ticker)
    }

    @Test
    fun `실패 누적이 없으면 차단되지 않는다`() {
        assertThat(limiter.isBlocked(userId)).isFalse()
    }

    @Test
    fun `4회 실패 후에는 차단되지 않는다`() {
        repeat(4) { limiter.recordFailure(userId) }

        assertThat(limiter.isBlocked(userId)).isFalse()
    }

    @Test
    fun `5회 실패 후에는 차단된다`() {
        repeat(5) { limiter.recordFailure(userId) }

        assertThat(limiter.isBlocked(userId)).isTrue()
    }

    @Test
    fun `5회 초과 실패해도 계속 차단된다`() {
        repeat(7) { limiter.recordFailure(userId) }

        assertThat(limiter.isBlocked(userId)).isTrue()
    }

    @Test
    fun `reset 후에는 실패 카운터가 초기화되어 차단되지 않는다`() {
        repeat(5) { limiter.recordFailure(userId) }
        assertThat(limiter.isBlocked(userId)).isTrue()

        limiter.reset(userId)

        assertThat(limiter.isBlocked(userId)).isFalse()
    }

    @Test
    fun `한 사용자의 실패가 다른 사용자를 차단하지 않는다`() {
        val otherUser = UUID.fromString("88888888-0000-0000-0000-000000000008")
        repeat(5) { limiter.recordFailure(userId) }

        assertThat(limiter.isBlocked(otherUser)).isFalse()
    }

    @Test
    fun `window(5분) 만료 후에는 차단이 해제된다`() {
        repeat(5) { limiter.recordFailure(userId) }
        assertThat(limiter.isBlocked(userId)).isTrue()

        // 5분 + 1초 경과 — expireAfterWrite window 만료로 카운터 제거.
        ticker.advance(Duration.ofMinutes(5).plusSeconds(1))

        assertThat(limiter.isBlocked(userId)).isFalse()
    }

    @Test
    fun `window 내(5분 미만)에서는 차단이 유지된다`() {
        repeat(5) { limiter.recordFailure(userId) }

        // 4분 59초 경과 — 아직 window 내이므로 차단 유지.
        ticker.advance(Duration.ofMinutes(4).plusSeconds(59))

        assertThat(limiter.isBlocked(userId)).isTrue()
    }

    /**
     * 테스트에서 시간을 수동으로 진행시키는 Caffeine [Ticker].
     *
     * Caffeine 의 `expireAfterWrite` 는 [Ticker.read] 가 돌려주는 나노초 값을 기준으로 만료를
     * 판단하므로, 실제 대기 없이 [advance] 로 window 만료를 시뮬레이션할 수 있다.
     */
    private class ManualTicker : Ticker {
        private val nanos = AtomicLong(0)

        override fun read(): Long = nanos.get()

        fun advance(duration: Duration) {
            nanos.addAndGet(duration.toNanos())
        }
    }
}

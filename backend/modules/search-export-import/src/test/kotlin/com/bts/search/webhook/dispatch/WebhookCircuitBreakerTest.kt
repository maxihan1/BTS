// 인메모리 Webhook circuit breaker 상태 전이 단위 테스트 — Clock 주입 결정성 (FR-API-03 PR3 Task 5)
package com.bts.search.webhook.dispatch

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class WebhookCircuitBreakerTest {
    /**
     * 테스트에서 시간을 전진시키기 위한 가변 [Clock].
     *
     * 같은 인스턴스를 [WebhookCircuitBreaker]에 주입한 뒤 [advance]로 instant를 밀어,
     * 벽시계 sleep 없이 OPEN_DURATION(60초) 경계를 결정적으로 검증한다
     * (메모리 authcontroller-revokesession-timebomb 회귀 방지, StepUpServiceTest 선례).
     */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        fun advance(by: Duration) {
            current = current.plus(by)
        }
    }

    private val baseInstant = Instant.parse("2026-07-01T00:00:00Z")

    @Test
    fun `연속 5회 실패하면 open 상태가 된다`() {
        val breaker = WebhookCircuitBreaker(MutableClock(baseInstant))
        val id = UUID.randomUUID()

        repeat(5) { breaker.recordFailure(id) }

        assertTrue(breaker.isOpen(id))
    }

    @Test
    fun `4회 실패로는 open 되지 않는다 (negative control)`() {
        val breaker = WebhookCircuitBreaker(MutableClock(baseInstant))
        val id = UUID.randomUUID()

        repeat(4) { breaker.recordFailure(id) }

        assertFalse(breaker.isOpen(id))
    }

    @Test
    fun `open 후 60초 이내에는 open 상태를 유지한다`() {
        val clock = MutableClock(baseInstant)
        val breaker = WebhookCircuitBreaker(clock)
        val id = UUID.randomUUID()
        repeat(5) { breaker.recordFailure(id) }

        clock.advance(Duration.ofSeconds(59))

        assertTrue(breaker.isOpen(id))
    }

    @Test
    fun `open 후 60초가 지나면 half-open 되어 탐침을 허용한다`() {
        val clock = MutableClock(baseInstant)
        val breaker = WebhookCircuitBreaker(clock)
        val id = UUID.randomUUID()
        repeat(5) { breaker.recordFailure(id) }

        clock.advance(Duration.ofSeconds(60))

        assertFalse(breaker.isOpen(id))
    }

    @Test
    fun `half-open 탐침이 성공하면 closed로 전환되고 실패 카운터가 리셋된다`() {
        val clock = MutableClock(baseInstant)
        val breaker = WebhookCircuitBreaker(clock)
        val id = UUID.randomUUID()
        repeat(5) { breaker.recordFailure(id) }
        clock.advance(Duration.ofSeconds(60))

        breaker.recordSuccess(id)

        assertFalse(breaker.isOpen(id))

        // 카운터가 리셋됐으므로 4회 실패(임계 미달)로는 다시 open 되지 않는다
        repeat(4) { breaker.recordFailure(id) }
        assertFalse(breaker.isOpen(id))
    }

    @Test
    fun `half-open 탐침이 실패하면 다시 open 된다`() {
        val clock = MutableClock(baseInstant)
        val breaker = WebhookCircuitBreaker(clock)
        val id = UUID.randomUUID()
        repeat(5) { breaker.recordFailure(id) }
        clock.advance(Duration.ofSeconds(60))
        assertFalse(breaker.isOpen(id)) // half-open 진입 확인

        breaker.recordFailure(id)

        assertTrue(breaker.isOpen(id))
    }

    @Test
    fun `서로 다른 구독 id는 상태가 독립적이다`() {
        val breaker = WebhookCircuitBreaker(MutableClock(baseInstant))
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        repeat(5) { breaker.recordFailure(id1) }

        assertTrue(breaker.isOpen(id1))
        assertFalse(breaker.isOpen(id2))
    }

    @Test
    fun `기록 없는 id는 open 이 아니다 (기본 closed)`() {
        val breaker = WebhookCircuitBreaker(MutableClock(baseInstant))

        assertFalse(breaker.isOpen(UUID.randomUUID()))
    }
}

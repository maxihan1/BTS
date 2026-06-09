// 계정 연결 step-up(재인증) 윈도우 서비스 StepUpService 의 단위 테스트
package com.atlas.bts.identity.account

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class StepUpServiceTest {
    /**
     * 테스트에서 시간을 전진시키기 위한 가변 [Clock].
     *
     * 같은 인스턴스를 [StepUpService] 에 주입한 뒤 [advance] 로 instant 를 밀어,
     * 벽시계 sleep 없이 TTL 만료를 결정적으로 검증한다 (time-bomb 회귀 방지).
     */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        fun advance(by: Duration) {
            current = current.plus(by)
        }
    }

    private val baseInstant = Instant.parse("2026-06-09T00:00:00Z")

    @Test
    fun `grant 후 isValid 는 true`() {
        val clock = MutableClock(baseInstant)
        val service = StepUpService(clock)
        val sid = UUID.randomUUID()

        service.grant(sid)

        assertThat(service.isValid(sid)).isTrue()
    }

    @Test
    fun `한 번도 grant 안 한 sid 는 isValid false`() {
        val service = StepUpService(MutableClock(baseInstant))

        assertThat(service.isValid(UUID.randomUUID())).isFalse()
    }

    @Test
    fun `TTL 5분 직전에는 여전히 isValid true`() {
        val clock = MutableClock(baseInstant)
        val service = StepUpService(clock)
        val sid = UUID.randomUUID()
        service.grant(sid)

        clock.advance(Duration.ofMinutes(5).minusSeconds(1))

        assertThat(service.isValid(sid)).isTrue()
    }

    @Test
    fun `정확히 5분 경계 시점은 만료되어 isValid false (fail-safe)`() {
        val clock = MutableClock(baseInstant)
        val service = StepUpService(clock)
        val sid = UUID.randomUUID()
        service.grant(sid)

        clock.advance(Duration.ofMinutes(5))

        assertThat(service.isValid(sid)).isFalse()
    }

    @Test
    fun `TTL 5분 경과 후에는 isValid false`() {
        val clock = MutableClock(baseInstant)
        val service = StepUpService(clock)
        val sid = UUID.randomUUID()
        service.grant(sid)

        clock.advance(Duration.ofMinutes(6))

        assertThat(service.isValid(sid)).isFalse()
    }
}

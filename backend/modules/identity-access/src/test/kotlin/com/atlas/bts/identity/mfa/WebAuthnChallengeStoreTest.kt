// WebAuthnChallengeStore 단위 테스트 — 사용자별 WebAuthn challenge 발급/일회용 소비/TTL 만료/격리 (FR-MF-03 Task 4)

package com.atlas.bts.identity.mfa

import com.github.benmanes.caffeine.cache.Ticker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * [WebAuthnChallengeStore] 단위 테스트.
 *
 * WebAuthn(패스키/보안키) 등록·인증의 challenge(nonce, FIDO2 서명 대상)를 사용자별로 발급하고
 * 1회용으로 소비하는 로직을 검증한다. challenge 는 5분 TTL 의 Caffeine 캐시에 보관되며,
 * 시각 의존(TTL 만료)은 주입 [Ticker] ([ManualTicker])로 고정해 실제 대기 없이 시간 진행을
 * 시뮬레이션한다 — `Instant.now()` 하드코딩 time-bomb 회귀 방지
 * (메모리 authcontroller-revokesession-timebomb).
 *
 * ## 검증 시나리오
 * - (a) issue → consume 성공 (발급한 challenge 가 동일하게 소비됨)
 * - (b) 재consume 은 null (1회용 — 한 번 소비되면 사라짐)
 * - (c) TTL(5분) 만료 후 consume 부재 (null)
 * - (d) key=userId 격리 (다른 userId 의 challenge 가 서로 간섭하지 않음)
 */
class WebAuthnChallengeStoreTest {
    private val ticker = ManualTicker()
    private lateinit var store: WebAuthnChallengeStore

    private val userId = UUID.fromString("99999999-0000-0000-0000-000000000009")

    @BeforeEach
    fun setUp() {
        store = WebAuthnChallengeStore(ticker)
    }

    @Test
    fun `발급한 challenge 를 동일하게 소비할 수 있다`() {
        val issued = store.issue(userId)

        val consumed = store.consume(userId)

        assertThat(consumed).isNotNull()
        assertThat(consumed!!.value).isEqualTo(issued.value)
    }

    @Test
    fun `발급한 challenge 는 SecureRandom 기반의 충분한 엔트로피 nonce 다`() {
        // webauthn4j DefaultChallenge() 는 SecureRandom 기반 16바이트 nonce 를 생성한다
        // (WebAuthn 권장 최소 엔트로피). 매 발급마다 값이 달라야 한다(상수/예측 불가).
        val first = store.issue(userId)
        val second = store.issue(userId)

        assertThat(first.value).hasSizeGreaterThanOrEqualTo(16)
        assertThat(second.value).isNotEqualTo(first.value)
    }

    @Test
    fun `재consume 은 null 을 돌려준다 (1회용)`() {
        store.issue(userId)
        store.consume(userId)

        val second = store.consume(userId)

        assertThat(second).isNull()
    }

    @Test
    fun `발급 없이 consume 하면 null 이다`() {
        assertThat(store.consume(userId)).isNull()
    }

    @Test
    fun `TTL(5분) 만료 후에는 challenge 가 부재(null)다`() {
        store.issue(userId)

        // 5분 + 1초 경과 — expireAfterWrite TTL 만료로 challenge 제거.
        ticker.advance(Duration.ofMinutes(5).plusSeconds(1))

        assertThat(store.consume(userId)).isNull()
    }

    @Test
    fun `TTL 내(5분 미만)에서는 challenge 가 유지된다`() {
        val issued = store.issue(userId)

        // 4분 59초 경과 — 아직 TTL 내이므로 challenge 유지.
        ticker.advance(Duration.ofMinutes(4).plusSeconds(59))

        val consumed = store.consume(userId)
        assertThat(consumed).isNotNull()
        assertThat(consumed!!.value).isEqualTo(issued.value)
    }

    @Test
    fun `한 사용자의 challenge 는 다른 사용자에게 간섭하지 않는다`() {
        val otherUser = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a")
        store.issue(userId)

        // 다른 사용자는 발급한 적이 없으므로 부재.
        assertThat(store.consume(otherUser)).isNull()
    }

    @Test
    fun `한 사용자의 소비가 다른 사용자의 challenge 를 지우지 않는다`() {
        val otherUser = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b")
        store.issue(userId)
        val otherIssued = store.issue(otherUser)

        // userId 의 challenge 를 소비해도 otherUser 의 것은 그대로 남는다.
        store.consume(userId)

        val otherConsumed = store.consume(otherUser)
        assertThat(otherConsumed).isNotNull()
        assertThat(otherConsumed!!.value).isEqualTo(otherIssued.value)
    }

    /**
     * 테스트에서 시간을 수동으로 진행시키는 Caffeine [Ticker].
     *
     * Caffeine 의 `expireAfterWrite` 는 [Ticker.read] 가 돌려주는 나노초 값을 기준으로 만료를
     * 판단하므로, 실제 대기 없이 [advance] 로 TTL 만료를 시뮬레이션할 수 있다.
     */
    private class ManualTicker : Ticker {
        private val nanos = AtomicLong(0)

        override fun read(): Long = nanos.get()

        fun advance(duration: Duration) {
            nanos.addAndGet(duration.toNanos())
        }
    }
}

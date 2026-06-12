// 사용자별 WebAuthn challenge(FIDO2 nonce)를 발급하고 1회용으로 소비하는 in-memory 저장소 (FR-MF-03 Task 4)

package com.atlas.bts.identity.mfa

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Ticker
import com.webauthn4j.data.client.challenge.Challenge
import com.webauthn4j.data.client.challenge.DefaultChallenge
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * 사용자별 WebAuthn challenge(nonce)를 발급하고 1회용으로 소비하는 in-memory 저장소 (FR-MF-03 Task 4).
 *
 * ## challenge 란 — MfaChallengeTokenService 의 challenge JWT 와 다른 개념
 * 여기서 다루는 challenge 는 **WebAuthn(FIDO2)의 nonce** 다. 서버가 무작위 바이트열을 발급하면
 * 인증기(보안키/패스키)가 이를 포함해 서명하고, 서버는 등록(attestation)/인증(assertion) 응답의
 * 서명이 이 challenge 를 대상으로 했는지 검증한다(재전송/replay 방어). 즉 challenge 는 **암호 서명의
 * 대상 데이터**다.
 *
 * 이는 [MfaChallengeTokenService] 가 발급하는 **challenge JWT** 와는 전혀 다른 개념이다. challenge JWT 는
 * "비밀번호 1단계를 통과해 MFA 2단계로 진입할 권한"을 나타내는 단명 인증 토큰이다(요청 본문으로 전달).
 * 이름은 같은 'challenge' 지만, 본 저장소의 것은 WebAuthn 서명 대상 nonce, 그쪽은 2단계 진입권 토큰이다.
 *
 * ## 1회용 (replay 방어)
 * challenge 는 발급 후 한 번만 검증에 쓰여야 한다. [consume] 은 조회와 동시에 원자적으로 무효화하므로
 * 같은 challenge 로 두 번째 검증을 시도하면 `null` 을 돌려 거부된다.
 *
 * ## TTL — Caffeine 5분
 * challenge 는 [CHALLENGE_TTL] (5분) 동안만 유효하다. 그 안에 등록/인증 응답이 오지 않으면 만료되어
 * 부재(`null`)가 된다. key 는 userId 라 사용자별로 격리되며, 한 사용자가 새로 [issue] 하면 기존
 * challenge 를 덮어쓴다(사용자당 진행 중 1건).
 *
 * ## 시각 의존 — Ticker 주입
 * TTL 만료 판단은 Caffeine 의 [Ticker] 가 돌려주는 나노초 값을 기준으로 한다. `Instant.now()` 를
 * 하드코딩하면 특정 시각에 깨지는 time-bomb 이 되므로, 테스트에서 수동 진행 가능한 [Ticker] 를
 * 주입해 TTL 만료를 시뮬레이션한다 (메모리 authcontroller-revokesession-timebomb).
 *
 * ## 보안 주의
 * userId·challenge 값은 로깅하지 않는다(§1.1.2). challenge 는 [DefaultChallenge] 기본 생성자
 * (SecureRandom 기반 16바이트 — WebAuthn 권장 최소 엔트로피)로 생성한다. 상태를 보유하는 싱글톤 빈이다.
 *
 * ## 참조
 * - FR-MF-03 Task 4 (WebAuthn challenge 저장소)
 * - SDD §19.7 (2FA)
 */
@Component
class WebAuthnChallengeStore(
    ticker: Ticker = Ticker.systemTicker(),
) {
    /** 사용자별 진행 중 WebAuthn challenge. 마지막 발급 시점 기준 [CHALLENGE_TTL] 후 자동 소멸. */
    private val challenges: Cache<UUID, Challenge> =
        Caffeine.newBuilder()
            .expireAfterWrite(CHALLENGE_TTL)
            .ticker(ticker)
            .build()

    /**
     * 사용자에게 새 WebAuthn challenge(SecureRandom 기반 nonce)를 발급해 저장하고 반환한다.
     *
     * 기존에 진행 중이던 challenge 가 있으면 덮어쓴다(사용자당 진행 중 1건).
     *
     * @param userId challenge 를 발급할 사용자 UUID.
     * @return 새로 생성된 [Challenge] (FIDO2 서명 대상 nonce).
     */
    fun issue(userId: UUID): Challenge {
        val challenge = DefaultChallenge()
        challenges.put(userId, challenge)
        return challenge
    }

    /**
     * 사용자의 challenge 를 조회하고 동시에 무효화한다(1회용).
     *
     * 처음 소비되는 challenge 면 그 값을 돌려주고 캐시에서 제거한다. 발급된 적 없거나 이미
     * 소비됐거나 TTL 이 만료됐으면 `null` 을 반환한다. `null` 은 인증 실패를 숨기는 것이 아니라
     * **명시적 거부 신호**다(불명은 거부 — fail-closed).
     *
     * @param userId challenge 를 소비할 사용자 UUID.
     * @return 유효한 [Challenge], 또는 부재/만료/재소비 시 `null`.
     */
    fun consume(userId: UUID): Challenge? {
        // asMap().remove 는 매핑이 있으면 그 값을 돌려주며 제거하고, 없으면 null 을 돌려준다(원자적).
        return challenges.asMap().remove(userId)
    }

    internal companion object {
        /** WebAuthn challenge 유효 기간 — 5분. 등록/인증 응답이 그 안에 와야 한다. */
        val CHALLENGE_TTL: Duration = Duration.ofMinutes(5)
    }
}

// Access Token JWT 검증 시 sid claim으로 세션 revoke 여부를 확인하는 Converter (FR-09-11 / EC-29)

package com.atlas.bts.identity.jwt

import com.atlas.bts.identity.session.SessionService
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * FR-09-11 sid 기반 세션 revoke 검증 회로.
 *
 * Access Token JWT가 필터 체인에 도달할 때 [convert]가 호출된다.
 * `sid` 클레임으로 [SessionService.lookup]을 호출하여 세션이 활성 상태인지 확인하고,
 * 비활성(폐기 또는 만료) 세션이면 [InvalidBearerTokenException]을 던져 인증을 거부한다.
 *
 * ## 캐시 정책 (EC-29)
 * 같은 sid에 대한 DB 조회를 최소화하기 위해 Caffeine 캐시를 사용한다.
 * - TTL: 5초 (expireAfterWrite) — Access Token 수명(15분) 대비 DB hit를 대폭 줄이면서
 *   revoke 후 최대 5초 내 차단 보장.
 * - 최대 항목: 10,000개 (동시 접속 피크 추정치 기준).
 * - 캐시 값: `true` = 세션 활성, `false` = 비활성(폐기·만료·미존재).
 *
 * ## 처리 정책
 * - `sid` 클레임 없음 → [InvalidBearerTokenException] ("missing sid claim")
 * - `sid` UUID 파싱 실패 → [InvalidBearerTokenException] ("invalid sid format")
 * - 세션 미존재([SessionService.lookup] null 반환) → 비활성으로 처리 → 거부
 * - 세션 폐기([Session.isRevoked] true) 또는 만료([Session.isActive] false) → 거부
 *
 * ## 설계 노트
 * Task 19 (SecurityConfig)가 이 Bean을 `oauth2ResourceServer.jwt.jwtAuthenticationConverter()`로
 * 등록하여 Spring Security 필터 체인에 연결한다. 이 Converter 자체는 Spring Security 필터 체인
 * 교체가 아닌 JWT 검증 후처리 단계에서 실행된다.
 *
 * @see SessionService.lookup sid로 세션 단건 조회
 * @see com.atlas.bts.identity.session.Session.isActive 세션 활성 상태 판별
 */
@Component
class SidRevokeJwtConverter(
    private val sessionService: SessionService,
    private val clock: Clock = Clock.systemUTC(),
    private val delegate: JwtAuthenticationConverter = defaultDelegate(),
) : Converter<Jwt, AbstractAuthenticationToken> {

    /** EC-29: Caffeine 5s TTL 캐시 — sid(UUID) → active(Boolean) */
    private val cache: Cache<UUID, Boolean> = Caffeine.newBuilder()
        .expireAfterWrite(CACHE_TTL)
        .maximumSize(CACHE_MAX_SIZE)
        .build()

    /**
     * JWT를 [AbstractAuthenticationToken]으로 변환한다.
     *
     * 변환 전에 `sid` 클레임으로 세션 revoke 여부를 검증한다 (FR-09-11).
     *
     * @throws InvalidBearerTokenException sid 클레임 누락, UUID 형식 오류, 또는 세션 비활성 시
     */
    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val sid = resolveSid(jwt)
        val active = cache.get(sid) { resolveActive(it) }
        if (active != true) {
            throw InvalidBearerTokenException("session_revoked")
        }
        return delegate.convert(jwt) ?: throw InvalidBearerTokenException("invalid jwt")
    }

    /**
     * JWT에서 `sid` 클레임을 추출하여 [UUID]로 파싱한다.
     *
     * @throws InvalidBearerTokenException 클레임 누락 또는 UUID 형식 오류
     */
    private fun resolveSid(jwt: Jwt): UUID {
        val raw = jwt.getClaimAsString("sid")
            ?: throw InvalidBearerTokenException("missing sid claim")
        return try {
            UUID.fromString(raw)
        } catch (e: IllegalArgumentException) {
            throw InvalidBearerTokenException("invalid sid format: $raw")
        }
    }

    /**
     * [SessionService.lookup]을 호출하여 세션 활성 여부를 반환한다.
     *
     * Caffeine 캐시 로더로 사용된다. null(미존재), 폐기, 만료 세션은 false를 반환한다.
     */
    private fun resolveActive(sid: UUID): Boolean {
        val session = sessionService.lookup(sid) ?: return false
        return session.isActive(clock.instant())
    }

    internal companion object {
        /**
         * 기본 [JwtAuthenticationConverter] 를 생성한다.
         *
         * BTS 의 전역 시스템 역할은 [JwtIssuer.CLAIM_ROLES] (`roles`) claim 에 담긴다.
         * 기본 [JwtGrantedAuthoritiesConverter] 는 `scope`/`scp` claim 만 탐색하므로,
         * `roles` claim 을 `ROLE_` 접두어 authority 로 변환하도록 명시적으로 구성한다 (FR-PM-08).
         * 예: `roles: ["SYSTEM_ADMIN"]` → authority `ROLE_SYSTEM_ADMIN` →
         * `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` 매칭.
         */
        private fun defaultDelegate(): JwtAuthenticationConverter {
            val authoritiesConverter =
                JwtGrantedAuthoritiesConverter().apply {
                    setAuthoritiesClaimName(JwtIssuer.CLAIM_ROLES)
                    setAuthorityPrefix("ROLE_")
                }
            return JwtAuthenticationConverter().apply {
                setJwtGrantedAuthoritiesConverter(authoritiesConverter)
            }
        }

        /**
         * EC-29 캐시 TTL — revoke 후 최대 5초 내 차단 보장.
         *
         * Access Token 수명(15분) 대비 DB hit를 대폭 줄이면서
         * 세션 폐기 후 허용 창을 5초로 제한한다 (EC-29 정책).
         */
        val CACHE_TTL: Duration = Duration.ofSeconds(5)

        /**
         * EC-29 캐시 최대 항목 수 — 동시 접속 피크 추정치 기준.
         *
         * 1,000명 규모 사내 워크스페이스 기준 피크 동시 세션 추정치의 10배 마진.
         */
        const val CACHE_MAX_SIZE: Long = 10_000L
    }
}

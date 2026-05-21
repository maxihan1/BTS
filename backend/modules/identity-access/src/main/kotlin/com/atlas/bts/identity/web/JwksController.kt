// RFC 7517 JWK Set 공개키 노출 엔드포인트 — Spring Authorization Server 미도입으로 직접 구현 (FR-09-2/18)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.jwt.JwtKeyProvider
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

/**
 * RFC 7517 JWK Set 공개키 노출 엔드포인트.
 *
 * `GET /.well-known/jwks.json` — 인증 없이 접근 가능 (SecurityConfig permitAll).
 * Spring Authorization Server 미도입 결정(ADR 2026-05-20-jwt-key-rotation-policy)에 따라
 * nimbus-jose-jwt 9.x API 로 직접 구현한다.
 *
 * ## 보안 규칙
 * - [JwtKeyProvider.publicKey] 만 직렬화. privateKey(`d` 필드)는 절대 포함하지 않는다.
 * - nimbus [RSAKey.Builder]에 [RSAPublicKey]만 전달하면 `d` 필드가 자동으로 제외된다.
 *
 * ## 캐시 정책 (FR-09-18)
 * `Cache-Control: max-age=86400, public` — 24시간 공개 캐시.
 * 키 교체(key rotation) 시 [JwtKeyProvider.kid]가 변경되므로 클라이언트는 새 kid 를 받은 후
 * jwks.json 을 다시 fetch 한다. 이전 kid 로 발급된 토큰은 만료될 때까지 검증 가능하다.
 * 키 교체 직후 24시간 내에는 구 공개키가 CDN/클라이언트에 캐시될 수 있으므로
 * 구 키를 서버에서 즉시 제거하지 않고 토큰 만료 시간(access token TTL)만큼 유지해야 한다.
 *
 * ## 참조
 * - FR-AU-09-2, FR-AU-18 (JWK Set 공개)
 * - ADR `docs/decisions/2026-05-20-jwt-key-rotation-policy.md` (Wave 1 Task 30)
 * - [JwtKeyProvider], [SecurityConfig]
 */
@RestController
class JwksController(
    private val jwtKeyProvider: JwtKeyProvider,
) {
    /**
     * 현재 활성 RSA 공개키를 JWK Set 형식으로 반환한다.
     *
     * 응답 예시.
     * ```json
     * { "keys": [{ "kty": "RSA", "use": "sig", "kid": "k-01", "alg": "RS256", "n": "...", "e": "AQAB" }] }
     * ```
     */
    @GetMapping("/.well-known/jwks.json")
    fun jwks(): ResponseEntity<Map<String, Any>> {
        val rsaKey =
            RSAKey.Builder(jwtKeyProvider.publicKey)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(jwtKeyProvider.kid)
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build()

        val body = mapOf("keys" to listOf(rsaKey.toJSONObject()))

        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(86400, TimeUnit.SECONDS).cachePublic())
            .body(body)
    }
}

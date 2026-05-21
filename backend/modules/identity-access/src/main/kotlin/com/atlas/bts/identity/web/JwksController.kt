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

@RestController
class JwksController(
    private val jwtKeyProvider: JwtKeyProvider,
) {
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

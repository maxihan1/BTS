// WebhookSigner의 HMAC-SHA256 서명 계산을 알려진 테스트 벡터로 검증하는 단위 테스트
package com.bts.search.webhook.dispatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class WebhookSignerTest {
    @Test
    fun `sign - RFC4231 HMAC-SHA256 Test Case 2 벡터와 일치한다`() {
        // RFC4231 §4.3 — key="Jefe"(ASCII), data="what do ya want for nothing?"
        val secret = "Jefe"
        val body = "what do ya want for nothing?".toByteArray(Charsets.US_ASCII)

        val signature = WebhookSigner.sign(secret, body)

        assertEquals(
            "sha256=5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            signature,
        )
    }

    @Test
    fun `sign - GitHub webhook 문서 예시 벡터와 일치한다`() {
        // GitHub Webhooks 문서의 서명 검증 예시 (secret + payload)
        val secret = "It's a Secret to Everybody"
        val body = "Hello, World!".toByteArray(Charsets.UTF_8)

        val signature = WebhookSigner.sign(secret, body)

        assertEquals(
            "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
            signature,
        )
    }

    @Test
    fun `sign - secret이 다르면 같은 body라도 다른 서명을 생성한다`() {
        val body = "Hello, World!".toByteArray(Charsets.UTF_8)

        val signatureA = WebhookSigner.sign("secret-a", body)
        val signatureB = WebhookSigner.sign("secret-b", body)

        assertNotEquals(signatureA, signatureB)
    }
}

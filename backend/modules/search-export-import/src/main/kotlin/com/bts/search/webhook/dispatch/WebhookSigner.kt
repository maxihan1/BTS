// Webhook 발송 body에 대한 HMAC-SHA256 서명을 계산하는 순수 함수 유틸
package com.bts.search.webhook.dispatch

import org.springframework.security.crypto.codec.Hex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object WebhookSigner {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SIGNATURE_PREFIX = "sha256="

    fun sign(secret: String, body: ByteArray): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        val digest = mac.doFinal(body)
        return SIGNATURE_PREFIX + String(Hex.encode(digest))
    }
}

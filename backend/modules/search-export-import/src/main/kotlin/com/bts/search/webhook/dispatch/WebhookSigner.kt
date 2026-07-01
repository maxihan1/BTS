// Webhook 발송 body에 대한 HMAC-SHA256 서명을 계산하는 순수 함수 유틸
package com.bts.search.webhook.dispatch

import org.springframework.security.crypto.codec.Hex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Webhook 발송 요청 body에 대한 HMAC-SHA256 서명을 계산하는 순수 함수 유틸 (FR-API-03 PR3 Task 4).
 *
 * ## 서명 계약 (spec §6)
 * - 알고리즘. `HMAC_SHA256(key=평문 secret, message=raw body bytes)`
 * - 헤더 형식. `X-BTS-Signature: sha256=<hex>` — GitHub webhook 서명 관례와 동일한 접두사를 써서
 *   수신자가 별도 학습 없이 검증 코드를 재사용할 수 있게 한다(devex 리뷰).
 * - 서명 대상은 **재직렬화하지 않은 raw body bytes** 여야 한다. 호출자가 실제로 전송한 바이트와
 *   다른 바이트(예: JSON 재파싱 후 재직렬화한 결과)로 서명하면 수신자 측 검증이 항상 실패한다.
 *
 * ## 보안 주의 (DEVELOPMENT.md §1.1.2)
 * [secret]은 평문으로 전달받아 [Mac] 키로만 사용하고 절대 로깅하지 않는다. 반환하는 서명값 역시
 * 재전송 공격에 활용될 수 있으므로 호출자가 로그에 남기지 않도록 주의한다(호출자 책임).
 *
 * JDK 표준 [javax.crypto.Mac]만 사용하는 외부 의존성 없는 순수 함수 — 상태를 갖지 않으므로
 * 여러 스레드/구독에서 안전하게 공유할 수 있다.
 */
object WebhookSigner {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SIGNATURE_PREFIX = "sha256="

    /**
     * [secret] 키로 [body]의 HMAC-SHA256 서명을 계산해 `sha256=<hex>` 형식으로 반환한다.
     *
     * @param secret 평문 signing secret (로깅 금지). 구독 등록 시 저장된 값을 복호화해 전달한다.
     * @param body 서명 대상 raw body bytes. 실제로 전송할 body와 바이트 단위로 동일해야 한다.
     * @return `sha256=` 접두 hex 인코딩 서명 문자열. `X-BTS-Signature` 헤더 값으로 그대로 사용한다.
     */
    fun sign(
        secret: String,
        body: ByteArray,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        val digest = mac.doFinal(body)
        return SIGNATURE_PREFIX + toHex(digest)
    }

    /**
     * 바이트 배열을 소문자 hex 문자열로 인코딩한다.
     *
     * [org.springframework.security.crypto.codec.Hex]는 이미 이 모듈의 [com.bts.search.webhook.config.WebhookEncryptionConfig]
     * 경유로 의존 중인 Spring Security 유틸이라 신규 의존성 추가 없이 재사용한다.
     *
     * @param bytes 인코딩할 바이트 배열 (여기서는 HMAC digest).
     * @return 소문자 hex 문자열.
     */
    private fun toHex(bytes: ByteArray): String = String(Hex.encode(bytes))
}

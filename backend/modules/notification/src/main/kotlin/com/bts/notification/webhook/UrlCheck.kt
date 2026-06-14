// WebhookUrlValidator 검사 결과를 표현하는 sealed 클래스

package com.bts.notification.webhook

/**
 * Webhook URL 검사 결과.
 *
 * - [Allowed] — 전송 가능한 URL
 * - [Blocked] — SSRF 위험 또는 비-http 스킴으로 차단
 * - [Malformed] — URL 파싱 자체가 실패한 경우
 */
sealed class UrlCheck {
    /** 전송 허용. */
    data object Allowed : UrlCheck()

    /**
     * 내부망 IP, 비-http 스킴 등으로 차단.
     *
     * @param reason 차단 사유 (로그 전용 — HTTP 응답에 노출 금지)
     */
    data class Blocked(val reason: String) : UrlCheck()

    /**
     * URL 파싱 실패 또는 빈 값.
     *
     * @param reason 파싱 실패 사유
     */
    data class Malformed(val reason: String) : UrlCheck()
}

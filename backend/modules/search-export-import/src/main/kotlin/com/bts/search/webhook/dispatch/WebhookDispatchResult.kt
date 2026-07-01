// SearchWebhookDispatcher의 발송 결과를 표현하는 sealed 클래스
package com.bts.search.webhook.dispatch

/**
 * [SearchWebhookDispatcher.dispatch] 발송 결과 (FR-API-03 PR3 Task 7).
 *
 * notification `WebhookDispatcher`의 [com.bts.notification.webhook.WebhookDispatchResult]와
 * 개념은 같지만(2xx=성공/영구거부/일시실패), 이력 기록([com.bts.search.webhook.domain.WebhookDelivery])에
 * HTTP 상태 코드가 필요해 [Sent]·[Failed]에 `code`를 추가로 담는다.
 *
 * - [Sent] — 2xx 응답, 전송 성공
 * - [Rejected] — SSRF 차단/malformed 등 영구 거부 (발송 자체를 시도하지 않음)
 * - [Failed] — non-2xx 응답 또는 타임아웃/연결오류 등 예외로 인한 실패
 */
sealed class WebhookDispatchResult {
    /**
     * 2xx 응답 — 전송 성공.
     *
     * @param code 응답 HTTP 상태 코드
     */
    data class Sent(val code: Int) : WebhookDispatchResult()

    /**
     * 영구 거부 — SSRF 차단/malformed URL 등으로 발송 자체를 시도하지 않았다.
     *
     * @param reason 거부 사유 (로그 전용 — 내부 host 등 민감정보 미포함)
     */
    data class Rejected(val reason: String) : WebhookDispatchResult()

    /**
     * 발송 실패 — non-2xx 응답 또는 타임아웃/연결오류/DNS 실패 등 예외.
     *
     * @param reason 실패 사유 (로그 전용)
     * @param code 응답을 받았다면 HTTP 상태 코드, 예외로 응답 자체를 받지 못했다면 `null`
     */
    data class Failed(val reason: String, val code: Int? = null) : WebhookDispatchResult()
}

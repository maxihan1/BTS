// Webhook 전송 결과를 표현하는 sealed 클래스

package com.bts.notification.webhook

/**
 * Webhook 전송 결과.
 *
 * - [Sent] — 2xx 응답, 전송 성공
 * - [Rejected] — SSRF 차단/malformed/비-http 스킴 등 영구 거부 (워커가 pgmq.delete 처리)
 * - [Failed] — non-2xx/타임아웃/연결오류/DNS 실패 등 일시 실패 (워커가 재전달 허용)
 */
sealed class WebhookDispatchResult {
    /** 2xx 응답 — 전송 성공. */
    data object Sent : WebhookDispatchResult()

    /**
     * 영구 거부 — 재시도해도 의미 없는 실패.
     *
     * 워커는 이 결과 시 pgmq.delete 로 메시지를 제거한다.
     *
     * @param reason 거부 사유 (로그 전용)
     */
    data class Rejected(val reason: String) : WebhookDispatchResult()

    /**
     * 일시 실패 — 재시도 가능한 실패.
     *
     * 워커는 이 결과 시 pgmq.delete 하지 않아 vt 만료 후 재전달된다 (at-least-once).
     *
     * @param reason 실패 사유 (로그 전용)
     */
    data class Failed(val reason: String) : WebhookDispatchResult()
}

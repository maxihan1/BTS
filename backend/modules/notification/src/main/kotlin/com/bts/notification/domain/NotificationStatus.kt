// 알림 발송 상태 enum — PENDING / SENT / FAILED 3종

package com.bts.notification.domain

/**
 * 알림 단건의 발송 생명주기 상태.
 *
 * - PENDING: 발송 큐에 적재됐으나 아직 전송되지 않은 상태.
 * - SENT: 채널로 전송 완료된 상태.
 * - FAILED: 전송 시도 후 최종 실패한 상태.
 */
enum class NotificationStatus {
    /** 발송 대기 중 — 큐 적재 후 미전송 */
    PENDING,

    /** 전송 완료 */
    SENT,

    /** 전송 최종 실패 */
    FAILED,
}

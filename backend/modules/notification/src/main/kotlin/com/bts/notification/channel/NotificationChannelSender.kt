// 알림 채널 발송 추상 인터페이스 — 채널 지원 여부와 단건 발송 계약 정의

package com.bts.notification.channel

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification

/**
 * 알림 단건을 특정 채널로 발송하는 sender 의 공통 인터페이스.
 *
 * 구현체는 자신이 처리할 채널을 [supports] 로 선언하고,
 * [send] 에서 실제 전송 로직을 실행한다.
 *
 * 현재 구현체.
 * - [InAppChannelSender]: STOMP WebSocket 인앱 알림 (FR-NT-02)
 *
 * 후속 구현체 (별도 PR).
 * - EmailChannelSender: 이메일 발송
 * - WebhookChannelSender: 외부 HTTP 웹훅 호출
 */
interface NotificationChannelSender {
    /**
     * 이 sender 가 주어진 채널을 처리할 수 있는지 반환한다.
     *
     * @param channel 처리 대상 채널
     * @return 지원하면 `true`, 아니면 `false`
     */
    fun supports(channel: Channel): Boolean

    /**
     * 알림 단건을 해당 채널로 발송한다.
     *
     * @param notification 발송할 알림 Aggregate
     */
    fun send(notification: Notification)
}

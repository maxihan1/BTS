// 알림 발송 단위 도메인 — 수신자/이벤트/채널/상태 + 멱등 dedup 키

package com.bts.notification.domain

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * 알림 발송 단위 Aggregate.
 *
 * 모든 필드는 val 로 선언돼 불변이다.
 * 상태 변경이 필요할 때는 copy 로 새 인스턴스를 반환한다.
 *
 * dedupKey 는 동일한 이벤트가 두 번 처리될 때 중복 발송을 막기 위한 멱등 키다.
 * companion object 의 computeDedupKey 로 결정적으로 계산한다.
 *
 * @param id 알림 고유 식별자
 * @param recipientUserId 수신자 UUID
 * @param eventType 이 알림을 발생시킨 이벤트 유형
 * @param channel 알림 전송 채널
 * @param issueKey 연관 이슈 키 (이슈와 무관한 이벤트면 null)
 * @param title 알림 제목
 * @param body 알림 본문 (null 허용)
 * @param payload 원본 이벤트 컨텍스트 — JSON 직렬화 문자열 또는 null
 * @param status 현재 발송 상태
 * @param dedupKey 중복 발송 방지용 멱등 키 — computeDedupKey 로 생성
 * @param readAt 수신자가 읽은 시각 (미읽음이면 null)
 * @param createdAt 알림 생성 시각
 */
data class Notification(
    val id: UUID,
    val recipientUserId: UUID,
    val eventType: NotificationEventType,
    val channel: Channel,
    val issueKey: String?,
    val title: String,
    val body: String?,
    val payload: String?,
    val status: NotificationStatus,
    val dedupKey: String,
    val readAt: Instant?,
    val createdAt: Instant,
) {
    companion object {
        private const val DEDUP_ALGORITHM = "SHA-256"
        private const val DEDUP_SEPARATOR = "|"

        /**
         * 중복 발송 방지를 위한 결정적 dedup 키를 계산한다.
         *
         * 같은 입력이면 항상 같은 64자 소문자 hex 문자열을 반환한다.
         * 입력 중 하나라도 다르면 다른 키가 생성된다.
         *
         * 구성 원소: eventType.wireValue + issueKey("" 로 null 처리) + occurredAt ISO 문자열
         *            + recipientUserId 문자열 + channel.name
         * 각 원소는 DEDUP_SEPARATOR 로 연결한 뒤 SHA-256 해시한다.
         *
         * @param eventType 이벤트 유형
         * @param issueKey 연관 이슈 키 (없으면 null)
         * @param occurredAt 이벤트 발생 시각
         * @param recipientUserId 수신자 UUID
         * @param channel 전송 채널
         * @return 64자 소문자 hex SHA-256 문자열
         */
        fun computeDedupKey(
            eventType: NotificationEventType,
            issueKey: String?,
            occurredAt: Instant,
            recipientUserId: UUID,
            channel: Channel,
        ): String {
            val parts =
                listOf(
                    eventType.wireValue,
                    issueKey ?: "",
                    occurredAt.toString(),
                    recipientUserId.toString(),
                    channel.name,
                )
            val raw = parts.joinToString(DEDUP_SEPARATOR)
            val digest = MessageDigest.getInstance(DEDUP_ALGORITHM)
            val hashBytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}

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
 * read(readAt) 와 archive(archivedAt) 는 2축 독립으로 동작한다.
 * 보관해도 읽음 상태가 바뀌지 않으며, 읽어도 보관 상태가 바뀌지 않는다.
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
 * @param archivedAt 수신자가 보관한 시각 (미보관이면 null)
 * @param actorUserId 이 알림을 발생시킨 행위자 UUID (없으면 null)
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
    val archivedAt: Instant? = null,
    val actorUserId: UUID? = null,
) {
    /**
     * 알림을 읽음 처리한다.
     *
     * readAt 이 이미 설정된 경우(이미 읽음) 멱등하게 this 를 그대로 반환해 최초 읽은 시각을 보존한다.
     * archivedAt 은 변경하지 않는다.
     *
     * @param at 읽은 시각
     * @return readAt 이 설정된 새 Notification (이미 읽었으면 this)
     */
    fun markRead(at: Instant): Notification = if (readAt != null) this else copy(readAt = at)

    /**
     * 알림을 미읽음 처리한다.
     *
     * archivedAt 은 변경하지 않는다.
     *
     * @return readAt 이 null 로 초기화된 새 Notification
     */
    fun markUnread(): Notification = copy(readAt = null)

    /**
     * 알림을 보관함에 넣는다.
     *
     * archivedAt 이 이미 설정된 경우(이미 보관됨) 멱등하게 this 를 그대로 반환해 최초 보관 시각을 보존한다.
     * readAt 은 변경하지 않는다.
     *
     * @param at 보관 시각
     * @return archivedAt 이 설정된 새 Notification (이미 보관됐으면 this)
     */
    fun archive(at: Instant): Notification = if (archivedAt != null) this else copy(archivedAt = at)

    /**
     * 알림을 보관함에서 꺼낸다.
     *
     * readAt 은 변경하지 않는다.
     *
     * @return archivedAt 이 null 로 초기화된 새 Notification
     */
    fun unarchive(): Notification = copy(archivedAt = null)

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
         *            + recipientUserId 문자열 + channel.name (+ commentId — 있을 때만)
         * 각 원소는 DEDUP_SEPARATOR 로 연결한 뒤 SHA-256 해시한다.
         *
         * ## commentId 는 있을 때만 원소가 된다
         * `occurredAt` 만으로는 「어느 댓글인가」를 구분하지 못한다. import 로 들어온 댓글은
         * 원본 시스템의 초 단위 시각을 그대로 쓰므로(`CommentApplicationService` 의
         * `createdAt` 주입 경로), 같은 이슈에 같은 초에 달린 댓글 2건은 나머지 원소가 전부
         * 같아진다. 그때 두 번째 알림은 UNIQUE(dedup_key) 에 걸려 「멱등이 동작했다」는
         * 얼굴로 사라진다 — 에러가 아니라 정상 로그로 유실된다.
         *
         * ★`listOfNotNull` 이라 commentId 가 null 이면 **원소 자체가 없다.** `?: ""` 로 항상
         * 붙이면 구분자가 하나 더 생겨 댓글과 무관한 알림(담당자 지정·상태 전환·스프린트…)의
         * 키까지 전부 바뀌고, 배포 경계에 떠 있던 모든 이벤트가 재전달 시 중복 알림이 된다.
         * 폭발 반경을 댓글 이벤트로만 가둔다.
         *
         * @param eventType 이벤트 유형
         * @param issueKey 연관 이슈 키 (없으면 null)
         * @param occurredAt 이벤트 발생 시각
         * @param recipientUserId 수신자 UUID
         * @param channel 전송 채널
         * @param commentId 알림의 근거가 된 댓글 UUID (댓글에서 비롯되지 않았으면 null)
         * @return 64자 소문자 hex SHA-256 문자열
         */
        @Suppress("LongParameterList") // dedup 키의 구성 원소 목록 그 자체 — 묶으면 무엇이 키에 드는지가 흐려진다
        fun computeDedupKey(
            eventType: NotificationEventType,
            issueKey: String?,
            occurredAt: Instant,
            recipientUserId: UUID,
            channel: Channel,
            commentId: UUID? = null,
        ): String {
            val parts =
                listOfNotNull(
                    eventType.wireValue,
                    issueKey ?: "",
                    occurredAt.toString(),
                    recipientUserId.toString(),
                    channel.name,
                    commentId?.toString(),
                )
            val raw = parts.joinToString(DEDUP_SEPARATOR)
            val digest = MessageDigest.getInstance(DEDUP_ALGORITHM)
            val hashBytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}

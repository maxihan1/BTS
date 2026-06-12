// 알림 발송 기록 저장/조회 repository (dedup_key 멱등 INSERT)

package com.bts.notification.repository

import com.bts.notification.domain.Channel
import com.bts.notification.domain.Notification
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationStatus
import com.bts.notification.jooq.tables.records.NotificationsRecord
import com.bts.notification.jooq.tables.references.NOTIFICATIONS
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import java.util.UUID

/**
 * notifications 테이블의 INSERT(멱등) + 조회를 담당하는 Repository.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 [Transactional] 을 명시한다 (DATA.md §6).
 * ArchUnit 룰4: jOOQ 접근은 repository 레이어에만 허용.
 * ArchUnit 룰5: @Transactional 보유 클래스는 @Repository/@Service/@Component 필수.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class NotificationRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 알림 1건을 삽입한다. dedup_key 충돌 시 DO NOTHING — 멱등 보장.
     *
     * ON CONFLICT (dedup_key) DO NOTHING 을 jOOQ DSL 로 표현하므로
     * 두 번 호출해도 DB 행은 1개만 존재한다 (중복 발송 방지).
     *
     * @param n 삽입할 알림 도메인 객체
     * @return 실제로 행이 삽입됐으면 true, dedup_key 충돌로 skip 됐으면 false
     */
    @Transactional
    fun insertIfAbsent(n: Notification): Boolean {
        log.debug(
            "알림 삽입 시도 — recipientUserId={}, eventType={}, dedupKey={}",
            n.recipientUserId,
            n.eventType.wireValue,
            n.dedupKey,
        )

        val affected =
            dsl.insertInto(NOTIFICATIONS)
                .set(NOTIFICATIONS.ID, n.id)
                .set(NOTIFICATIONS.RECIPIENT_USER_ID, n.recipientUserId)
                .set(NOTIFICATIONS.EVENT_TYPE, n.eventType.wireValue)
                .set(NOTIFICATIONS.CHANNEL, n.channel.name)
                .set(NOTIFICATIONS.ISSUE_KEY, n.issueKey)
                .set(NOTIFICATIONS.TITLE, n.title)
                .set(NOTIFICATIONS.BODY, n.body)
                .set(NOTIFICATIONS.PAYLOAD, n.payload?.let { JSONB.valueOf(it) })
                .set(NOTIFICATIONS.STATUS, n.status.name)
                .set(NOTIFICATIONS.DEDUP_KEY, n.dedupKey)
                .set(NOTIFICATIONS.READ_AT, n.readAt?.atOffset(ZoneOffset.UTC))
                .set(NOTIFICATIONS.CREATED_AT, n.createdAt.atOffset(ZoneOffset.UTC))
                .onConflict(NOTIFICATIONS.DEDUP_KEY)
                .doNothing()
                .execute()

        return affected > 0
    }

    /**
     * 알림 1건의 상태를 SENT 로 갱신한다 (실시간 푸시 성공 직후 호출).
     *
     * 알림 행은 먼저 PENDING 으로 삽입되고, 채널 전송이 성공한 뒤에만 이 메서드로 SENT 로 전이한다.
     * 푸시가 실패하면 PENDING 으로 남아 Inbox(FR-UX-03)가 영속 fallback 이 된다(at-least-once 미보장 푸시).
     *
     * @param id 갱신할 알림 ID
     */
    @Transactional
    fun markSent(id: UUID) {
        dsl.update(NOTIFICATIONS)
            .set(NOTIFICATIONS.STATUS, NotificationStatus.SENT.name)
            .where(NOTIFICATIONS.ID.eq(id))
            .execute()
    }

    /**
     * 수신자 UUID 로 알림 목록을 최신순으로 조회한다.
     *
     * deleted 개념이 없으므로 전체 행을 반환한다. 페이지네이션은 상위 서비스 레이어에서 처리.
     *
     * @param recipientUserId 수신자 UUID
     * @return 해당 수신자의 알림 목록 (created_at DESC 정렬)
     */
    @Transactional(readOnly = true)
    fun findByRecipient(recipientUserId: UUID): List<Notification> {
        return dsl.selectFrom(NOTIFICATIONS)
            .where(NOTIFICATIONS.RECIPIENT_USER_ID.eq(recipientUserId))
            .orderBy(NOTIFICATIONS.CREATED_AT.desc())
            .fetch()
            .map { toDomain(it) }
    }

    /**
     * jOOQ [NotificationsRecord] 를 도메인 [Notification] 로 변환한다.
     *
     * enum 역매핑 실패 시 DB 데이터 손상으로 판단해 IllegalStateException 으로 fail-fast.
     *
     * @param record jOOQ 에서 읽어온 DB 레코드
     * @return 변환된 도메인 객체
     * @throws IllegalStateException DB 에 알 수 없는 enum 문자열이 저장된 경우 (데이터 손상)
     */
    private fun toDomain(record: NotificationsRecord): Notification {
        val eventType =
            NotificationEventType.fromWire(record.eventType)
                ?: error("알 수 없는 event_type 값: ${record.eventType} — DB 데이터 손상")

        val channel =
            Channel.fromWire(record.channel)
                ?: error("알 수 없는 channel 값: ${record.channel} — DB 데이터 손상")

        val status =
            runCatching { NotificationStatus.valueOf(record.status) }.getOrNull()
                ?: error("알 수 없는 status 값: ${record.status} — DB 데이터 손상")

        val id = record.id ?: error("id 가 null — DB 데이터 손상")

        val createdAt =
            record.createdAt?.toInstant()
                ?: error("created_at 이 null — id=$id")

        return Notification(
            id = id,
            recipientUserId = record.recipientUserId,
            eventType = eventType,
            channel = channel,
            issueKey = record.issueKey,
            title = record.title,
            body = record.body,
            payload = record.payload?.data(),
            status = status,
            dedupKey = record.dedupKey,
            readAt = record.readAt?.toInstant(),
            createdAt = createdAt,
        )
    }
}

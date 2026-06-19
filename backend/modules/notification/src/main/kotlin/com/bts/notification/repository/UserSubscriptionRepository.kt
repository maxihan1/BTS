// 사용자별 알림 구독(user_notification_subs) jOOQ 리포지토리

package com.bts.notification.repository

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.UserSubscription
import com.bts.notification.jooq.tables.records.UserNotificationSubsRecord
import com.bts.notification.jooq.tables.references.USER_NOTIFICATION_SUBS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneOffset
import java.util.UUID

/**
 * user_notification_subs 테이블의 upsert / 조회를 담당하는 Repository.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 [Transactional] 을 명시한다 (DATA.md §6).
 *
 * DB 저장 형식.
 * - event_type: [NotificationEventType.wireValue] 문자열 (예: "issue.created")
 * - channel: [Channel.name] 문자열 (예: "IN_APP")
 * - Instant ↔ OffsetDateTime(UTC) 변환은 [NotificationPolicyRepository] 선례와 동일
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class UserSubscriptionRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 사용자 구독 1건을 삽입하거나 갱신한다.
     *
     * `ON CONFLICT (user_id, event_type, channel) DO UPDATE` 로 동작하므로
     * 같은 (userId, eventType, channel) 조합이 이미 존재하면 enabled / updated_at 만 갱신한다.
     * 행이 없으면 새로 INSERT 한다.
     *
     * @param sub 저장할 구독 도메인 객체
     */
    @Transactional
    fun upsert(sub: UserSubscription) {
        log.debug(
            "구독 upsert — userId={}, eventType={}, channel={}, enabled={}",
            sub.userId,
            sub.eventType.wireValue,
            sub.channel.name,
            sub.enabled,
        )

        dsl.insertInto(USER_NOTIFICATION_SUBS)
            .set(USER_NOTIFICATION_SUBS.ID, UUID.randomUUID())
            .set(USER_NOTIFICATION_SUBS.USER_ID, sub.userId)
            .set(USER_NOTIFICATION_SUBS.EVENT_TYPE, sub.eventType.wireValue)
            .set(USER_NOTIFICATION_SUBS.CHANNEL, sub.channel.name)
            .set(USER_NOTIFICATION_SUBS.ENABLED, sub.enabled)
            .set(USER_NOTIFICATION_SUBS.CREATED_AT, sub.createdAt.atOffset(ZoneOffset.UTC))
            .set(USER_NOTIFICATION_SUBS.UPDATED_AT, sub.updatedAt.atOffset(ZoneOffset.UTC))
            .onConflict(
                USER_NOTIFICATION_SUBS.USER_ID,
                USER_NOTIFICATION_SUBS.EVENT_TYPE,
                USER_NOTIFICATION_SUBS.CHANNEL,
            )
            .doUpdate()
            .set(USER_NOTIFICATION_SUBS.ENABLED, sub.enabled)
            .set(USER_NOTIFICATION_SUBS.UPDATED_AT, sub.updatedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }

    /**
     * 특정 사용자의 모든 구독 행을 반환한다.
     *
     * DB 에 행이 없으면 빈 리스트를 반환한다.
     * 알 수 없는 event_type / channel 값이 있으면 해당 행을 건너뛴다(방어 처리).
     *
     * @param userId 조회 대상 사용자 UUID
     * @return 해당 사용자의 구독 목록
     */
    @Transactional(readOnly = true)
    fun findByUser(userId: UUID): List<UserSubscription> {
        return dsl.selectFrom(USER_NOTIFICATION_SUBS)
            .where(USER_NOTIFICATION_SUBS.USER_ID.eq(userId))
            .fetch()
            .mapNotNull { toDomain(it) }
    }

    /**
     * 주어진 (eventType, channel) 조합에서 enabled=false 인 사용자 ID 집합을 반환한다.
     *
     * 워커 배치 필터용 — 알림 발송 전 수신 거부 사용자를 걸러내기 위해 호출한다.
     *
     * - [userIds] 가 비어 있으면 DB 쿼리 없이 빈 [Set] 을 즉시 반환한다 (NFR1 배치 최적화).
     * - [userIds] 에 포함되지 않은 사용자나 행이 없는 사용자는 결과에 포함되지 않는다.
     * - enabled=true 행은 결과에 포함되지 않는다.
     *
     * @param eventType 구독 이벤트 유형
     * @param channel 구독 채널
     * @param userIds 배치 대상 사용자 UUID 컬렉션
     * @return enabled=false 인 userId 집합
     */
    @Transactional(readOnly = true)
    fun fetchDisabled(
        eventType: NotificationEventType,
        channel: Channel,
        userIds: Collection<UUID>,
    ): Set<UUID> {
        if (userIds.isEmpty()) {
            return emptySet()
        }

        return dsl.selectFrom(USER_NOTIFICATION_SUBS)
            .where(USER_NOTIFICATION_SUBS.EVENT_TYPE.eq(eventType.wireValue))
            .and(USER_NOTIFICATION_SUBS.CHANNEL.eq(channel.name))
            .and(USER_NOTIFICATION_SUBS.ENABLED.isFalse)
            .and(USER_NOTIFICATION_SUBS.USER_ID.`in`(userIds))
            .fetch()
            .mapNotNull { it.userId }
            .toSet()
    }

    /**
     * jOOQ [UserNotificationSubsRecord] 를 도메인 [UserSubscription] 으로 변환한다.
     *
     * enum 역매핑 실패 시(DB 에 알 수 없는 값) `null` 을 반환해 호출자가 건너뛸 수 있게 한다.
     *
     * @param record jOOQ 에서 읽어온 DB 레코드
     * @return 변환된 도메인 객체, enum 역매핑 실패 시 null
     */
    private fun toDomain(record: UserNotificationSubsRecord): UserSubscription? {
        val eventType = NotificationEventType.fromWire(record.eventType ?: return null)
            ?: run {
                log.warn("알 수 없는 event_type 값: {} — 행 건너뜀", record.eventType)
                return null
            }

        val channel = Channel.fromWire(record.channel ?: return null)
            ?: run {
                log.warn("알 수 없는 channel 값: {} — 행 건너뜀", record.channel)
                return null
            }

        val userId = record.userId ?: return null
        val enabled = record.enabled ?: return null
        val createdAt = record.createdAt?.toInstant() ?: return null
        val updatedAt = record.updatedAt?.toInstant() ?: return null

        return UserSubscription(
            userId = userId,
            eventType = eventType,
            channel = channel,
            enabled = enabled,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }
}

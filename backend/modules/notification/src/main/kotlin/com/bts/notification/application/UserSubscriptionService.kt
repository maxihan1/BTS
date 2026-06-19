// 사용자별 알림 구독 매트릭스 조회 + PATCH upsert 서비스

package com.bts.notification.application

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.UserSubscription
import com.bts.notification.repository.UserSubscriptionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 사용자별 알림 구독 매트릭스 조회와 PATCH upsert 를 담당하는 애플리케이션 서비스.
 *
 * ## opt-out 기본 원칙
 * DB 에 행이 없는 (eventType, channel) 조합은 enabled=true(수신)로 간주한다.
 * 사용자가 특정 조합을 끄면 [UserSubscriptionRepository.upsert] 를 통해 행이 생성되거나 갱신된다.
 *
 * ## 매트릭스 구조
 * [NotificationEventType.entries] (10종) × [ORDERED_CONFIGURABLE_CHANNELS] (IN_APP, EMAIL) = 20셀.
 * 순서는 항상 eventType 선언 순서 × (IN_APP, EMAIL) 고정이다 (EC11 결정성 보장).
 *
 * ## 채널 화이트리스트
 * [UserSubscription.CONFIGURABLE_CHANNELS] 에 없는 채널을 patch 에 전달하면
 * [IllegalArgumentException] 을 던진다 (EC1).
 * 하나라도 무효한 entry 가 있으면 전체를 거부해 부분 적용을 방지한다 (EC8).
 *
 * Clock 은 생성자로 주입받아 테스트 결정성을 보장한다
 * (memory: AuthController revokeSession time-bomb 교훈).
 *
 * @param repository 사용자 구독 jOOQ 저장소
 * @param clock 시각 주입 (기본값 UTC — 테스트에서 고정 Clock 으로 교체)
 */
@Service
class UserSubscriptionService(
    private val repository: UserSubscriptionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 사용자의 전체 구독 매트릭스를 반환한다.
     *
     * DB 행이 없는 조합은 enabled=true(opt-out 기본)로 채운다.
     * DB 에 행이 있으면 해당 (eventType, channel) 셀의 enabled 값을 오버레이한다.
     *
     * 반환 순서는 [NotificationEventType.entries] 선언 순서 × [ORDERED_CONFIGURABLE_CHANNELS] 고정 순서.
     * 동일 입력에 항상 동일 순서를 보장한다 (EC11).
     *
     * @param userId 구독 매트릭스를 조회할 사용자 UUID
     * @return 20개 [SubscriptionCell] 목록
     */
    @Transactional(readOnly = true)
    fun getMatrix(userId: UUID): List<SubscriptionCell> {
        val stored = repository.findByUser(userId)
        val overrideMap = stored.associate { sub -> (sub.eventType to sub.channel) to sub.enabled }

        return NotificationEventType.entries.flatMap { eventType ->
            ORDERED_CONFIGURABLE_CHANNELS.map { channel ->
                val enabled = overrideMap.getOrDefault(eventType to channel, DEFAULT_ENABLED)
                SubscriptionCell(eventType = eventType, channel = channel, enabled = enabled)
            }
        }
    }

    /**
     * 사용자의 구독 설정을 일괄 갱신하고 갱신된 매트릭스를 반환한다.
     *
     * 검증을 모든 upsert 보다 먼저 수행한다.
     * 하나라도 [UserSubscription.CONFIGURABLE_CHANNELS] 밖 채널이 있으면
     * [IllegalArgumentException] 을 던지고 upsert 를 한 건도 실행하지 않는다 (EC8).
     *
     * [entries] 가 비어 있으면 upsert 없이 현재 매트릭스를 그대로 반환한다 (EC10).
     *
     * @param userId 구독을 갱신할 사용자 UUID
     * @param entries 갱신할 (eventType, channel, enabled) 목록
     * @return 갱신 후 [getMatrix] 결과 (20개 [SubscriptionCell])
     * @throws IllegalArgumentException [UserSubscription.CONFIGURABLE_CHANNELS] 밖 채널이 포함된 경우
     */
    @Transactional
    fun patch(
        userId: UUID,
        entries: List<SubscriptionPatchEntry>,
    ): List<SubscriptionCell> {
        validateChannels(entries)

        val now = clock.instant()
        entries.forEach { entry ->
            val sub =
                UserSubscription(
                    userId = userId,
                    eventType = entry.eventType,
                    channel = entry.channel,
                    enabled = entry.enabled,
                    createdAt = now,
                    updatedAt = now,
                )
            repository.upsert(sub)
            log.debug(
                "구독 patch — userId={}, eventType={}, channel={}, enabled={}",
                userId,
                entry.eventType.wireValue,
                entry.channel.name,
                entry.enabled,
            )
        }

        return getMatrix(userId)
    }

    /**
     * entries 전체의 channel 이 [UserSubscription.CONFIGURABLE_CHANNELS] 에 속하는지 검증한다.
     *
     * 하나라도 화이트리스트 밖이면 [IllegalArgumentException] 을 던진다.
     * 검증은 upsert 호출 전에 수행해야 부분 적용을 방지할 수 있다 (EC8).
     *
     * @param entries 검증할 패치 항목 목록
     * @throws IllegalArgumentException 화이트리스트 밖 채널이 포함된 경우
     */
    private fun validateChannels(entries: List<SubscriptionPatchEntry>) {
        val invalid = entries.filter { entry -> !UserSubscription.isConfigurable(entry.channel) }
        if (invalid.isNotEmpty()) {
            val invalidChannels = invalid.map { entry -> entry.channel }.distinct()
            throw IllegalArgumentException(
                "설정 불가능한 채널이 포함되어 있습니다: $invalidChannels. " +
                    "허용 채널: ${UserSubscription.CONFIGURABLE_CHANNELS}",
            )
        }
    }

    private companion object {
        /** opt-out 기본값 — DB 에 행이 없으면 수신(true)으로 간주한다. */
        private const val DEFAULT_ENABLED = true

        /**
         * 매트릭스 정렬 결정성을 위한 채널 고정 순서 리스트.
         *
         * [UserSubscription.CONFIGURABLE_CHANNELS] 는 Set 이라 순회 순서가 불안정하므로
         * 명시적 리스트를 사용한다 (EC11).
         * 순서 변경은 클라이언트 표현에 영향을 줄 수 있으므로 신중히 검토해야 한다.
         */
        private val ORDERED_CONFIGURABLE_CHANNELS: List<Channel> = listOf(Channel.IN_APP, Channel.EMAIL)
    }
}

/**
 * 사용자 구독 매트릭스의 단일 셀을 나타내는 서비스 레이어 표현.
 *
 * 컨트롤러가 이 타입을 응답 DTO 로 변환한다.
 *
 * @param eventType 구독 이벤트 유형
 * @param channel 구독 채널 (항상 [UserSubscription.CONFIGURABLE_CHANNELS] 중 하나)
 * @param enabled 수신 여부 — false 이면 해당 조합의 알림을 발송하지 않음
 */
data class SubscriptionCell(
    val eventType: NotificationEventType,
    val channel: Channel,
    val enabled: Boolean,
)

/**
 * 구독 매트릭스 PATCH 요청의 단일 항목.
 *
 * 컨트롤러 DTO 에서 이 타입으로 변환 후 [UserSubscriptionService.patch] 에 전달한다.
 *
 * @param eventType 갱신 대상 이벤트 유형
 * @param channel 갱신 대상 채널 — [UserSubscription.CONFIGURABLE_CHANNELS] 에 속해야 한다
 * @param enabled 변경할 수신 여부
 */
data class SubscriptionPatchEntry(
    val eventType: NotificationEventType,
    val channel: Channel,
    val enabled: Boolean,
)

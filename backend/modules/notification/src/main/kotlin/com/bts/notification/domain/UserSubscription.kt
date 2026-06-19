// 사용자별 알림 구독(이벤트×채널 opt-out) 도메인 모델

package com.bts.notification.domain

import java.time.Instant
import java.util.UUID

/**
 * 사용자가 이벤트×채널 조합에 대해 알림 수신 여부를 직접 제어하는 구독 도메인 모델.
 *
 * ## opt-out 기본 원칙
 * DB 행이 없으면 수신(enabled=true)으로 간주한다. 사용자가 특정 조합을 끄면 행이 생성(또는 갱신)된다.
 *
 * ## 관리자 정책과 AND 결합
 * 최종 발송 여부는 [NotificationPolicy](관리자 정책) AND [UserSubscription](사용자 구독) 으로 결정한다.
 * 사용자는 관리자가 허용한 채널만 끌 수 있고, 관리자가 비활성화한 채널을 켤 수는 없다.
 *
 * ## 불변식
 * - 모든 필드는 `val` 로 선언해 한 번 생성된 이후 변경 불가.
 * - 상태 변경이 필요할 때는 [withEnabled] 로 새 인스턴스를 반환한다.
 * - 도메인은 시각(Instant)을 직접 생성하지 않는다. 시각은 호출자가 Clock 으로 주입해 전달한다.
 *   (Clock 의존 제거로 테스트 결정성 보장)
 *
 * @param userId 구독 소유자의 사용자 UUID
 * @param eventType 구독 대상 이벤트 유형
 * @param channel 구독 대상 전송 채널 — [CONFIGURABLE_CHANNELS] 중 하나여야 한다
 * @param enabled 수신 여부 — false 이면 해당 조합의 알림을 발송하지 않음
 * @param createdAt 구독 행 생성 시각
 * @param updatedAt 최종 수정 시각
 */
data class UserSubscription(
    val userId: UUID,
    val eventType: NotificationEventType,
    val channel: Channel,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * [enabled] 상태를 변경한 새 [UserSubscription] 인스턴스를 반환한다.
     *
     * 원본 객체는 변경되지 않는다(불변 copy).
     * [updatedAt] 은 호출자가 전달한 [now] 로 교체된다.
     * 그 외 모든 필드는 원본 그대로 보존된다.
     *
     * @param newEnabled 변경 후 수신 여부
     * @param now 변경 시각 (호출자 Clock 에서 주입)
     * @return enabled 와 updatedAt 이 갱신된 새 인스턴스
     */
    fun withEnabled(
        newEnabled: Boolean,
        now: Instant,
    ): UserSubscription = copy(enabled = newEnabled, updatedAt = now)

    companion object {
        /**
         * 사용자가 직접 설정할 수 있는 채널 화이트리스트.
         *
         * - [Channel.IN_APP]: 서비스 내 알림함 — 인앱 알림은 항상 사용자 제어 대상이다.
         * - [Channel.EMAIL]: 이메일 — 수신 거부 요구가 높아 사용자 제어를 허용한다.
         * - [Channel.SLACK]: 별도 Slack 통합 BC 가 관리하므로 이 BC 에서 제어하지 않는다.
         * - [Channel.TEAMS]: 현재 구현 범위 밖(Phase 2 예정)이라 포함하지 않는다.
         * - [Channel.WEBHOOK]: FR-NT-05 에서 per-project 모델로 구현된 아웃바운드 웹훅이며,
         *   per-user opt-out 모델과 다르므로 포함하지 않는다.
         *
         * controller / service / worker 가 이 상수를 단일 출처로 참조해야 한다.
         */
        val CONFIGURABLE_CHANNELS: Set<Channel> = setOf(Channel.IN_APP, Channel.EMAIL)

        /**
         * 주어진 채널이 사용자가 설정 가능한 채널인지 확인한다.
         *
         * @param channel 확인 대상 채널
         * @return [CONFIGURABLE_CHANNELS] 에 포함되면 `true`, 아니면 `false`
         */
        fun isConfigurable(channel: Channel): Boolean = channel in CONFIGURABLE_CHANNELS
    }
}

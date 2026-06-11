// 알림 정책 도메인 Aggregate — 이벤트×역할×채널 조합의 수신 여부를 결정하는 핵심 모델

package com.bts.notification.domain

import java.time.Instant
import java.util.UUID

/**
 * 알림 정책 Aggregate Root.
 *
 * `projectKey=null` 이면 시스템 기본(전역) 정책이며,
 * `projectKey` 가 있으면 해당 프로젝트에서 전역 기본을 덮어쓰는 프로젝트 전용 정책이다.
 *
 * ## projectKey(문자열)를 쓰는 이유
 * notification BC 는 BC 격리상 issue-tracking 의 projects 테이블을 조회할 수 없어
 * projectKey→projectId(UUID) 변환이 불가하다. 정책을 소비하는 이벤트 payload(예: IssueMentioned)도
 * projectKey(문자열)만 담으므로, 변환 없이 직접 매칭하려면 projectKey 로 저장/평가한다.
 *
 * ## 불변식
 * - 모든 필드는 `val` 로 선언해 한 번 생성된 이후 변경 불가.
 * - 상태 변경이 필요할 때는 [toggle] 로 새 인스턴스를 반환한다.
 * - 도메인은 시각(Instant)을 직접 생성하지 않는다. 시각은 호출자가 Clock 으로 주입해 전달한다.
 *   (Clock 의존 제거로 테스트 결정성 보장 — memory: AuthController revokeSession time-bomb 교훈)
 *
 * @param id 정책 식별자
 * @param projectKey 프로젝트 키 문자열 (null = 전역 기본 정책)
 * @param eventType 이 정책이 적용되는 이벤트 유형
 * @param recipientRole 알림을 수신할 역할
 * @param channel 알림 전송 채널
 * @param enabled 정책 활성 여부 — false 이면 해당 조합의 알림을 발송하지 않음
 * @param createdBy 정책 생성자 UUID (null = 시스템 시드)
 * @param createdAt 생성 시각
 * @param updatedAt 최종 수정 시각
 */
data class NotificationPolicy(
    val id: UUID,
    val projectKey: String?,
    val eventType: NotificationEventType,
    val recipientRole: RecipientRole,
    val channel: Channel,
    val enabled: Boolean,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * 이 정책이 전역(시스템 기본) 정책인지 확인한다.
     *
     * @return `projectKey` 가 null 이면 `true`, 특정 프로젝트에 속하면 `false`
     */
    fun isGlobal(): Boolean = projectKey == null

    /**
     * [enabled] 상태를 전환한 새 [NotificationPolicy] 인스턴스를 반환한다.
     *
     * 원본 객체는 변경되지 않는다(불변 copy).
     * [updatedAt] 은 호출자가 전달한 [now] 로 교체된다.
     * 그 외 모든 필드는 원본 그대로 보존된다.
     *
     * @param newEnabled 변경 후 활성 여부
     * @param now 변경 시각 (호출자 Clock 에서 주입)
     * @return enabled 와 updatedAt 이 갱신된 새 인스턴스
     */
    fun toggle(
        newEnabled: Boolean,
        now: Instant,
    ): NotificationPolicy = copy(enabled = newEnabled, updatedAt = now)
}

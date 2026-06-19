// 사용자 구독 매트릭스 GET/PATCH 엔드포인트 요청·응답 DTO

package com.bts.notification.web.dto

/**
 * 구독 매트릭스의 단일 셀을 표현하는 DTO.
 *
 * 요청(PATCH body)과 응답(GET/PATCH 반환) 양쪽에 공통으로 사용한다.
 *
 * - [eventType]: [com.bts.notification.domain.NotificationEventType.wireValue] 형식 문자열 (예: "issue.commented")
 * - [channel]: [com.bts.notification.domain.Channel.name] 형식 문자열 (예: "EMAIL", "IN_APP")
 * - [enabled]: 수신 여부
 *
 * 요청 시에는 문자열로 수신하여 컨트롤러에서 enum 으로 파싱한다.
 * 알 수 없는 값이면 [IllegalArgumentException] → 400.
 *
 * @param eventType 이벤트 유형 문자열
 * @param channel 채널 이름 문자열
 * @param enabled 수신 여부
 */
data class SubscriptionEntryDto(
    val eventType: String,
    val channel: String,
    val enabled: Boolean,
)

/**
 * 구독 매트릭스 PATCH 요청 바디.
 *
 * [subscriptions] 에 갱신할 셀 목록을 담는다.
 * 빈 목록이면 upsert 없이 현재 매트릭스를 그대로 반환한다 (EC10).
 *
 * 크기 상한은 [MAX_SUBSCRIPTION_PATCH_ENTRIES] 로 제한한다.
 * 정상 매트릭스는 최대 20셀(10 eventType × {IN_APP, EMAIL})이며,
 * 상한은 100으로 설정하여 enum 증가 여유를 포함하면서도 DoS 표면을 차단한다.
 *
 * @param subscriptions 갱신할 셀 목록
 */
data class PatchSubscriptionsRequest(
    val subscriptions: List<SubscriptionEntryDto>,
) {
    companion object {
        /**
         * PATCH body 에 허용되는 [subscriptions] 최대 엔트리 수.
         *
         * 정상 매트릭스 최대 크기는 20셀(10 eventType × 2 channel)이며,
         * enum 값 증가 여유를 두어 100으로 설정한다.
         * 컨트롤러가 이 상한을 명시 검사한다(초과 시 [IllegalArgumentException] → 400).
         * Bean Validation provider 미의존(notification 모듈 슬라이스에 provider 부재) — 명시 검사로 통일.
         */
        const val MAX_SUBSCRIPTION_PATCH_ENTRIES = 100
    }
}

/**
 * 구독 매트릭스 조회·갱신 응답 바디.
 *
 * 전체 20셀(10 eventType × {IN_APP, EMAIL})을 항상 반환한다.
 * `{ "data": { "subscriptions": [...] } }` 형태로 직렬화된다.
 *
 * @param subscriptions 전체 구독 셀 목록 (20개)
 */
data class SubscriptionMatrixResponse(
    val subscriptions: List<SubscriptionEntryDto>,
)

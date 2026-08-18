// 아웃바운드 webhook이 구독 가능한(publishable) 이벤트 wireValue allowlist
package com.bts.search.webhook.domain

/**
 * 아웃바운드 webhook 구독의 `eventFilter`가 참조할 수 있는 발행 가능(publishable) 이벤트 카탈로그.
 *
 * 값은 notification BC `NotificationEventType.wireValue`와 동일한 문자열 계약을 따르지만,
 * BC 격리 원칙(모듈 경계 존중)에 따라 해당 enum을 직접 import하지 않고
 * 이 파일 안에 로컬 문자열 상수로 별도 정의한다.
 */
object WebhookEventCatalog {
    /** 이슈 생성 이벤트의 wireValue. */
    const val ISSUE_CREATED = "issue.created"

    /** 이슈 상태 전환 이벤트의 wireValue. */
    const val ISSUE_TRANSITIONED = "issue.transitioned"

    /** 현재 발행 가능한 이벤트 wireValue 전체 집합. */
    val PUBLISHABLE: Set<String> = setOf(ISSUE_CREATED, ISSUE_TRANSITIONED)

    /**
     * 주어진 [wireValue]가 발행 가능한 이벤트인지 판정한다.
     *
     * @param wireValue 판정할 이벤트 wireValue 문자열.
     * @return [PUBLISHABLE]에 속하면 `true`.
     */
    fun isPublishable(wireValue: String): Boolean = wireValue in PUBLISHABLE
}

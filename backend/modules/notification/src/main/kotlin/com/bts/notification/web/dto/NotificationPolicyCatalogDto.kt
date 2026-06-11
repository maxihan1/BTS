// 알림 정책 카탈로그 응답 DTO — enum 목록을 정적으로 열거하는 읽기 전용 응답

package com.bts.notification.web.dto

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.RecipientRole

/**
 * 카탈로그 응답 내 이벤트 타입 항목.
 *
 * @param value [NotificationEventType.wireValue] 문자열
 * @param publishable 외부 발행 채널 허용 여부
 */
data class EventTypeCatalogItem(
    val value: String,
    val publishable: Boolean,
)

/**
 * 알림 정책 카탈로그 응답 DTO.
 *
 * 프론트엔드가 정책 생성 폼에서 선택 가능한 enum 목록을 한 번에 조회할 수 있도록 제공한다.
 * 서버 측 enum 이 클라이언트 표현의 진실 출처가 되어 drift 를 방지한다.
 *
 * @param eventTypes 지원 이벤트 타입 목록 (wireValue + publishable 메타 포함)
 * @param recipientRoles 지원 수신 역할 목록 (enum 이름 문자열)
 * @param channels 지원 전송 채널 목록 (enum 이름 문자열)
 */
data class PolicyCatalogResponse(
    val eventTypes: List<EventTypeCatalogItem>,
    val recipientRoles: List<String>,
    val channels: List<String>,
) {
    companion object {
        /**
         * [NotificationEventType], [RecipientRole], [Channel] enum 에서 카탈로그를 생성한다.
         *
         * @return 현재 코드 기준 모든 지원 항목을 담은 카탈로그 응답
         */
        fun fromEnums(): PolicyCatalogResponse =
            PolicyCatalogResponse(
                eventTypes =
                    NotificationEventType.entries.map { type ->
                        EventTypeCatalogItem(
                            value = type.wireValue,
                            publishable = type.publishable,
                        )
                    },
                recipientRoles = RecipientRole.entries.map { it.name },
                channels = Channel.entries.map { it.name },
            )
    }
}

// 알림 정책 REST API 요청/응답 DTO — CreatePolicyRequest, TogglePolicyRequest, NotificationPolicyResponse

package com.bts.notification.web.dto

import com.bts.notification.domain.NotificationPolicy
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

/**
 * 알림 정책 생성 요청 DTO.
 *
 * [eventType], [recipientRole], [channel] 은 문자열로 수신하여 컨트롤러에서 enum 으로 파싱한다.
 * 파싱 실패 시 [NotificationPolicyController] 가 400 응답을 반환한다.
 *
 * @param projectKey 프로젝트 키 (null = 전역 기본 정책)
 * @param eventType [com.bts.notification.domain.NotificationEventType.wireValue] 문자열
 * @param recipientRole [com.bts.notification.domain.RecipientRole] 이름 문자열
 * @param channel [com.bts.notification.domain.Channel] 이름 문자열
 * @param enabled 초기 활성 여부 (기본값 true)
 */
data class CreatePolicyRequest(
    val projectKey: String? = null,
    @field:NotBlank
    val eventType: String,
    @field:NotBlank
    val recipientRole: String,
    @field:NotBlank
    val channel: String,
    val enabled: Boolean = true,
)

/**
 * 알림 정책 활성/비활성 토글 요청 DTO.
 *
 * @param enabled 변경할 활성 여부
 */
data class TogglePolicyRequest(
    val enabled: Boolean,
)

/**
 * 알림 정책 응답 DTO.
 *
 * [projectKey] 가 null 이면 전역 정책이므로 JSON 직렬화 시 키를 생략한다 ([JsonInclude.Include.NON_NULL]).
 *
 * @param id 정책 UUID
 * @param projectKey 프로젝트 키 (null = 전역 기본 정책)
 * @param eventType [com.bts.notification.domain.NotificationEventType.wireValue] 문자열
 * @param recipientRole [com.bts.notification.domain.RecipientRole] 이름 문자열
 * @param channel [com.bts.notification.domain.Channel] 이름 문자열
 * @param enabled 활성 여부
 * @param createdAt 생성 시각
 * @param updatedAt 최종 수정 시각
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class NotificationPolicyResponse(
    val id: UUID,
    val projectKey: String?,
    val eventType: String,
    val recipientRole: String,
    val channel: String,
    val enabled: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * 도메인 객체 [NotificationPolicy] 를 응답 DTO 로 변환한다.
         *
         * @param policy 변환할 도메인 객체
         * @return 직렬화 가능한 응답 DTO
         */
        fun from(policy: NotificationPolicy): NotificationPolicyResponse =
            NotificationPolicyResponse(
                id = policy.id,
                projectKey = policy.projectKey,
                eventType = policy.eventType.wireValue,
                recipientRole = policy.recipientRole.name,
                channel = policy.channel.name,
                enabled = policy.enabled,
                createdAt = policy.createdAt,
                updatedAt = policy.updatedAt,
            )
    }
}

// whoami 엔드포인트 응답 DTO — JWT/PAT 인증 사용자 식별 정보 + authMethod + 강제변경/시스템관리자 플래그

package com.atlas.bts.identity.dto

import java.util.UUID

/**
 * whoami 응답 DTO.
 *
 * @property username 사용자명 (PAT 분기는 빈 문자열)
 * @property email 이메일 (없으면 빈 문자열)
 * @property authMethod 인증 방식 ("jwt" 또는 "pat")
 * @property userId 사용자 식별자
 * @property mustChangePassword 강제 비밀번호 변경 필요 여부 (FR-AU-05).
 *   JWT 분기는 local_credentials 의 플래그를 반영하고, PAT 분기는 항상 false 다.
 * @property isSystemAdmin 시스템 전역 관리자 여부 (FR-PM-08).
 *   JWT 분기는 SystemPermissionResolver 판정을 반영하고, PAT 분기는 항상 false 다.
 * @property mfaEnrollmentRequired MFA 강제 등록 필요 여부 (FR-MF-04).
 *   JWT 분기는 access 토큰의 `mfa_enrollment_required` 클레임 값(부재=false)을 그대로 읽어 노출한다.
 *   백엔드 게이트 필터와 동일한 클레임을 단일 출처로 공유하므로 항상 일치하며, 라이브 재계산을 하지 않는다.
 *   PAT 분기는 MFA 강제 컨텍스트와 무관하므로 항상 false 다.
 * @property displayName 화면 표시 이름 (FR-PR-01). JWT 분기는 users.display_name 을 그대로 노출하고,
 *   봇 컨텍스트인 PAT 분기는 프로필 view-layer 를 노출하지 않으므로 null 이다.
 * @property avatarUrl 아바타 다운로드 경로 (FR-PR-01). 파생 근거는 user_profiles.avatar_object_key —
 *   설정돼 있으면 `/api/v1/users/{userId}/avatar`, 미설정이면 null 이다(오브젝트 키 자체는 노출하지 않는다).
 *   PAT 분기는 항상 null 이다.
 * @property statusEmoji 상태 메시지 이모지 (FR-PR-02). 활성 상태(미만료)면 값, 미설정/만료면 null 이다.
 *   파생 근거는 user_statuses(만료 필터는 UserStatusRepository.findActiveByUserId 책임). PAT 분기는 항상 null.
 * @property statusText 상태 메시지 텍스트 (FR-PR-02). statusEmoji 와 동일 규칙. PAT 분기는 항상 null.
 * @property oooActive 부재중(Out of Office) 활성 여부 (FR-PR-03). 활성(`startsAt<=now<endsAt`)이면 true,
 *   미설정/종료/미래예약(비활성)이면 false 다. 파생 근거는 user_ooo
 *   (활성 필터는 OutOfOfficeRepository.findActiveByUserId 책임). PAT 분기는 항상 false.
 * @property oooUntil 부재중 종료 시각(ISO-8601 문자열) (FR-PR-03). [oooActive] 가 true 일 때만 값을 갖고,
 *   그 외는 null 이다. PAT 분기는 항상 null.
 */
data class WhoamiResponse(
    val username: String,
    val email: String,
    val authMethod: String,
    val userId: UUID? = null,
    val mustChangePassword: Boolean,
    val isSystemAdmin: Boolean,
    val mfaEnrollmentRequired: Boolean,
    val displayName: String?,
    val avatarUrl: String?,
    val statusEmoji: String?,
    val statusText: String?,
    val oooActive: Boolean,
    val oooUntil: String?,
)

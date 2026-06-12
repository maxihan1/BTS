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
 */
data class WhoamiResponse(
    val username: String,
    val email: String,
    val authMethod: String,
    val userId: UUID? = null,
    val mustChangePassword: Boolean,
    val isSystemAdmin: Boolean,
    val mfaEnrollmentRequired: Boolean,
)

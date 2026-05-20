// user_external_accounts 테이블 매핑 엔티티 — User × External IdP 식별자 연결

package com.atlas.bts.identity.provider.ldap

import java.time.Instant
import java.util.UUID

/**
 * user_external_accounts 테이블 행 매핑 data class (FR-AU-02).
 *
 * 외부 IdP(LDAP/OIDC/SAML) 의 사용자 식별자 [externalSubject] 를
 * BTS 내부 [userId] 에 연결한다.
 *
 * 계정 잠금 상태 ([lockedUntil]) 와 최근 로그인 시각 ([lastLoginAt]) 도 함께 관리한다.
 */
data class ExternalAccount(
    val id: UUID,
    val providerId: UUID,
    /** 외부 IdP 발급 고유 식별자 (LDAP DN, OIDC sub, SAML NameID) */
    val externalSubject: String,
    val userId: UUID,
    /** LDAP 그룹 DN 목록 — 권한 매핑은 FR-PM-01 에서 처리 */
    val groups: List<String>,
    /** 연속 인증 실패 횟수 (성공 시 0 으로 reset) */
    val failedAttempts: Int,
    /** LockoutPolicy 적용 잠금 만료 시각 (null = 잠금 없음) */
    val lockedUntil: Instant?,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

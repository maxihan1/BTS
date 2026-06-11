// sessions 테이블 행 매핑 도메인 엔티티 — 세션 만료·폐기 상태 판별 포함 (SDD 19.5)

package com.atlas.bts.identity.session

import java.time.Instant
import java.util.UUID

/**
 * 사용자 로그인 세션 도메인 엔티티 (SDD 19.5 §세션 관리).
 *
 * 하나의 로그인 = 하나의 [Session] row. DB의 `sessions` 테이블과 1:1 매핑된다.
 *
 * ## 생명주기
 * 생성(로그인) → 활성 → 만료([expiresAt] 경과) 또는 폐기([revokedAt] 설정).
 *
 * ## 세션 저장 스펙 (SDD 19.5)
 * | 토큰 | 만료 | 저장 |
 * |---|---|---|
 * | Session (DB) | 14일 | sessions 테이블 |
 * | Refresh Token | 14일 | HttpOnly Cookie |
 * | Access JWT | 15분 | sessionStorage |
 *
 * ## 필드
 * - [id]: 세션 식별자. JWT 클레임 `sid` 로 포함되어 revoke 시 일치 확인.
 * - [userId]: BTS 내부 사용자 ID (`users.id` FK).
 * - [providerId]: 인증에 사용된 Provider ("local", "ldap-corp" 등).
 * - [deviceFingerprint]: 신뢰 디바이스 면제(FR-MF-05) 및 보안 이벤트 알림용. null 허용.
 * - [ipAddress]: 보안 감사 로그용 클라이언트 IP. null 허용.
 * - [userAgent]: 보안 감사 로그용 User-Agent 헤더. null 허용.
 * - [createdAt]: 세션 최초 생성 시각.
 * - [expiresAt]: 세션 만료 시각. [isActive] / [isExpired] 판별에 사용.
 * - [lastSeenAt]: 마지막 활동 시각 (sliding window 갱신 대상).
 * - [revokedAt]: 명시적 폐기 시각. null이면 폐기 안 됨.
 * - [revokeReason]: 폐기 사유 (예. "ADMIN_REVOKE", "LOGOUT", "PASSWORD_CHANGED"). null 허용.
 * - [mfaVerified]: 이 세션이 2차 요소(TOTP)까지 통과했는지 (FR-MF-01). JWT `mfa_verified` 클레임의 원천.
 *   refresh 회전 시 [RefreshTokenService.rotate] 가 이 값을 다시 발급 토큰에 전파한다(GAP-1). 기본 false.
 *
 * @see RefreshToken 세션에 귀속되는 Refresh Token (1 Session : N RefreshToken)
 */
data class Session(
    val id: UUID,
    val userId: UUID,
    val providerId: String,
    val deviceFingerprint: String?,
    val ipAddress: String?,
    val userAgent: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant?,
    val revokeReason: String?,
    val mfaVerified: Boolean = false,
) {
    /**
     * 세션이 현재 사용 가능한 상태인지 확인한다.
     *
     * [revokedAt]이 null이고 [expiresAt]이 [now]보다 미래인 경우에만 true.
     * 만료 경계값([expiresAt] == [now])은 만료로 간주하여 false를 반환한다.
     */
    fun isActive(now: Instant): Boolean = revokedAt == null && expiresAt > now

    /**
     * 세션이 만료되었는지 확인한다.
     *
     * [expiresAt] <= [now]이면 true. 폐기 여부와 독립적으로 판별한다.
     */
    fun isExpired(now: Instant): Boolean = expiresAt <= now

    /**
     * 세션이 명시적으로 폐기되었는지 확인한다.
     *
     * [revokedAt]이 설정되어 있으면 true. 만료 여부와 독립적으로 판별한다.
     */
    fun isRevoked(): Boolean = revokedAt != null
}

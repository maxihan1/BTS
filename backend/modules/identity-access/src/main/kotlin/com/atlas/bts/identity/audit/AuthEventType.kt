// 인증 감사 이벤트 유형 enum — 18종 (FR-09-31 + FR-PM-01 + FR-MF-01 + FR-MF-02)

package com.atlas.bts.identity.audit

/**
 * BTS 인증 감사 로그에 기록되는 이벤트 유형 18종 (FR-09-31 + FR-PM-01 + FR-MF-01 + FR-MF-02).
 *
 * - [LOGIN_SUCCESS]: 로그인 성공
 * - [LOGIN_FAILURE]: 로그인 실패 (잘못된 자격증명, 계정 잠금 등)
 * - [LOGOUT]: 단일 디바이스 로그아웃
 * - [LOGOUT_ALL_DEVICES]: 전체 디바이스 로그아웃
 * - [TOKEN_REFRESHED]: Refresh Token으로 Access Token 재발급
 * - [SUSPICIOUS_REFRESH_REPLAY]: Refresh Token 재사용 의심 (리플레이 공격 탐지)
 * - [USER_PROVISIONED]: 신규 사용자 프로비저닝 (LDAP/OIDC 최초 로그인 등)
 * - [PAT_USED]: Personal Access Token 사용
 * - [LDAP_UNAVAILABLE]: LDAP 서버 연결 불가
 * - [PROJECT_MEMBER_ADDED]: 프로젝트 멤버 추가 (부트스트랩 포함)
 * - [PROJECT_ROLE_CHANGED]: 프로젝트 멤버 역할 변경
 * - [PROJECT_MEMBER_REMOVED]: 프로젝트 멤버 제거
 * - [MFA_ENABLED]: TOTP(2FA) 활성화 완료 (enable 코드 검증 성공)
 * - [MFA_CHALLENGE_SUCCESS]: 로그인 2단계 TOTP 코드 검증 성공
 * - [MFA_CHALLENGE_FAILURE]: 로그인 2단계 TOTP 코드 검증 실패 (오답)
 * - [MFA_DISABLED]: TOTP(2FA) 비활성화 (현재 코드 검증 후 해제)
 * - [MFA_BACKUP_CODES_GENERATED]: MFA 백업 코드 발급/재발급 (기존 묶음 전량 교체)
 * - [MFA_BACKUP_CODE_USED]: MFA 백업 코드 1회용 소진 (로그인 복구에 사용)
 */
enum class AuthEventType {
    LOGIN_SUCCESS,
    LOGIN_FAILURE,
    LOGOUT,
    LOGOUT_ALL_DEVICES,
    TOKEN_REFRESHED,
    SUSPICIOUS_REFRESH_REPLAY,
    USER_PROVISIONED,
    PAT_USED,
    LDAP_UNAVAILABLE,
    PROJECT_MEMBER_ADDED,
    PROJECT_ROLE_CHANGED,
    PROJECT_MEMBER_REMOVED,
    MFA_ENABLED,
    MFA_CHALLENGE_SUCCESS,
    MFA_CHALLENGE_FAILURE,
    MFA_DISABLED,
    MFA_BACKUP_CODES_GENERATED,
    MFA_BACKUP_CODE_USED,
}

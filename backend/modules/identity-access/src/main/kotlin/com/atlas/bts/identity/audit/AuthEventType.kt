// 인증 감사 이벤트 유형 enum — 9종 (FR-09-31)

package com.atlas.bts.identity.audit

/**
 * BTS 인증 감사 로그에 기록되는 이벤트 유형 9종 (FR-09-31).
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
}

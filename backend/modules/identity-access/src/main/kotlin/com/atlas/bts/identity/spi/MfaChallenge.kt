// MFA 챌린지 유형 열거형 — FR-MF-01에서 TOTP 추가, WebAuthn 등은 후속 PR

package com.atlas.bts.identity.spi

/**
 * MFA 챌린지 유형.
 *
 * - [TOTP]: Authenticator 앱 기반 시간제한 일회용 코드 (RFC 6238, FR-MF-01).
 * - [NOT_IMPLEMENTED_YET]: 아직 실체화되지 않은 챌린지 placeholder. WebAuthn 등 후속 PR에서
 *   구체 유형으로 대체될 임시 값(기존 dead path 호환을 위해 유지).
 */
enum class MfaChallenge {
    TOTP,
    NOT_IMPLEMENTED_YET,
}

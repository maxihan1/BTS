// MFA 챌린지 유형 열거형 — FR-MF PR에서 sealed class로 확장 예정

package com.atlas.bts.identity.spi

/**
 * MFA 챌린지 유형. 현재는 placeholder.
 * FR-MF PR에서 sealed interface로 확장되어 TOTP/WebAuthn 등 구체 타입이 추가됨.
 */
enum class MfaChallenge {
    NOT_IMPLEMENTED_YET,
}

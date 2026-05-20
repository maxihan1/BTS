// 인증 실패 원인 열거형 — INVALID_CREDENTIALS/INVALID_INPUT/PROVIDER_UNAVAILABLE/ACCOUNT_LOCKED

package com.atlas.bts.identity.spi

/**
 * 인증 실패 시 Failure.reason에 담기는 원인 코드.
 */
enum class FailureReason {
    INVALID_CREDENTIALS,
    INVALID_INPUT,
    PROVIDER_UNAVAILABLE,
    ACCOUNT_LOCKED,
}

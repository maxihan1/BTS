// 인증 결과 VO — sealed interface Success/Failure/RequiresMfa

package com.atlas.bts.identity.spi

/**
 * [AuthenticationProvider.authenticate] 호출 결과.
 *
 * - [Success]: 인증 성공. [Principal]을 포함.
 * - [Failure]: 인증 실패. [FailureReason]으로 원인 구분.
 * - [RequiresMfa]: 1단계 인증 성공 + MFA 추가 인증 필요. [MfaChallenge]로 챌린지 타입 전달.
 */
sealed interface AuthnResult {
    data class Success(val principal: Principal) : AuthnResult

    data class Failure(val reason: FailureReason) : AuthnResult

    data class RequiresMfa(val challenge: MfaChallenge) : AuthnResult
}

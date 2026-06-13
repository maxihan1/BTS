// 비밀번호 변경 서비스 — 정책 검증 + rotate + 다른 세션 무효화 (FR-AU-05)

package com.atlas.bts.identity.credential

import com.atlas.bts.identity.mfa.TrustedDeviceService
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.SessionService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 비밀번호 변경 결과.
 *
 * ## 반환 케이스
 * - [Success]: 변경 완료. 다른 세션·refresh chain 무효화 완료.
 * - [PolicyViolation]: 새 비밀번호가 정책([PasswordPolicy]) 위반. rotate 미호출.
 * - [SameAsCurrent]: 새 비밀번호가 현재 비밀번호와 평문 동일. rotate 미호출.
 *   SameAsCurrent 는 평문 동일성만 단축 검사하며, current 진위(DB 검증)는 수행하지 않는다.
 *   비밀번호 힌트 누출 없음 — 정책 통과 조건에서만 도달 가능.
 * - [CurrentMismatch]: 현재 비밀번호 불일치 ([LocalCredentialService.rotate] = false). 세션 무효화 미호출.
 */
sealed interface ChangePasswordResult {
    data object Success : ChangePasswordResult

    data class PolicyViolation(val violations: List<PasswordPolicyViolation>) : ChangePasswordResult

    data object SameAsCurrent : ChangePasswordResult

    data object CurrentMismatch : ChangePasswordResult
}

/**
 * 비밀번호 변경 서비스 (FR-AU-05).
 *
 * ## 검증 순서
 * 1. [PasswordPolicy.validate] — new 정책 위반 시 즉시 [ChangePasswordResult.PolicyViolation] 반환.
 * 2. new == current 평문 동일성 — [ChangePasswordResult.SameAsCurrent] 반환.
 * 3. [LocalCredentialService.rotate] — old 불일치 시 [ChangePasswordResult.CurrentMismatch] 반환.
 * 4. 세션 무효화 — 현재 세션(currentSid) 제외, 활성 세션마다 [SessionService.revoke] +
 *    [RefreshTokenRepository.revokeChainFromSession] 쌍 호출. dangling refresh token 방지 목적.
 *
 * ## CharArray wipe (DEVELOPMENT.md §1.1 / CONCERN-5)
 * [LocalCredentialService.rotate] 는 전달받은 [CharArray] 를 내부에서 wipe 한다.
 * policy 위반 · same 경로로 early-return 시 rotate 가 호출되지 않으므로,
 * 이 서비스의 finally 블록에서 [current] / [new] 를 직접 wipe 한다.
 * rotate 가 호출된 경우에도 finally 의 fill(' ') 은 멱등(이미 wipe 된 배열에 재적용)이므로 안전.
 *
 * ## 트랜잭션 (DATA.md §6)
 * rotate (credential UPDATE) + 세션/chain 무효화를 단일 경계로 묶는다.
 * 실패 시 모두 롤백되어 비밀번호만 바뀌고 세션이 살아있는 상태를 방지한다.
 *
 * @param localCredentialService 패스워드 rotate 담당
 * @param sessionService 세션 revoke 담당
 * @param refreshTokenRepository refresh chain revoke 담당
 * @param trustedDeviceService 비밀번호 변경 성공 시 신뢰 디바이스 전량 자동폐기 담당 (FR-MF-05)
 */
@Service
class ChangePasswordService(
    private val localCredentialService: LocalCredentialService,
    private val sessionService: SessionService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val trustedDeviceService: TrustedDeviceService,
) {
    /**
     * 비밀번호를 변경하고 현재 세션 외 다른 세션을 무효화한다.
     *
     * @param userId     변경 대상 사용자 ID
     * @param currentSid 현재 요청을 보낸 세션 ID — 이 세션은 무효화에서 제외된다
     * @param current    현재 비밀번호 평문 — 반환 후 wipe
     * @param new        새 비밀번호 평문 — 반환 후 wipe
     * @return [ChangePasswordResult] 변경 결과
     *
     * `@Suppress("ReturnCount")` 사유 — policy·same·rotate 각 단계 guard clause early-return이
     * 검증 순서를 명확히 표현한다. 단일 return 리팩터링은 단계 간 흐름을 오히려 불명확하게 만든다.
     */
    @Suppress("ReturnCount")
    @Transactional
    fun change(
        userId: UUID,
        currentSid: UUID,
        current: CharArray,
        new: CharArray,
    ): ChangePasswordResult {
        try {
            // (1) 새 비밀번호 정책 검증
            val violations = PasswordPolicy.validate(new)
            if (violations.isNotEmpty()) {
                return ChangePasswordResult.PolicyViolation(violations)
            }

            // (2) new == current 평문 동일성 — rotate 전에 배열이 살아있는 상태에서 비교
            if (current.contentEquals(new)) {
                return ChangePasswordResult.SameAsCurrent
            }

            // (3) 현재 비밀번호 검증 + 변경 (rotate 가 내부에서 current/new wipe)
            val rotated = localCredentialService.rotate(userId, current, new)
            if (!rotated) {
                return ChangePasswordResult.CurrentMismatch
            }

            // (4) 다른 세션 무효화 — revoke + chain 쌍 호출
            sessionService.findActiveByUser(userId)
                .filter { it.id != currentSid }
                .forEach { session ->
                    sessionService.revoke(session.id, REVOKE_REASON)
                    refreshTokenRepository.revokeChainFromSession(session.id)
                }

            // (5) 신뢰 디바이스 전량 자동폐기 — 비밀번호 변경 = 보안 이벤트 (FR-MF-05)
            trustedDeviceService.revokeAll(userId)

            return ChangePasswordResult.Success
        } finally {
            // early-return(policy 위반 · same) 경로: rotate 미호출 → 여기서 직접 wipe
            // rotate 호출 경로: rotate 내부에서 이미 wipe 되었으나 재적용은 멱등이므로 안전
            current.fill(' ')
            new.fill(' ')
        }
    }

    companion object {
        /** 비밀번호 변경으로 인한 세션 폐기 사유 — 감사 로그 검색 키 */
        const val REVOKE_REASON = "password_changed"
    }
}

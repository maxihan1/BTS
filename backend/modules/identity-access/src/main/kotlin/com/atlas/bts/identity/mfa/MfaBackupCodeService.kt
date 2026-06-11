// MFA 백업 코드 발급/재발급·검증소진·상태조회 오케스트레이션 서비스 — 감사 emit + 백업 전용 rate-limit (FR-MF-02 Task 5)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * MFA(다단계 인증) 1회용 백업 코드의 발급/재발급·검증소진·상태조회 오케스트레이션 서비스 (FR-MF-02 Task 5).
 * SDD §19.7.
 *
 * Authenticator 앱/하드웨어 키를 분실했을 때의 로그인 복구 수단인 1회용 백업 코드를 다룬다.
 * 순수 생성([BackupCodeGenerator]), 해시([BackupCodeHasher]), 영속([MfaBackupCodeRepository]),
 * brute-force 방어([BackupCodeAttemptLimiter]), 감사([AuthAuditLogService]), TOTP 활성 확인
 * ([TotpSecretRepository])을 조립해 다음 흐름을 제공한다.
 *
 * - [generateOrRegenerate]: TOTP ACTIVE 사용자에게 코드 10개를 발급(기존 묶음 전량 교체).
 * - [verifyAndConsume]: 로그인 복구 시 입력 코드를 검증하고 1회용으로 소진.
 * - [status]: 발급 여부 + 남은 미사용 개수(상태 표시용).
 *
 * ## 보안 불변식 (DEVELOPMENT.md §1.1)
 * - **평문 미저장(§1.1.1)**: 코드는 [BackupCodeHasher] 의 SHA-256 해시로만 영속한다. 평문 10개는
 *   [generateOrRegenerate] 응답([GenerateResult.Generated])에만 한 번 노출되고 저장하지 않는다.
 * - **비밀값 미로깅(§1.1.2)**: 평문 코드·해시는 로그/감사 metadata 에 담지 않는다([emit] 은 providerId
 *   `"mfa"` 와 이벤트 유형만 기록).
 * - **fail-closed**: TOTP 미활성·코드 불일치·rate-limit 등 불명·실패는 모두 거부로 수렴한다(불명은 거부).
 *
 * ## 백업 전용 rate-limit — TOTP 와 독립 ([BackupCodeAttemptLimiter])
 * 백업 코드 실패 카운터는 TOTP 코드 실패([MfaAttemptLimiter])와 **별도** 빈/캐시다. 두 경로를 한
 * 카운터로 합치면 TOTP 오답 누적이 정작 복구 수단인 백업 코드까지 잠가버린다 — 폰을 잃어 TOTP 를
 * 틀린 상황에서 백업 코드로도 못 들어가는 자물쇠가 된다. 이를 막으려고 카운터를 분리한다.
 *
 * ## 트랜잭션 (DATA.md §6)
 * 클래스 레벨 [Transactional] 로 각 변경 메서드를 단일 트랜잭션 경계로 둔다. 발급/소진과 감사 기록을
 * 같은 트랜잭션에 묶어, 상태 변경(replaceAll/consume)과 감사가 함께 commit/rollback 된다.
 * [status] 는 읽기 전용이다.
 *
 * ## 참조
 * - FR-MF-02 Task 5, SDD §19.7 (MFA — 백업 코드)
 */
@Service
@Transactional
class MfaBackupCodeService(
    private val generator: BackupCodeGenerator,
    private val hasher: BackupCodeHasher,
    private val repo: MfaBackupCodeRepository,
    private val limiter: BackupCodeAttemptLimiter,
    private val auditLog: AuthAuditLogService,
    private val totpRepo: TotpSecretRepository,
) {
    /**
     * TOTP 가 ACTIVE 인 사용자에게 1회용 백업 코드 10개를 발급한다(기존 묶음 전량 교체).
     *
     * TOTP 가 [TotpStatus.ACTIVE] 가 아니면 발급하지 않고 [GenerateResult.NotActive] 로 거부한다 —
     * 2FA 가 켜진 사용자에게만 복구 수단을 부여한다(fail-closed). ACTIVE 면 [BackupCodeGenerator] 로
     * 코드 10개를 만들고 [BackupCodeHasher] 로 해시한 뒤 [MfaBackupCodeRepository.replaceAll] 로
     * 이전 묶음(사용/미사용 무관)을 무효화하며 교체한다. 평문 10개는 응답에만 노출되고 저장은 해시만이다(§1.1.1).
     *
     * @param userId 발급 주체 사용자.
     * @return 발급된 평문 코드([GenerateResult.Generated]) 또는 [GenerateResult.NotActive].
     */
    fun generateOrRegenerate(userId: UUID): GenerateResult {
        if (totpRepo.findByUser(userId)?.status != TotpStatus.ACTIVE) {
            return GenerateResult.NotActive
        }
        val plainCodes = generator.generate()
        repo.replaceAll(userId, plainCodes.map { hasher.hash(it) })
        emit(userId, AuthEventType.MFA_BACKUP_CODES_GENERATED)
        return GenerateResult.Generated(plainCodes)
    }

    /**
     * 입력 백업 코드를 검증하고 미사용이면 1회용으로 소진한다(로그인 복구).
     *
     * 백업 전용 [BackupCodeAttemptLimiter] 차단 상태면 소진을 시도하지 않고 [VerifyResult.TooManyAttempts]
     * 로 거부한다. 차단이 아니면 입력을 [BackupCodeHasher] 로 해시해 [MfaBackupCodeRepository.consumeIfUnused]
     * 로 atomic 소진을 시도한다 — 성공(미사용 코드 1개 소진)이면 limiter 를 reset 하고
     * [AuthEventType.MFA_BACKUP_CODE_USED] 를 emit 하며, 실패(없음/이미 사용)면 실패를 기록하고
     * [VerifyResult.InvalidCode] 로 거부한다(fail-closed).
     *
     * @param userId 검증 주체 사용자.
     * @param plain 사용자가 입력한 백업 코드 평문(형식 변형은 [BackupCodeHasher] 가 정규화 흡수).
     * @return [VerifyResult] (Success / InvalidCode / TooManyAttempts).
     *
     * ReturnCount 억제 — rate-limit·소진실패 guard early-return 이 본문보다 명확하다.
     */
    @Suppress("ReturnCount")
    fun verifyAndConsume(
        userId: UUID,
        plain: String,
    ): VerifyResult {
        if (limiter.isBlocked(userId)) return VerifyResult.TooManyAttempts
        if (!repo.consumeIfUnused(userId, hasher.hash(plain))) {
            limiter.recordFailure(userId)
            return VerifyResult.InvalidCode
        }
        limiter.reset(userId)
        emit(userId, AuthEventType.MFA_BACKUP_CODE_USED)
        return VerifyResult.Success
    }

    /**
     * 사용자의 백업 코드 발급 여부와 남은 미사용 개수를 반환한다(상태 표시용).
     *
     * @param userId 확인할 사용자.
     * @return [BackupCodeStatus] (generated = 전체 코드 수 > 0, remaining = 미사용 코드 수).
     */
    @Transactional(readOnly = true)
    fun status(userId: UUID): BackupCodeStatus =
        BackupCodeStatus(
            generated = repo.countTotal(userId) > 0,
            remaining = repo.countUnused(userId),
        )

    /**
     * MFA 백업 코드 감사 이벤트를 기록한다. providerId 는 `"mfa"` 로 고정하며,
     * 평문 코드·해시 등 비밀값은 metadata 에 담지 않는다(§1.1.2).
     */
    private fun emit(
        userId: UUID,
        eventType: AuthEventType,
    ) {
        auditLog.record(
            AuthAuditLog(
                userId = userId,
                eventType = eventType,
                providerId = AUDIT_PROVIDER_ID,
            ),
        )
    }

    /** [generateOrRegenerate] 결과 — 발급된 평문 코드 또는 TOTP 미활성. */
    sealed interface GenerateResult {
        /**
         * 발급 성공. 평문 코드는 응답에만 노출되고 저장은 해시다(§1.1.1).
         *
         * @property codes 새로 발급한 1회용 백업 코드 평문 묶음(10개).
         */
        data class Generated(val codes: List<String>) : GenerateResult

        /** TOTP 가 ACTIVE 가 아니라 발급 불가(2FA 미활성 사용자). */
        data object NotActive : GenerateResult
    }

    /** [verifyAndConsume] 결과. */
    sealed interface VerifyResult {
        /** 미사용 코드 소진 성공. */
        data object Success : VerifyResult

        /** 일치하는 미사용 코드 없음(오답/이미 사용). */
        data object InvalidCode : VerifyResult

        /** 백업 전용 rate-limit 차단 상태. */
        data object TooManyAttempts : VerifyResult
    }

    /** [status] 결과 — 백업 코드 발급 여부와 남은 미사용 개수. */
    data class BackupCodeStatus(
        val generated: Boolean,
        val remaining: Int,
    )

    private companion object {
        /** MFA 감사 이벤트의 providerId 라벨(SSO provider 아님). */
        const val AUDIT_PROVIDER_ID = "mfa"
    }
}

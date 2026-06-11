// MFA 백업 코드 발급/재발급·검증소진·상태조회 오케스트레이션 서비스 — 감사 emit + 백업 전용 rate-limit (FR-MF-02 Task 5)

package com.atlas.bts.identity.mfa

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * MFA(다단계 인증) 1회용 백업 코드의 발급/재발급·검증소진·상태조회 오케스트레이션 서비스 (FR-MF-02 Task 5).
 */
@Service
@Transactional
class MfaBackupCodeService(
    private val generator: BackupCodeGenerator,
    private val hasher: BackupCodeHasher,
    private val repo: MfaBackupCodeRepository,
    private val limiter: BackupCodeAttemptLimiter,
    private val auditLog: com.atlas.bts.identity.audit.AuthAuditLogService,
    private val totpRepo: TotpSecretRepository,
) {
    /** 백업 코드 발급/재발급. */
    fun generateOrRegenerate(userId: UUID): GenerateResult = TODO("Task 5 green")

    /** 백업 코드 검증 + 1회용 소진. */
    fun verifyAndConsume(
        userId: UUID,
        plain: String,
    ): VerifyResult = TODO("Task 5 green")

    /** 백업 코드 상태(발급 여부 + 남은 개수). */
    @Transactional(readOnly = true)
    fun status(userId: UUID): BackupCodeStatus = TODO("Task 5 green")

    /** [generateOrRegenerate] 결과. */
    sealed interface GenerateResult {
        /** 발급 성공 — 평문 코드 묶음(응답에만 노출). */
        data class Generated(val codes: List<String>) : GenerateResult

        /** TOTP 가 ACTIVE 가 아니라 발급 불가. */
        data object NotActive : GenerateResult
    }

    /** [verifyAndConsume] 결과. */
    sealed interface VerifyResult {
        /** 미사용 코드 소진 성공. */
        data object Success : VerifyResult

        /** 일치하는 미사용 코드 없음. */
        data object InvalidCode : VerifyResult

        /** rate-limit 차단 상태. */
        data object TooManyAttempts : VerifyResult
    }

    /** [status] 결과 — 백업 코드 발급 여부와 남은 미사용 개수. */
    data class BackupCodeStatus(
        val generated: Boolean,
        val remaining: Int,
    )
}

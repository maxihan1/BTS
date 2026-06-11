// TOTP(2FA) 설정/활성화/로그인검증/비활성화 오케스트레이션 서비스 — 감사 emit + replay 방어 (FR-MF-01 Task 8)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * TOTP(Time-based One-Time Password, RFC 6238) 기반 다중 요소 인증(MFA)의 오케스트레이션 서비스
 * (FR-MF-01 Task 8). SDD §19.7.
 *
 * 순수 연산([TotpService]), 영속([TotpSecretRepository]), 암호화([MfaSecretEncryptor]),
 * brute-force 방어([MfaAttemptLimiter]), 감사([AuthAuditLogService])를 조립해 다음 흐름을 제공한다.
 *
 * - [setup]: secret 생성 → 암호화 저장(PENDING) → Authenticator 앱용 otpauth/QR 반환.
 * - [enable]: 첫 코드 검증 성공 시 PENDING → ACTIVE 전이(2단계 로그인 적용 시작).
 * - [verifyLogin]: 로그인 2단계에서 코드 검증(+ replay 방어).
 * - [disable]: 현재 코드 검증(step-up)을 거쳐 secret 삭제.
 * - [isEnabled]: ACTIVE 여부.
 *
 * ## 보안 불변식 (DEVELOPMENT.md §1.1)
 * - **평문 secret 저장 금지(§1.1.1)**: secret 은 [MfaSecretEncryptor] 암호문으로만 저장한다.
 *   평문은 [setup] 응답(otpauth/QR)에만 잠깐 노출되고 영속화하지 않는다.
 * - **비밀값/코드 미로깅(§1.1.2)**: secret·입력 코드를 로그/감사 metadata 에 담지 않는다.
 * - **step-up 재인증**: [disable] 은 기존 세션만으로 허용하지 않고 현재 TOTP 코드 검증을 강제해
 *   무단 비활성화를 차단한다(security 에이전트 정의 — 민감 작업 step-up).
 * - **fail-closed**: 코드/replay/차단 등 불명·실패는 모두 거부로 수렴한다(불명은 거부).
 *
 * ## replay 방어 (코드 일회성)
 * [verifyLogin] 은 검증 성공한 time-step 을 [TotpSecretRepository.advanceVerifiedStep] 로
 * 조건부(단조 증가) 기록한다. 같은 코드(=같은 time-step)를 다시 제출하면 갱신이 0행이 되어 거부한다
 * (advisory-lock-bigint-toctou 교훈 — lock 밖 read-then-write 경쟁 차단을 단일 atomic UPDATE 로).
 * 현재 time-step 계산은 주입 [clock] 기준이라 특정 시각에 깨지는 time-bomb 을 피한다
 * (authcontroller-revokesession-timebomb 교훈).
 *
 * ## rate-limit
 * [enable]/[verifyLogin]/[disable] 모두 진입 시 [MfaAttemptLimiter.isBlocked] 로 차단 여부를 확인하고,
 * 코드 오답 시 [MfaAttemptLimiter.recordFailure], 성공 시 [MfaAttemptLimiter.reset] 한다.
 *
 * ## 트랜잭션 (DATA.md §6)
 * 클래스 레벨 [Transactional] 로 각 public 메서드를 단일 트랜잭션 경계로 둔다. 감사 기록은
 * best-effort 가 아니라 같은 트랜잭션에 묶어, 상태 변경(activate/delete)과 감사가 함께 commit/rollback 된다.
 *
 * @param clock replay 방어용 현재 time-step 계산 기준. 테스트는 `Clock.fixed` 로 고정한다.
 */
@Service
@Transactional
class MfaService(
    private val totpService: TotpService,
    private val repo: TotpSecretRepository,
    private val encryptor: MfaSecretEncryptor,
    private val limiter: MfaAttemptLimiter,
    private val auditLog: AuthAuditLogService,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * TOTP secret 을 새로 생성하고 PENDING 으로 저장한 뒤 Authenticator 앱용 provisioning 정보를 만든다.
     *
     * 이미 [TotpStatus.ACTIVE] 인 사용자는 [SetupResult.AlreadyEnabled] 로 거부해 활성 secret 을
     * 실수로 덮어쓰지 않는다. PENDING(미확인) 상태에서는 새 secret 으로 re-setup 을 허용한다.
     * 저장은 암호문만, 평문 secret 은 응답([SetupResult.Created])에만 담긴다(§1.1.1).
     *
     * @param userId 설정 주체 사용자.
     * @param label otpauth 라벨(보통 이메일).
     * @return 생성 정보([SetupResult.Created]) 또는 [SetupResult.AlreadyEnabled].
     */
    fun setup(
        userId: UUID,
        label: String,
    ): SetupResult {
        if (repo.findByUser(userId)?.status == TotpStatus.ACTIVE) {
            return SetupResult.AlreadyEnabled
        }
        val secret = totpService.generateSecret()
        repo.upsertPending(userId, encryptor.encrypt(secret))
        val otpauthUri = totpService.otpauthUri(secret, label)
        return SetupResult.Created(
            otpauthUri = otpauthUri,
            qrPngDataUri = totpService.qrPngDataUri(otpauthUri),
            secretBase32 = secret,
        )
    }

    /**
     * PENDING secret 의 첫 코드를 검증하고 성공 시 [TotpStatus.ACTIVE] 로 전이한다(2단계 로그인 시작).
     *
     * @param userId 활성화 주체 사용자.
     * @param code 사용자가 입력한 6자리 코드.
     * @return [EnableResult] (Success / InvalidCode / TooManyAttempts / NoPending).
     *
     * ReturnCount 억제 — rate-limit·PENDING 부재·오답 guard early-return 이 본문보다 명확하다.
     */
    @Suppress("ReturnCount")
    fun enable(
        userId: UUID,
        code: String,
    ): EnableResult {
        if (limiter.isBlocked(userId)) return EnableResult.TooManyAttempts
        val pending =
            repo.findByUser(userId)?.takeIf { it.status == TotpStatus.PENDING }
                ?: return EnableResult.NoPending

        if (!verifyCode(pending, code)) {
            limiter.recordFailure(userId)
            return EnableResult.InvalidCode
        }
        repo.activate(userId)
        limiter.reset(userId)
        emit(userId, AuthEventType.MFA_ENABLED)
        return EnableResult.Success
    }

    /**
     * 로그인 2단계에서 ACTIVE secret 의 코드를 검증한다(+ replay 방어).
     *
     * 검증 성공 후 [TotpSecretRepository.advanceVerifiedStep] 로 time-step 을 단조 증가시키며,
     * 이미 같거나 큰 step 이 기록돼 있으면(=같은 코드 재사용) replay 로 보고 거부한다.
     *
     * @param userId 검증 주체 사용자.
     * @param code 사용자가 입력한 6자리 코드.
     * @return [VerifyResult] (Success / InvalidCode / TooManyAttempts / NotEnabled).
     *
     * ReturnCount 억제 — rate-limit·ACTIVE 부재·검증실패 guard early-return 이 본문보다 명확하다.
     */
    @Suppress("ReturnCount")
    fun verifyLogin(
        userId: UUID,
        code: String,
    ): VerifyResult {
        if (limiter.isBlocked(userId)) return VerifyResult.TooManyAttempts
        val active =
            repo.findByUser(userId)?.takeIf { it.status == TotpStatus.ACTIVE }
                ?: return VerifyResult.NotEnabled

        val now = clock.instant()
        val matched = totpService.verify(encryptor.decrypt(active.secretCipher), code, now)
        // replay 방어 — 검증 성공이라도 time-step 이 이미 소비됐으면 거부(단조 증가 실패).
        if (!matched || !repo.advanceVerifiedStep(userId, totpService.currentTimeStep(now))) {
            limiter.recordFailure(userId)
            emit(userId, AuthEventType.MFA_CHALLENGE_FAILURE)
            return VerifyResult.InvalidCode
        }
        limiter.reset(userId)
        emit(userId, AuthEventType.MFA_CHALLENGE_SUCCESS)
        return VerifyResult.Success
    }

    /**
     * 현재 TOTP 코드를 검증(step-up)한 뒤 secret 을 삭제해 2FA 를 비활성화한다.
     *
     * 무단 비활성화 차단을 위해 코드 검증 없이는 삭제하지 않는다(민감 작업 step-up).
     *
     * @param userId 비활성화 주체 사용자.
     * @param code 사용자가 입력한 6자리 코드.
     * @return [DisableResult] (Success / InvalidCode / TooManyAttempts / NotEnabled).
     *
     * ReturnCount 억제 — rate-limit·ACTIVE 부재·오답 guard early-return 이 본문보다 명확하다.
     */
    @Suppress("ReturnCount")
    fun disable(
        userId: UUID,
        code: String,
    ): DisableResult {
        if (limiter.isBlocked(userId)) return DisableResult.TooManyAttempts
        val active =
            repo.findByUser(userId)?.takeIf { it.status == TotpStatus.ACTIVE }
                ?: return DisableResult.NotEnabled

        if (!verifyCode(active, code)) {
            limiter.recordFailure(userId)
            return DisableResult.InvalidCode
        }
        repo.deleteByUser(userId)
        limiter.reset(userId)
        emit(userId, AuthEventType.MFA_DISABLED)
        return DisableResult.Success
    }

    /**
     * 사용자의 TOTP 가 [TotpStatus.ACTIVE] 인지 여부.
     *
     * 로그인 흐름에서 2단계 챌린지가 필요한지 판단하는 데 쓴다.
     *
     * @param userId 확인할 사용자.
     * @return ACTIVE 면 `true`.
     */
    @Transactional(readOnly = true)
    fun isEnabled(userId: UUID): Boolean = repo.findByUser(userId)?.status == TotpStatus.ACTIVE

    /**
     * [secret] 암호문을 복호화해 [code] 를 현재 [clock] 시각 기준으로 검증한다(replay step 기록 없이).
     *
     * @return 코드가 ±1 time-step 이내에서 유효하면 `true`.
     */
    private fun verifyCode(
        secret: TotpSecret,
        code: String,
    ): Boolean = totpService.verify(encryptor.decrypt(secret.secretCipher), code, clock.instant())

    /**
     * MFA 감사 이벤트를 기록한다. providerId 는 `"mfa"` 로 고정하며,
     * secret·코드 등 비밀값은 metadata 에 담지 않는다(§1.1.2).
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

    /** [setup] 결과 — 생성 정보 또는 이미 활성. */
    sealed interface SetupResult {
        /**
         * 새 PENDING secret 생성 완료. 평문 secret 은 응답에만 노출되고 저장은 암호문이다.
         *
         * @property otpauthUri Authenticator 앱이 스캔할 provisioning URI.
         * @property qrPngDataUri [otpauthUri] 를 인코딩한 QR PNG data URI.
         * @property secretBase32 평문 base32 secret. QR 스캔 불가 환경의 수동입력 fallback 용으로 응답에만 노출하며,
         *   저장·로그에는 절대 담지 않는다(§1.1.1/§1.1.2). [otpauthUri] 의 `secret` 파라미터와 동일한 값이다.
         */
        data class Created(
            val otpauthUri: String,
            val qrPngDataUri: String,
            val secretBase32: String,
        ) : SetupResult

        /** 이미 ACTIVE 라 활성 secret 을 덮어쓰지 않음. */
        data object AlreadyEnabled : SetupResult
    }

    /** [enable] 결과. */
    sealed interface EnableResult {
        /** PENDING → ACTIVE 전이 성공. */
        data object Success : EnableResult

        /** 코드 오답. */
        data object InvalidCode : EnableResult

        /** rate-limit 차단 상태. */
        data object TooManyAttempts : EnableResult

        /** 활성화할 PENDING secret 이 없음(미설정/이미 ACTIVE). */
        data object NoPending : EnableResult
    }

    /** [verifyLogin] 결과. */
    sealed interface VerifyResult {
        /** 코드 검증 + replay 방어 통과. */
        data object Success : VerifyResult

        /** 코드 오답 또는 replay(time-step 재사용). */
        data object InvalidCode : VerifyResult

        /** rate-limit 차단 상태. */
        data object TooManyAttempts : VerifyResult

        /** ACTIVE secret 이 없음(2단계 미적용 사용자). */
        data object NotEnabled : VerifyResult
    }

    /** [disable] 결과. */
    sealed interface DisableResult {
        /** 코드 검증 후 secret 삭제 성공. */
        data object Success : DisableResult

        /** 코드 오답(무단 비활성화 차단). */
        data object InvalidCode : DisableResult

        /** rate-limit 차단 상태. */
        data object TooManyAttempts : DisableResult

        /** 비활성화할 ACTIVE secret 이 없음. */
        data object NotEnabled : DisableResult
    }

    private companion object {
        /** MFA 감사 이벤트의 providerId 라벨(SSO provider 아님). */
        const val AUDIT_PROVIDER_ID = "mfa"
    }
}

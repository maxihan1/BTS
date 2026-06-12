// WebAuthn(보안키/패스키) 등록·인증·목록·삭제 오케스트레이션 서비스 — 감사 emit + clone/replay 방어 (FR-MF-03 Task 6)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.credential.CredentialRecord
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationData
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.challenge.Challenge
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionsAuthenticatorOutputs
import com.webauthn4j.util.Base64UrlUtil
import com.webauthn4j.util.Base64Util
import com.webauthn4j.util.UUIDUtil
import com.webauthn4j.util.exception.WebAuthnException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * WebAuthn(보안키/패스키) 자격증명의 등록·인증·목록·삭제를 오케스트레이션하는 서비스 (FR-MF-03 Task 6). SDD §19.8.
 *
 * 순수 검증([WebAuthnService]), challenge 발급/소비([WebAuthnChallengeStore]),
 * 영속([WebAuthnCredentialRepository]), brute-force 방어([MfaAttemptLimiter]),
 * 감사([AuthAuditLogService])를 조립해 다음 흐름을 제공한다.
 *
 * - [registerStart]/[registerFinish]: 보안키 등록(attestation) ceremony.
 * - [authenticateStart]/[verifyLogin]: 로그인 2단계 보안키 인증(assertion) ceremony.
 * - [listKeys]/[deleteKey]/[hasActiveKey]: 등록 키 관리.
 *
 * ## 보안 불변식 (DEVELOPMENT.md §1.1)
 * - **fail-closed**: challenge 만료·검증 실패·소유 불일치·clone 의심 등 불명·실패는 모두 거부로
 *   수렴한다(불명은 거부). 검증 실패 원인은 내부 WARN 로그만 남기고 결과는 sealed 로 돌려 원인을
 *   외부로 노출하지 않는다(catch-all 500 변질·내부사정 누출 방지).
 * - **비밀값/식별자 미로깅(§1.1.2)**: challenge·응답 JSON·서명·credentialId·userId 를 로그에 담지 않는다.
 * - **clone/replay 방어**: assertion 의 signCount 를 [WebAuthnCredentialRepository.advanceSignCount]
 *   로 단일 atomic UPDATE 하며, 역행/정체(=clone 의심)는 0행 갱신으로 거부한다(0→0 정상기기 허용).
 * - **소유 검증**: assertion 응답의 credentialId 가 다른 사용자 소유면 거부한다(타인 키 도용 차단).
 *
 * ## challenge 1회용 (fail-closed)
 * 등록/인증 모두 [WebAuthnChallengeStore.consume] 가 `null`(부재/만료/재소비)이면 즉시 거부한다.
 * `null` 은 실패 은폐가 아니라 명시적 거부 신호다.
 *
 * ## rate-limit (verifyLogin)
 * [verifyLogin] 은 진입 시 [MfaAttemptLimiter.isBlocked] 로 차단을 확인하고, 실패 시
 * [MfaAttemptLimiter.recordFailure], 성공 시 [MfaAttemptLimiter.reset] 한다. TOTP/백업코드와
 * **동일 limiter 빈을 공유**해 사용자별 MFA 실패를 일원화 집계한다.
 *
 * ## 트랜잭션 (DATA.md §6)
 * 클래스 레벨 [Transactional] 로 각 public 메서드를 단일 트랜잭션 경계로 둔다. 감사 기록은
 * best-effort 가 아니라 상태 변경(insert/advance/delete)과 같은 트랜잭션에 묶어 함께 commit/rollback 된다.
 *
 * ## userHandle/userName — BC 격리
 * 등록 옵션의 user.id(handle)는 [userId] 바이트를, user.name/displayName 은 [userId] 문자열을
 * 쓴다. 사용자 표시명은 다른 BC(사용자 프로필) 소관이라 직접 조회하지 않는다(BC 격리). 인증기 UI 의
 * 표시 품질보다 격리를 우선한다.
 *
 * @param clock lastUsedAt/createdAt 시각 기준. 테스트는 `Clock.fixed` 로 고정한다.
 *
 * ## LongParameterList 억제
 * 6개 의존성은 모두 단일 책임 협력자(검증/challenge/영속/rate-limit/감사/시계)로 묶을 응집 단위가
 * 없어 그대로 주입한다.
 *
 * ## TooManyFunctions 억제
 * 등록(start/finish)·인증(start/verify)·관리(list/delete/has) 세 ceremony 의 public 메서드와
 * 그 검증/복원/조립/감사 private 헬퍼가 한 오케스트레이션 책임에 응집한다(MfaService 와 동일 성격).
 */
@Suppress("LongParameterList", "TooManyFunctions")
@Service
@Transactional
class WebAuthnSecurityKeyService(
    private val webAuthnService: WebAuthnService,
    private val webAuthnManager: WebAuthnManager,
    private val repo: WebAuthnCredentialRepository,
    private val challengeStore: WebAuthnChallengeStore,
    private val auditLog: AuthAuditLogService,
    private val limiter: MfaAttemptLimiter,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 등록(attestation) 시작 — challenge 를 발급하고 등록 옵션 JSON 을 반환한다.
     *
     * 기존 등록 키의 credentialId 를 excludeCredentials 로 넣어 같은 인증기 중복 등록을 막는다.
     *
     * @param userId 등록 주체 사용자.
     * @return 브라우저 `navigator.credentials.create()` 에 넘길 등록 옵션 JSON.
     */
    @Transactional(readOnly = true)
    fun registerStart(userId: UUID): String {
        val challenge = challengeStore.issue(userId)
        val excludeIds = repo.findByUser(userId).map { Base64UrlUtil.decode(it.credentialId) }
        return webAuthnService.registrationOptionsJson(
            challenge,
            UUIDUtil.convertUUIDToBytes(userId),
            userId.toString(),
            userId.toString(),
            excludeIds,
        )
    }

    /**
     * 등록(attestation) 완료 — challenge 를 소비해 등록 응답을 검증하고 자격증명을 저장한다.
     *
     * @param userId 등록 주체 사용자.
     * @param responseJson 인증기 등록 응답(브라우저가 전달한 PublicKeyCredential JSON).
     * @param name 사용자 지정 별칭(예: "회사 노트북 Touch ID"). null 허용.
     * @return [RegisterResult] (Success / Expired / InvalidRegistration / AlreadyRegistered).
     */
    @Suppress("ReturnCount")
    fun registerFinish(
        userId: UUID,
        responseJson: String,
        name: String?,
    ): RegisterResult {
        val challenge = challengeStore.consume(userId) ?: return RegisterResult.Expired
        val data = verifyRegistration(responseJson, challenge) ?: return RegisterResult.InvalidRegistration
        val authenticatorData = data.attestationObject?.authenticatorData ?: return RegisterResult.InvalidRegistration
        val attested = authenticatorData.attestedCredentialData ?: return RegisterResult.InvalidRegistration
        val credentialId = Base64UrlUtil.encodeToString(attested.credentialId)
        if (repo.findByCredentialId(credentialId) != null) return RegisterResult.AlreadyRegistered
        repo.insert(buildCredential(userId, attested, authenticatorData.signCount, credentialId, name))
        emit(userId, AuthEventType.MFA_WEBAUTHN_REGISTERED)
        return RegisterResult.Success
    }

    /**
     * 인증(assertion) 시작 — challenge 를 발급하고 등록 키를 allowCredentials 로 한 인증 옵션 JSON 을 반환한다.
     *
     * @param userId 인증 주체 사용자.
     * @return 브라우저 `navigator.credentials.get()` 에 넘길 인증 옵션 JSON.
     */
    @Transactional(readOnly = true)
    fun authenticateStart(userId: UUID): String {
        val challenge = challengeStore.issue(userId)
        val allowIds = repo.findByUser(userId).map { Base64UrlUtil.decode(it.credentialId) }
        return webAuthnService.authenticationOptionsJson(challenge, allowIds)
    }

    /**
     * 로그인 2단계 보안키 인증(assertion) 검증 — 서명·소유·clone 방어를 거쳐 성공 시 사용 시각을 기록한다.
     *
     * 실패(만료/검증실패/미등록/타인소유/clone)는 모두 [VerifyResult.InvalidAssertion] 로 수렴하며
     * limiter 실패 기록 + 감사 실패 emit 을 동반한다(원인 비노출 fail-closed).
     *
     * @param userId 인증 주체 사용자.
     * @param responseJson 인증기 인증 응답(브라우저가 전달한 PublicKeyCredential JSON).
     * @return [VerifyResult] (Success / InvalidAssertion / TooManyAttempts).
     */
    @Suppress("ReturnCount")
    fun verifyLogin(
        userId: UUID,
        responseJson: String,
    ): VerifyResult {
        if (limiter.isBlocked(userId)) return VerifyResult.TooManyAttempts
        val challenge = challengeStore.consume(userId) ?: return failAssertion(userId)
        val record = resolveOwnedCredential(userId, responseJson) ?: return failAssertion(userId)
        val authData = verifyAuthentication(responseJson, record.record, challenge) ?: return failAssertion(userId)
        val newSignCount = authData.authenticatorData?.signCount ?: return failAssertion(userId)
        if (!repo.advanceSignCount(record.credential.id, newSignCount)) return failAssertion(userId)
        repo.touchLastUsed(record.credential.id, clock.instant())
        limiter.reset(userId)
        emit(userId, AuthEventType.MFA_CHALLENGE_SUCCESS)
        return VerifyResult.Success
    }

    /**
     * 사용자의 등록 보안키 요약 목록을 반환한다(관리 화면용).
     *
     * @param userId 조회 주체 사용자.
     * @return 등록 키 요약 목록(없으면 빈 목록). 공개키 등 민감 raw 데이터는 제외한다.
     */
    @Transactional(readOnly = true)
    fun listKeys(userId: UUID): List<KeySummary> =
        repo.findByUser(userId).map {
            KeySummary(it.id, it.name, it.createdAt, it.lastUsedAt)
        }

    /**
     * 보안키를 소유 검증과 함께 삭제한다(타인 키 삭제 차단). 삭제 성공 시에만 감사 emit.
     *
     * @param userId 요청자(소유자) 식별자.
     * @param id 삭제할 자격증명 PK.
     * @return 소유 일치로 1행 삭제되면 `true`, 미존재/타인 소유면 `false`.
     */
    fun deleteKey(
        userId: UUID,
        id: UUID,
    ): Boolean {
        val deleted = repo.deleteByIdAndUser(id, userId)
        if (deleted) emit(userId, AuthEventType.MFA_WEBAUTHN_REMOVED)
        return deleted
    }

    /**
     * 사용자가 활성 보안키를 1개 이상 등록했는지 여부(로그인 2단계 진입 판단용).
     *
     * @param userId 확인할 사용자.
     * @return 등록 키가 있으면 `true`.
     */
    @Transactional(readOnly = true)
    fun hasActiveKey(userId: UUID): Boolean = repo.findByUser(userId).isNotEmpty()

    /** 등록 응답을 검증한다. webauthn4j 검증/파싱 예외는 내부 WARN 후 `null`(거부)로 변환한다. */
    private fun verifyRegistration(
        responseJson: String,
        challenge: Challenge,
    ): RegistrationData? =
        runCatchingWebAuthn("registration verification failed") {
            webAuthnService.verifyRegistration(responseJson, challenge)
        }

    /** 인증 응답을 검증한다. webauthn4j 검증/파싱 예외는 내부 WARN 후 `null`(거부)로 변환한다. */
    private fun verifyAuthentication(
        responseJson: String,
        record: CredentialRecord,
        challenge: Challenge,
    ): AuthenticationData? =
        runCatchingWebAuthn("authentication verification failed") {
            webAuthnService.verifyAuthentication(responseJson, record, challenge)
        }

    /**
     * 인증 응답의 credentialId 를 파싱해 소유자([userId])의 등록 자격증명을 찾고 복원한 [CredentialRecord] 와 함께 돌려준다.
     *
     * 응답 파싱 실패·미등록·타인 소유는 모두 `null`(거부)로 수렴한다(소유 검증·fail-closed).
     *
     * ReturnCount 억제 — 파싱실패·credentialId부재·미등록/타인소유 guard early-return 이 본문보다 명확하다.
     */
    @Suppress("ReturnCount")
    private fun resolveOwnedCredential(
        userId: UUID,
        responseJson: String,
    ): OwnedCredential? {
        val authData = parseAuthentication(responseJson) ?: return null
        val rawId = authData.credentialId ?: return null
        val credentialId = Base64UrlUtil.encodeToString(rawId)
        val credential = repo.findByCredentialId(credentialId)?.takeIf { it.userId == userId } ?: return null
        val attested =
            webAuthnService.deserializeAttestedCredentialData(Base64Util.decode(credential.attestedCredentialData))
        return OwnedCredential(credential, restoreRecord(attested, credential.signCount))
    }

    /** 인증 응답 JSON 을 파싱해 credentialId 추출 진입점을 만든다. 파싱 예외는 내부 WARN 후 `null`(거부). */
    private fun parseAuthentication(responseJson: String): AuthenticationData? =
        runCatchingWebAuthn("authentication response parse failed") {
            webAuthnManager.parseAuthenticationResponseJSON(responseJson)
        }

    /**
     * 저장된 attestedCredentialData + signCount 로 인증 검증 기준 [CredentialRecord] 를 복원한다.
     *
     * 인증(assertion) 검증에 실제로 쓰이는 값은 공개키([attested])와 서명 카운터([signCount])뿐이다.
     * non-strict 매니저는 attestation statement 를 검증하지 않으므로 [NoneAttestationStatement] 로,
     * 인증기 확장 출력은 빈 객체로 채운다(복원 시점엔 부재). uvInitialized/backup 플래그는 null(미지정).
     */
    private fun restoreRecord(
        attested: AttestedCredentialData,
        signCount: Long,
    ): CredentialRecord =
        CredentialRecordImpl(
            NoneAttestationStatement(),
            null,
            null,
            null,
            signCount,
            attested,
            AuthenticationExtensionsAuthenticatorOutputs.BuilderForRegistration().build(),
            null,
            null,
            null,
        )

    /** 등록 검증 결과로 영속 [WebAuthnCredential] 을 조립한다(공개키 base64·signCount·aaguid 박제). */
    private fun buildCredential(
        userId: UUID,
        attested: AttestedCredentialData,
        signCount: Long,
        credentialId: String,
        name: String?,
    ): WebAuthnCredential {
        val now = clock.instant()
        val attestedBytes = webAuthnService.serializeAttestedCredentialData(attested)
        return WebAuthnCredential(
            id = UUID.randomUUID(),
            userId = userId,
            credentialId = credentialId,
            attestedCredentialData = Base64Util.encodeToString(attestedBytes),
            signCount = signCount,
            name = name,
            aaguid = attested.aaguid.value?.toString(),
            lastUsedAt = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** assertion 실패 공통 처리 — limiter 실패 기록 + 감사 실패 emit 후 [VerifyResult.InvalidAssertion]. */
    private fun failAssertion(userId: UUID): VerifyResult {
        limiter.recordFailure(userId)
        emit(userId, AuthEventType.MFA_CHALLENGE_FAILURE)
        return VerifyResult.InvalidAssertion
    }

    /** webauthn4j 검증/파싱 블록을 실행하고, 예외는 내부 WARN(원인 비노출) 후 `null` 로 변환한다. */
    private inline fun <T> runCatchingWebAuthn(
        message: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (ex: WebAuthnException) {
            // 검증 실패 원인(서명/challenge/origin/파싱)은 외부로 노출하지 않고 내부 로그만 남긴다(fail-closed).
            log.warn("WebAuthn {}: {}", message, ex.javaClass.simpleName)
            null
        }

    /** MFA 감사 이벤트를 기록한다(providerId="mfa", 비밀값/식별자 metadata 미포함). */
    private fun emit(
        userId: UUID,
        eventType: AuthEventType,
    ) {
        auditLog.record(AuthAuditLog(userId = userId, eventType = eventType, providerId = AUDIT_PROVIDER_ID))
    }

    /** [registerFinish] 결과. */
    sealed interface RegisterResult {
        /** 등록 검증 통과 + 저장 성공. */
        data object Success : RegisterResult

        /** challenge 부재/만료/재소비(1회용 소진). */
        data object Expired : RegisterResult

        /** 등록 응답 검증 실패(서명/challenge/origin/파싱 등). 원인은 비노출. */
        data object InvalidRegistration : RegisterResult

        /** credentialId 가 전역 UNIQUE 충돌(이미 등록됨 — 409). */
        data object AlreadyRegistered : RegisterResult
    }

    /** [verifyLogin] 결과. */
    sealed interface VerifyResult {
        /** 서명·소유·clone 방어 통과. */
        data object Success : VerifyResult

        /** 만료/검증실패/미등록/타인소유/clone 의심 — 원인 비노출 fail-closed. */
        data object InvalidAssertion : VerifyResult

        /** rate-limit 차단 상태. */
        data object TooManyAttempts : VerifyResult
    }

    /**
     * [listKeys] 의 보안키 요약(공개키 등 민감 raw 데이터 제외).
     *
     * @property id 자격증명 PK.
     * @property name 사용자 지정 별칭. null 허용.
     * @property createdAt 등록 시각.
     * @property lastUsedAt 마지막 로그인 성공 시각. null=미사용.
     */
    data class KeySummary(
        val id: UUID,
        val name: String?,
        val createdAt: Instant,
        val lastUsedAt: Instant?,
    )

    /** verifyLogin 내부 — 소유 검증된 저장 자격증명과 복원한 검증 기준 record 묶음. */
    private data class OwnedCredential(
        val credential: WebAuthnCredential,
        val record: CredentialRecord,
    )

    private companion object {
        /** MFA 감사 이벤트의 providerId 라벨(SSO provider 아님). */
        const val AUDIT_PROVIDER_ID = "mfa"
    }
}

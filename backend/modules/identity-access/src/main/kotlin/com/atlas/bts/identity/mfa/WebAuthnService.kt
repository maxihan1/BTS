// WebAuthn(패스키/보안키) 등록·인증 옵션 생성과 응답 검증을 webauthn4j 로 수행하는 서비스 (FR-MF-03 Task 5)

package com.atlas.bts.identity.mfa

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecord
import com.webauthn4j.data.AttestationConveyancePreference
import com.webauthn4j.data.AuthenticationData
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticatorSelectionCriteria
import com.webauthn4j.data.AuthenticatorTransport
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialDescriptor
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.ResidentKeyRequirement
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.Challenge
import com.webauthn4j.server.ServerProperty
import org.springframework.stereotype.Service

/**
 * WebAuthn(패스키/보안키) 등록·인증 옵션 생성과 응답 검증을 담당하는 서비스 (FR-MF-03 Task 5).
 *
 * ## 역할
 * - 등록(attestation)/인증(assertion) 시작 시 브라우저가 인증기에 넘길 **옵션 JSON** 을 생성한다.
 * - 인증기가 돌려준 응답 JSON 을 webauthn4j 로 검증한다(서명·challenge·origin·rpId 일치).
 * - 검증으로 얻은 credential 의 공개키 데이터를 ByteArray 로 직렬화/역직렬화해 영속 계층에 넘긴다.
 *
 * ## RP 식별 — [WebAuthnProperties]
 * 옵션의 rp/원본 검증 기준은 모두 [properties] (rpId/rpName/origin)에서 가져온다. 응답의 origin·
 * rpId 가 이 값과 일치하지 않으면 [webAuthnManager] 가 검증 예외를 던진다(피싱/replay 방어).
 *
 * ## attestation = none / userVerification = preferred
 * 사내 환경이라 인증기 제조사 검증(strict attestation)은 불필요하므로 attestation conveyance 를
 * none 으로, 사용자 검증(PIN/지문)은 preferred(강제 아님)로 둔다. residentKey 는 discouraged
 * (서버측 credential 목록 기반 — 디스커버러블 credential 불요).
 *
 * ## 검증 강도 — userVerificationRequired=false, userPresenceRequired=true
 * userVerification=preferred 와 정합하도록 검증 측 UV 강제는 false 로, UP(사용자가 인증기를 실제로
 * 만졌는지)는 true 로 둔다(WebAuthn 필수 불변식).
 *
 * ## 보안 주의
 * 옵션/응답 JSON·challenge·서명 raw 값을 로깅하지 않는다(§1.1.2). 검증 실패는 webauthn4j 의
 * `VerificationException` 으로 전파되며 본 서비스에서 삼키지 않는다(fail-closed).
 *
 * @see WebAuthnProperties
 * @see WebAuthnConfig
 */
@Service
class WebAuthnService(
    private val webAuthnManager: WebAuthnManager,
    private val properties: WebAuthnProperties,
    private val objectConverter: ObjectConverter,
) {
    private val attestedCredentialDataConverter = AttestedCredentialDataConverter(objectConverter)

    /** ES256(-7) · RS256(-257) — 지원 공개키 알고리즘(브라우저/플랫폼 인증기 호환 우선순위). */
    private val pubKeyCredParams: List<PublicKeyCredentialParameters> =
        listOf(
            PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256),
            PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.RS256),
        )

    /**
     * 등록(attestation) 시작 옵션 [PublicKeyCredentialCreationOptions] 를 만들어 JSON 문자열로 직렬화한다.
     *
     * 브라우저는 이 JSON 을 `navigator.credentials.create()` 에 넘겨 인증기에 등록을 요청한다.
     * [excludeCredentialIds] 에 사용자의 기존 credential id 를 넣으면 같은 인증기 중복 등록을 막는다.
     *
     * @param challenge 이번 등록 ceremony 의 서명 대상 nonce.
     * @param userHandle 사용자 식별 바이트열(권장 16바이트, PII 비포함 무작위값).
     * @param userName 로그인 식별자(인증기 UI 표시).
     * @param displayName 사용자에게 보일 이름(인증기 UI 표시).
     * @param excludeCredentialIds 제외할 기존 credential id 목록(중복 등록 방지).
     * @return 직렬화된 등록 옵션 JSON.
     */
    fun registrationOptionsJson(
        challenge: Challenge,
        userHandle: ByteArray,
        userName: String,
        displayName: String,
        excludeCredentialIds: List<ByteArray>,
    ): String {
        val options =
            PublicKeyCredentialCreationOptions(
                PublicKeyCredentialRpEntity(properties.rpId, properties.rpName),
                PublicKeyCredentialUserEntity(userHandle, userName, displayName),
                challenge,
                pubKeyCredParams,
                null,
                excludeCredentialIds.map(::descriptor),
                authenticatorSelection,
                AttestationConveyancePreference.NONE,
                null,
            )
        return objectConverter.jsonConverter.writeValueAsString(options)
    }

    /**
     * 등록 응답 JSON 을 검증하고 [RegistrationData] 를 반환한다.
     *
     * 서명·challenge·origin·rpId 일치를 모두 확인한다. 불일치/위변조 시 webauthn4j 가
     * `VerificationException` 을 던지며, 본 서비스는 이를 삼키지 않는다(fail-closed).
     *
     * @param responseJson 인증기 등록 응답(브라우저가 전달한 PublicKeyCredential JSON).
     * @param challenge 등록 시작 시 발급했던 challenge(검증 대상 nonce).
     * @return 검증된 [RegistrationData] (attestationObject 등 포함).
     */
    fun verifyRegistration(
        responseJson: String,
        challenge: Challenge,
    ): RegistrationData {
        val parameters = RegistrationParameters(serverProperty(challenge), pubKeyCredParams, false, true)
        return webAuthnManager.verifyRegistrationResponseJSON(responseJson, parameters)
    }

    /**
     * 인증(assertion) 시작 옵션 [PublicKeyCredentialRequestOptions] 를 만들어 JSON 문자열로 직렬화한다.
     *
     * 브라우저는 이 JSON 을 `navigator.credentials.get()` 에 넘겨 인증기에 서명을 요청한다.
     * [allowCredentialIds] 로 이 사용자가 등록한 credential 만 허용한다.
     *
     * @param challenge 이번 인증 ceremony 의 서명 대상 nonce.
     * @param allowCredentialIds 허용할 credential id 목록(사용자의 등록 credential).
     * @return 직렬화된 인증 옵션 JSON.
     */
    fun authenticationOptionsJson(
        challenge: Challenge,
        allowCredentialIds: List<ByteArray>,
    ): String {
        val options =
            PublicKeyCredentialRequestOptions(
                challenge,
                null,
                properties.rpId,
                allowCredentialIds.map(::descriptor),
                UserVerificationRequirement.PREFERRED,
                null,
            )
        return objectConverter.jsonConverter.writeValueAsString(options)
    }

    /**
     * 인증 응답 JSON 을 등록된 [credentialRecord] 기준으로 검증하고 [AuthenticationData] 를 반환한다.
     *
     * 서명을 저장된 공개키로 검증하고 challenge·origin·rpId 일치 및 signCount 진행을 확인한다.
     * 불일치/위변조 시 webauthn4j 가 `VerificationException` 을 던진다(fail-closed).
     *
     * @param responseJson 인증기 인증 응답(브라우저가 전달한 PublicKeyCredential JSON).
     * @param credentialRecord 검증 기준이 되는 저장된 credential(공개키·signCount).
     * @param challenge 인증 시작 시 발급했던 challenge(검증 대상 nonce).
     * @return 검증된 [AuthenticationData].
     */
    fun verifyAuthentication(
        responseJson: String,
        credentialRecord: CredentialRecord,
        challenge: Challenge,
    ): AuthenticationData {
        val parameters = AuthenticationParameters(serverProperty(challenge), credentialRecord, null, false, true)
        return webAuthnManager.verifyAuthenticationResponseJSON(responseJson, parameters)
    }

    /**
     * 검증으로 얻은 [AttestedCredentialData] (공개키 포함)를 영속 가능한 ByteArray 로 직렬화한다.
     *
     * webauthn4j 의 [AttestedCredentialDataConverter] 가 CBOR 로 인코딩한다. 역변환은
     * [deserializeAttestedCredentialData] 가 수행한다.
     *
     * @param data 직렬화할 attested credential data.
     * @return CBOR 인코딩 ByteArray(DB BYTEA 등에 저장).
     */
    fun serializeAttestedCredentialData(data: AttestedCredentialData): ByteArray {
        return attestedCredentialDataConverter.convert(data)
    }

    /**
     * [serializeAttestedCredentialData] 가 만든 ByteArray 를 [AttestedCredentialData] 로 복원한다.
     *
     * @param bytes CBOR 인코딩 ByteArray(저장된 credential).
     * @return 복원된 [AttestedCredentialData] (인증 검증의 공개키 출처).
     */
    fun deserializeAttestedCredentialData(bytes: ByteArray): AttestedCredentialData {
        return attestedCredentialDataConverter.convert(bytes)
    }

    /** userVerification=preferred · residentKey=discouraged 인증기 선택 기준. */
    private val authenticatorSelection: AuthenticatorSelectionCriteria =
        AuthenticatorSelectionCriteria(
            null,
            ResidentKeyRequirement.DISCOURAGED,
            UserVerificationRequirement.PREFERRED,
        )

    /** 옵션의 (exclude/allow) credential 목록 항목 — type=public-key, transports 미지정. */
    private fun descriptor(credentialId: ByteArray): PublicKeyCredentialDescriptor =
        PublicKeyCredentialDescriptor(
            PublicKeyCredentialType.PUBLIC_KEY,
            credentialId,
            emptySet<AuthenticatorTransport>(),
        )

    /** origin/rpId/challenge 로 검증 기준 [ServerProperty] 를 만든다(피싱·replay 방어 기준값). */
    private fun serverProperty(challenge: Challenge): ServerProperty {
        return ServerProperty(Origin(properties.origin), properties.rpId, challenge)
    }
}

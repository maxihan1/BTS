// WebAuthn(패스키/보안키) 등록·인증 옵션 생성과 응답 검증을 webauthn4j 로 수행하는 서비스 (FR-MF-03 Task 5)

package com.atlas.bts.identity.mfa

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecord
import com.webauthn4j.data.AuthenticationData
import com.webauthn4j.data.RegistrationData
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.client.challenge.Challenge
import org.springframework.stereotype.Service

/**
 * WebAuthn 등록/인증 옵션 생성과 응답 검증을 담당하는 서비스 (FR-MF-03 Task 5).
 *
 * 현재는 RED 단계 스텁이다. 후속 GREEN 단계에서 webauthn4j 호출로 채운다.
 */
@Service
class WebAuthnService(
    private val webAuthnManager: WebAuthnManager,
    private val properties: WebAuthnProperties,
    private val objectConverter: ObjectConverter,
) {
    /** 등록 옵션 JSON 생성 (미구현 — GREEN 단계에서 채움). */
    fun registrationOptionsJson(
        challenge: Challenge,
        userHandle: ByteArray,
        userName: String,
        displayName: String,
        excludeCredentialIds: List<ByteArray>,
    ): String = TODO("GREEN: registrationOptionsJson")

    /** 등록 응답 검증 (미구현 — GREEN 단계에서 채움). */
    fun verifyRegistration(
        responseJson: String,
        challenge: Challenge,
    ): RegistrationData = TODO("GREEN: verifyRegistration")

    /** 인증 옵션 JSON 생성 (미구현 — GREEN 단계에서 채움). */
    fun authenticationOptionsJson(
        challenge: Challenge,
        allowCredentialIds: List<ByteArray>,
    ): String = TODO("GREEN: authenticationOptionsJson")

    /** 인증 응답 검증 (미구현 — GREEN 단계에서 채움). */
    fun verifyAuthentication(
        responseJson: String,
        credentialRecord: CredentialRecord,
        challenge: Challenge,
    ): AuthenticationData = TODO("GREEN: verifyAuthentication")

    /** attestedCredentialData → ByteArray 직렬화 (미구현 — GREEN 단계에서 채움). */
    fun serializeAttestedCredentialData(data: AttestedCredentialData): ByteArray =
        TODO("GREEN: serializeAttestedCredentialData")

    /** ByteArray → attestedCredentialData 역직렬화 (미구현 — GREEN 단계에서 채움). */
    fun deserializeAttestedCredentialData(bytes: ByteArray): AttestedCredentialData =
        TODO("GREEN: deserializeAttestedCredentialData")
}
